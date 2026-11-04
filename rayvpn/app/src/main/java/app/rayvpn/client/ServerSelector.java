package app.rayvpn.client;

import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Picks the server for one of the three connection modes. Runs in the VPN
 * service (the "backend" side of the app); the UI only passes the mode.
 *
 * <ul>
 *   <li>{@link Mode#FAST}: connect to the first server (in list order) that answers a ping.</li>
 *   <li>{@link Mode#BALANCE}: test each server 3 times; the first whose average is under 200 ms wins.
 *       If none qualifies, the fastest one that answered is used.</li>
 *   <li>{@link Mode#BEST}: ping every server once, take the 10 lowest pings and run a small
 *       download test on those; the highest download speed wins.</li>
 * </ul>
 */
public final class ServerSelector {

    public enum Mode {
        FAST("fast"), BALANCE("balance"), BEST("best");

        public final String key;

        Mode(String key) {
            this.key = key;
        }

        public static Mode fromKey(String key) {
            if ("fast".equals(key)) return FAST;
            if ("best".equals(key)) return BEST;
            return BALANCE;
        }
    }

    public interface Progress {
        void update(String text);
    }

    public interface Cancel {
        boolean isCancelled();
    }

    static final long BALANCE_LIMIT_MS = 200L;
    static final int BALANCE_SAMPLES = 3;
    static final int BEST_SHORTLIST = 10;
    /** Size of the download used for the speed test; kept small to save the user's data. */
    static final int SPEED_BYTES = 300 * 1024;
    static final int SPEED_MIN_BYTES = 32 * 1024;
    private static final int TCP_GATE_MS = 1300;
    private static final int SPEED_TIMEOUT_MS = 8000;
    private static final String PING_URL = "https://www.gstatic.com/generate_204";
    private static final String SPEED_HOST = "speed.cloudflare.com";
    private static final String SPEED_PATH = "/__down?bytes=" + SPEED_BYTES;

    /**
     * Every call into the native core goes through this lock; the core is not
     * safe to drive from two threads at once.
     */
    static final Object CORE_LOCK = new Object();

    private static final SecureRandom RANDOM = new SecureRandom();

    private ServerSelector() {}

    /**
     * @return the chosen profile, or null if {@code cancel} fired first
     * @throws IllegalStateException with a user-facing message when no server answered
     */
    public static Profile select(Mode mode, List<Profile> profiles, Progress progress, Cancel cancel) {
        if (profiles == null || profiles.isEmpty()) {
            throw new IllegalStateException("فهرست سرورها هنوز آماده نیست");
        }
        switch (mode) {
            case FAST:
                return fast(profiles, progress, cancel);
            case BEST:
                return best(profiles, progress, cancel);
            default:
                return balance(profiles, progress, cancel);
        }
    }

    // ── modes ──────────────────────────────────────────────────────────────

    private static Profile fast(List<Profile> profiles, Progress progress, Cancel cancel) {
        for (int i = 0; i < profiles.size(); i++) {
            if (cancel.isCancelled()) return null;
            Profile p = profiles.get(i);
            progress.update("تست سرور " + (i + 1) + " از " + profiles.size());
            if (!tcpOk(p)) continue;
            if (delayOnce(p) >= 0) return p;
        }
        throw new IllegalStateException("هیچ سروری پاسخ نداد");
    }

    private static Profile balance(List<Profile> profiles, Progress progress, Cancel cancel) {
        Profile fallback = null;
        double fallbackAvg = Double.MAX_VALUE;
        for (int i = 0; i < profiles.size(); i++) {
            if (cancel.isCancelled()) return null;
            Profile p = profiles.get(i);
            progress.update("تست سرور " + (i + 1) + " از " + profiles.size());
            if (!tcpOk(p)) continue;
            long sum = 0;
            int answered = 0;
            for (int s = 0; s < BALANCE_SAMPLES; s++) {
                if (cancel.isCancelled()) return null;
                long ms = delayOnce(p);
                if (ms >= 0) {
                    sum += ms;
                    answered++;
                }
            }
            if (answered == 0) continue;
            double avg = (double) sum / answered;
            if (avg < BALANCE_LIMIT_MS) return p;
            if (avg < fallbackAvg) {
                fallbackAvg = avg;
                fallback = p;
            }
        }
        if (fallback != null) return fallback;
        throw new IllegalStateException("هیچ سروری پاسخ نداد");
    }

    private static Profile best(List<Profile> profiles, Progress progress, Cancel cancel) {
        List<Scored> pinged = new ArrayList<>();
        for (int i = 0; i < profiles.size(); i++) {
            if (cancel.isCancelled()) return null;
            Profile p = profiles.get(i);
            progress.update("تست پینگ " + (i + 1) + " از " + profiles.size());
            if (!tcpOk(p)) continue;
            long ms = delayOnce(p);
            if (ms >= 0) pinged.add(new Scored(p, ms));
        }
        if (pinged.isEmpty()) throw new IllegalStateException("هیچ سروری پاسخ نداد");

        Collections.sort(pinged, new Comparator<Scored>() {
            @Override
            public int compare(Scored a, Scored b) {
                return Long.compare(a.ms, b.ms);
            }
        });
        int count = Math.min(BEST_SHORTLIST, pinged.size());
        Profile fastest = null;
        double fastestBps = -1;
        for (int i = 0; i < count; i++) {
            if (cancel.isCancelled()) return null;
            Profile p = pinged.get(i).profile;
            progress.update("تست سرعت " + (i + 1) + " از " + count);
            double bps = speedOnce(p);
            if (bps > fastestBps) {
                fastestBps = bps;
                fastest = p;
            }
        }
        if (fastest != null && fastestBps > 0) return fastest;
        // the speed test failed everywhere: the lowest ping is still a good answer
        return pinged.get(0).profile;
    }

    private static final class Scored {
        final Profile profile;
        final long ms;

        Scored(Profile profile, long ms) {
            this.profile = profile;
            this.ms = ms;
        }
    }

    // ── measurements ───────────────────────────────────────────────────────

    /** Silent TCP probe to skip servers that cannot answer at all (UDP transports are exempt). */
    private static boolean tcpOk(Profile p) {
        if (!needsTcpGate(p)) return true;
        return tcpPing(p.address, p.port, TCP_GATE_MS) >= 0;
    }

    /** One real request through the config (Xray core + HTTP 204). -1 when it failed. */
    static long delayOnce(Profile p) {
        synchronized (CORE_LOCK) {
            try {
                String cfg = XrayConfigBuilder.buildDelay(p);
                long ms = Libv2ray.measureOutboundDelay(cfg, PING_URL);
                return ms < 0 ? -1L : ms;
            } catch (Throwable e) {
                return -1L;
            }
        }
    }

    /**
     * Downloads {@link #SPEED_BYTES} through a short-lived core running this config.
     * @return bytes per second, or -1 when the download did not complete
     */
    static double speedOnce(Profile p) {
        int port = freePort();
        if (port < 0) return -1;
        XrayConfigBuilder.LocalSocks socks = new XrayConfigBuilder.LocalSocks(port, token(), token());
        synchronized (CORE_LOCK) {
            CoreController core = null;
            try {
                core = Libv2ray.newCoreController(new SilentHandler());
                core.startLoop(XrayConfigBuilder.buildProbe(p, socks), 0);
                if (!waitListening(port, 3000)) return -1;
                SocksHttp.Result r = SocksHttp.get(true, SPEED_HOST, 443, SPEED_PATH,
                        port, socks.user, socks.pass, SPEED_BYTES, SPEED_TIMEOUT_MS);
                if (r.status != 200 || r.bodyBytes < SPEED_MIN_BYTES) return -1;
                double seconds = Math.max(r.elapsedNs / 1e9, 0.001);
                return r.bodyBytes / seconds;
            } catch (Throwable e) {
                return -1;
            } finally {
                try {
                    if (core != null && core.getIsRunning()) core.stopLoop();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** UDP transports cannot be probed with a TCP handshake. */
    static boolean needsTcpGate(Profile p) {
        if (p == null) return false;
        if ("hysteria".equalsIgnoreCase(p.protocol)) return false;
        if ("kcp".equalsIgnoreCase(p.network)) return false;
        if ("raw".equalsIgnoreCase(p.protocol)) return false;
        return p.address != null && !p.address.isEmpty() && p.port > 0;
    }

    static long tcpPing(String host, int port, int timeoutMs) {
        if (host == null || host.isEmpty() || port <= 0) return -1;
        String h = host.trim();
        if (h.startsWith("[") && h.endsWith("]") && h.length() > 2) h = h.substring(1, h.length() - 1);
        Socket socket = new Socket();
        long start = System.nanoTime();
        try {
            socket.connect(new InetSocketAddress(h, port), timeoutMs);
            long ms = (System.nanoTime() - start) / 1000000L;
            return ms < 1 ? 1 : ms;
        } catch (Exception e) {
            return -1;
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** A free loopback TCP port (races are harmless: the port is only used for a few seconds). */
    static int freePort() {
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return ss.getLocalPort();
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean waitListening(int port, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return true;
            } catch (Exception ignored) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return false;
                }
            }
        }
        return false;
    }

    /** Random token for the loopback SOCKS credentials. */
    static String token() {
        byte[] b = new byte[12];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte value : b) sb.append(String.format(Locale.US, "%02x", value));
        return sb.toString();
    }

    private static final class SilentHandler implements CoreCallbackHandler {
        @Override
        public long startup() {
            return 0;
        }

        @Override
        public long shutdown() {
            return 0;
        }

        @Override
        public long onEmitStatus(long code, String msg) {
            return 0;
        }
    }
}
