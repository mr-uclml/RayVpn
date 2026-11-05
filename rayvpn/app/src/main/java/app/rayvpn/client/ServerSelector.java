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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Picks the server for one of the three connection modes. Runs in the VPN
 * service (the "backend" side of the app); the UI only passes the mode.
 *
 * <p>Every mode uses the same two-stage pipeline:
 *
 * <ol>
 *   <li><b>Port scan</b> — a plain TCP handshake to {@code address:port}, a dozen at a
 *       time, 850 ms timeout. It costs nothing (no core, no traffic) and throws out every
 *       server that is down, blocked or too far away before the expensive work starts.
 *       Servers on UDP transports (hysteria, mKCP, "raw") cannot be probed this way, so
 *       they are kept and simply tried after the TCP-alive ones.</li>
 *   <li><b>Tunnel probe</b> — a real HTTP 204 through the config, which needs the Xray
 *       core and can only run one config at a time. Every mode therefore caps how many
 *       probes it runs and how long it may spend, and stops at the first answer that is
 *       good enough for that mode.</li>
 * </ol>
 *
 * <ul>
 *   <li>{@link Mode#FAST}: probes the cheapest TCP survivors in order and takes the first
 *       one that answers (at most {@value #FAST_BUDGET} probes).</li>
 *   <li>{@link Mode#BALANCE}: same order, but only accepts a tunnel delay of at most
 *       {@value #GOOD_DELAY_MS} ms so the choice is stable instead of merely the first
 *       server that happens to answer.</li>
 *   <li>{@link Mode#BEST}: walks the survivors in random order (as asked: sample, do not
 *       trust list order), keeps up to {@value #BEST_SHORTLIST} servers under
 *       {@value #GOOD_DELAY_MS} ms, then measures the real download speed of those and
 *       keeps the fastest.</li>
 * </ul>
 *
 * <p>{@link ServerCache} remembers what worked last time: a server that succeeded recently
 * is tried first (so reconnects are near instant), and one that just failed is pushed to the
 * back instead of being punished again a few seconds later.
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

    /** Progress of the selection, reported as numbers so the caller can localise it. */
    public interface Progress {
        /** generic "server i of n" (starting phase). */
        int TEST = 0;
        /** tunnel probe, "i of n". */
        int PING = 1;
        /** download test, "i of n". */
        int SPEED = 2;
        /** TCP port scan, "i of n". */
        int TCP = 3;

        void update(int step, int index, int total);
    }

    public interface Cancel {
        boolean isCancelled();
    }

    // ── tuning ─────────────────────────────────────────────────────────────
    /** A tunnel delay at or below this is "good enough" for balance and best. */
    static final long GOOD_DELAY_MS = 200L;
    /** TCP handshake timeout; also the point where a server counts as unusable. */
    static final int TCP_TIMEOUT_MS = 850;
    static final int TCP_TOO_SLOW_MS = 1500;
    static final int TCP_THREADS = 12;
    /** Tunnel probes allowed per mode before falling back to what we already have. */
    static final int FAST_BUDGET = 6;
    static final int BALANCE_BUDGET = 10;
    static final int BEST_BUDGET = 26;
    /** How many fast servers get a download test in BEST. */
    static final int BEST_SHORTLIST = 10;
    /** Wall-clock ceilings so a bad network can never hang the spinner forever. */
    static final long BALANCE_DEADLINE_MS = 25_000L;
    static final long BEST_DEADLINE_MS = 40_000L;
    /** Download size for the speed test; kept small to save the user's data. */
    static final int SPEED_BYTES = 256 * 1024;
    static final int SPEED_MIN_BYTES = 24 * 1024;
    static final int SPEED_TIMEOUT_MS = 5000;
    /** Above this the winner is obvious, so the remaining download tests are skipped. */
    static final double SPEED_GOOD_ENOUGH = 6.0 * 1024 * 1024;
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
        List<Profile> candidates = prepare(profiles);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("فهرست سرورها هنوز آماده نیست");
        }
        if (cancel.isCancelled()) return null;

        // stage 1: cheap TCP sweep, a dozen servers at a time
        List<Hit> alive = sweep(candidates, progress, cancel);
        if (cancel.isCancelled()) return null;
        if (alive == null || alive.isEmpty()) {
            throw new IllegalStateException("هیچ سروری باز نبود");
        }
        sortForTrying(alive);

        // stage 2 (and 3 for BEST)
        switch (mode) {
            case FAST:
                return fast(alive, progress, cancel);
            case BEST:
                return best(alive, progress, cancel);
            default:
                return balance(alive, progress, cancel);
        }
    }

    // ── candidates ─────────────────────────────────────────────────────────

    /**
     * Drops exact duplicates (same transport, same credentials, same everything except the
     * name) and orders what is left: recently working servers first, recently failing ones
     * last, everything else in list order.
     */
    static List<Profile> prepare(List<Profile> profiles) {
        Map<String, Profile> unique = new LinkedHashMap<>();
        if (profiles != null) {
            for (Profile p : profiles) {
                if (p == null || p.address == null) continue;
                String key = p.identity();
                if (!unique.containsKey(key)) unique.put(key, p);
            }
        }
        List<Profile> list = new ArrayList<>(unique.values());
        Collections.sort(list, new Comparator<Profile>() {
            @Override public int compare(Profile a, Profile b) {
                int ra = ServerCache.rank(a);
                int rb = ServerCache.rank(b);
                if (ra != rb) return ra < rb ? -1 : 1;
                return 0;                       // stable: keep the server list order
            }
        });
        return list;
    }

    // ── stage 1: TCP sweep ─────────────────────────────────────────────────

    private static final class Hit {
        final Profile profile;
        /** TCP round trip, or -1 when the transport is not TCP and cannot be probed. */
        final long tcpMs;

        Hit(Profile profile, long tcpMs) {
            this.profile = profile;
            this.tcpMs = tcpMs;
        }
    }

    private static List<Hit> sweep(final List<Profile> candidates, final Progress progress,
                                   final Cancel cancel) {
        final int total = candidates.size();
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(TCP_THREADS, Math.max(1, total)), new ThreadFactory() {
                    @Override public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "rayvpn-tcp");
                        t.setDaemon(true);
                        return t;
                    }
                });
        List<Hit> alive = new ArrayList<>();
        try {
            CompletionService<Hit> done = new ExecutorCompletionService<>(pool);
            for (final Profile p : candidates) {
                done.submit(new Callable<Hit>() {
                    @Override public Hit call() {
                        if (cancel.isCancelled()) return null;
                        if (!needsTcpGate(p)) return new Hit(p, -1L);
                        long ms = tcpPing(p.address, p.port, TCP_TIMEOUT_MS);
                        if (ms < 0 || ms > TCP_TOO_SLOW_MS) return null;
                        return new Hit(p, ms);
                    }
                });
            }
            int finished = 0;
            for (int i = 0; i < total; i++) {
                if (cancel.isCancelled()) return alive;
                Future<Hit> future = done.take();
                Hit hit;
                try {
                    hit = future.get();
                } catch (Exception e) {
                    hit = null;
                }
                finished++;
                progress.update(Progress.TCP, finished, total);
                if (hit != null) alive.add(hit);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        // the cheapest handshakes first; unprobeable UDP transports go last
        Collections.sort(alive, new Comparator<Hit>() {
            @Override public int compare(Hit a, Hit b) {
                long x = a.tcpMs < 0 ? Long.MAX_VALUE : a.tcpMs;
                long y = b.tcpMs < 0 ? Long.MAX_VALUE : b.tcpMs;
                return x == y ? 0 : (x < y ? -1 : 1);
            }
        });
        return alive;
    }

    /**
     * Known-good servers first (a reconnect then costs a single probe), then the rest by
     * handshake time, then the ones that just failed.
     */
    static void sortForTrying(List<Hit> alive) {
        Collections.sort(alive, new Comparator<Hit>() {
            @Override public int compare(Hit a, Hit b) {
                int ga = group(ServerCache.rank(a.profile));
                int gb = group(ServerCache.rank(b.profile));
                if (ga != gb) return ga < gb ? -1 : 1;
                long x = a.tcpMs < 0 ? Long.MAX_VALUE : a.tcpMs;
                long y = b.tcpMs < 0 ? Long.MAX_VALUE : b.tcpMs;
                return x == y ? 0 : (x < y ? -1 : 1);
            }
        });
    }

    private static int group(int rank) {
        return rank == 0 ? 0 : rank == 2 ? 2 : 1;
    }

    // ── modes ──────────────────────────────────────────────────────────────

    private static Profile fast(List<Hit> alive, Progress progress, Cancel cancel) {
        int tried = 0;
        int total = Math.min(alive.size(), FAST_BUDGET);
        for (Hit hit : alive) {
            if (cancel.isCancelled()) return null;
            if (tried >= FAST_BUDGET) break;
            tried++;
            progress.update(Progress.PING, tried, total);
            long ms = delayOnce(hit.profile);
            if (ms >= 0) {
                ServerCache.recordDelay(hit.profile, ms);
                return hit.profile;
            }
            ServerCache.recordFailure(hit.profile);
        }
        throw new IllegalStateException("هیچ سروری پاسخ نداد");
    }

    private static Profile balance(List<Hit> alive, Progress progress, Cancel cancel) {
        long deadline = System.currentTimeMillis() + BALANCE_DEADLINE_MS;
        Profile best = null;
        long bestMs = Long.MAX_VALUE;
        int tried = 0;
        int total = Math.min(alive.size(), BALANCE_BUDGET);
        for (Hit hit : alive) {
            if (cancel.isCancelled()) return null;
            if (tried >= BALANCE_BUDGET || System.currentTimeMillis() > deadline) break;
            tried++;
            progress.update(Progress.PING, tried, total);
            long first = delayOnce(hit.profile);
            if (first < 0) {
                ServerCache.recordFailure(hit.profile);
                continue;
            }
            if (first <= GOOD_DELAY_MS) {
                // one confirmation sample, so a lucky measurement does not decide the session
                long second = delayOnce(hit.profile);
                if (second < 0 || second <= GOOD_DELAY_MS * 3 / 2) {
                    ServerCache.recordDelay(hit.profile, second < 0 ? first : Math.min(first, second));
                    return hit.profile;
                }
            }
            if (first < bestMs) {
                bestMs = first;
                best = hit.profile;
            }
        }
        if (best != null) {
            ServerCache.recordDelay(best, bestMs);
            return best;
        }
        throw new IllegalStateException("هیچ سروری پاسخ نداد");
    }

    private static Profile best(List<Hit> alive, Progress progress, Cancel cancel) {
        List<Hit> pool = new ArrayList<>(alive);
        List<Profile> profiles = new ArrayList<>(pool.size());
        for (Hit hit : pool) profiles.add(hit.profile);
        if (ServerCache.hasSpeeds(profiles)) {
            // we already know how fast these were recently: start from the proven ones
            Collections.sort(pool, new Comparator<Hit>() {
                @Override public int compare(Hit a, Hit b) {
                    double x = ServerCache.speed(a.profile);
                    double y = ServerCache.speed(b.profile);
                    return x == y ? 0 : (x > y ? -1 : 1);
                }
            });
        } else {
            // sampled, not list order: everybody gets a fair chance
            Collections.shuffle(pool, RANDOM);
        }

        long deadline = System.currentTimeMillis() + BEST_DEADLINE_MS;
        List<Hit> shortlist = new ArrayList<>();
        Profile fallback = null;
        long fallbackMs = Long.MAX_VALUE;
        int tried = 0;
        int total = Math.min(pool.size(), BEST_BUDGET);
        for (Hit hit : pool) {
            if (cancel.isCancelled()) return null;
            if (shortlist.size() >= BEST_SHORTLIST || tried >= BEST_BUDGET
                    || System.currentTimeMillis() > deadline) {
                break;
            }
            tried++;
            progress.update(Progress.PING, tried, total);
            long ms = delayOnce(hit.profile);
            if (ms < 0) {
                ServerCache.recordFailure(hit.profile);
                continue;
            }
            ServerCache.recordDelay(hit.profile, ms);
            if (ms <= GOOD_DELAY_MS) {
                shortlist.add(hit);
            } else if (ms < fallbackMs) {
                fallbackMs = ms;
                fallback = hit.profile;
            }
        }

        if (shortlist.isEmpty()) {
            if (fallback != null) return fallback;
            throw new IllegalStateException("هیچ سروری پاسخ نداد");
        }

        // fastest ping first: if one of them turns out great we stop early
        Collections.sort(shortlist, new Comparator<Hit>() {
            @Override public int compare(Hit a, Hit b) {
                return Long.compare(a.tcpMs < 0 ? Long.MAX_VALUE : a.tcpMs,
                        b.tcpMs < 0 ? Long.MAX_VALUE : b.tcpMs);
            }
        });

        Profile fastest = null;
        double fastestBps = -1;
        int index = 0;
        for (Hit hit : shortlist) {
            if (cancel.isCancelled()) return null;
            index++;
            progress.update(Progress.SPEED, index, shortlist.size());
            double bps = speedOnce(hit.profile);
            if (bps > 0) {
                ServerCache.recordSpeed(hit.profile, bps);
                if (bps > fastestBps) {
                    fastestBps = bps;
                    fastest = hit.profile;
                }
                if (fastestBps >= SPEED_GOOD_ENOUGH) break;
            } else {
                ServerCache.recordFailure(hit.profile);
            }
        }
        if (fastest != null) return fastest;
        // every download failed; the lowest latency is still the best answer we have
        return shortlist.get(0).profile;
    }

    // ── measurements ───────────────────────────────────────────────────────

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
