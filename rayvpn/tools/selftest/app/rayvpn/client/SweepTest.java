package app.rayvpn.client;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class SweepTest {
    static int failures = 0;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    static Profile mk(String host, int port, String proto) {
        Profile p = new Profile();
        p.address = host;
        p.port = port;
        p.protocol = proto;
        p.name = host + ":" + port;
        return p;
    }

    public static void main(String[] args) throws Exception {
        List<ServerSocket> listeners = new ArrayList<>();
        int[] open = new int[3];
        for (int i = 0; i < 3; i++) {
            ServerSocket s = new ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"));
            listeners.add(s);
            open[i] = s.getLocalPort();
        }

        List<Profile> candidates = new ArrayList<>();
        candidates.add(mk("127.0.0.1", open[0], "vless"));
        candidates.add(mk("127.0.0.1", 1, "vless"));                 // refused
        candidates.add(mk("127.0.0.1", open[1], "vmess"));
        candidates.add(mk("127.0.0.1", 2, "trojan"));                // refused
        Profile udp = mk("127.0.0.1", 5555, "hysteria");             // not probeable
        candidates.add(udp);
        candidates.add(mk("127.0.0.1", open[2], "vless"));

        Method sweep = ServerSelector.class.getDeclaredMethod("sweep", List.class,
                ServerSelector.Progress.class, ServerSelector.Cancel.class);
        sweep.setAccessible(true);
        final AtomicInteger lastTotal = new AtomicInteger();
        final AtomicInteger updates = new AtomicInteger();
        final List<Integer> seen = new ArrayList<>();
        ServerSelector.Progress progress = new ServerSelector.Progress() {
            @Override public void update(int step, int index, int total) {
                updates.incrementAndGet();
                lastTotal.set(total);
                seen.add(index);
            }
        };
        ServerSelector.Cancel never = new ServerSelector.Cancel() {
            @Override public boolean isCancelled() { return false; }
        };

        long start = System.currentTimeMillis();
        @SuppressWarnings("unchecked")
        List<Object> alive = (List<Object>) sweep.invoke(null, candidates, progress, never);
        long spent = System.currentTimeMillis() - start;

        System.out.println("sweep()");
        check("all 6 candidates reported in progress (" + updates.get() + ")", updates.get() == 6);
        check("total is the candidate count (" + lastTotal.get() + ")", lastTotal.get() == 6);
        check("3 dead servers dropped, 4 kept (got " + alive.size() + ")", alive.size() == 4);
        check("finished quickly (" + spent + " ms)", spent < 3000);

        // the three TCP survivors come first, ordered by handshake time, the UDP one last
        java.lang.reflect.Field tcpMs = alive.get(0).getClass().getDeclaredField("tcpMs");
        java.lang.reflect.Field profileField = alive.get(0).getClass().getDeclaredField("profile");
        tcpMs.setAccessible(true);
        profileField.setAccessible(true);
        long previous = -1;
        boolean sorted = true;
        boolean udpLast = true;
        for (int i = 0; i < alive.size(); i++) {
            long ms = tcpMs.getLong(alive.get(i));
            Profile p = (Profile) profileField.get(alive.get(i));
            if (i == alive.size() - 1 && p == udp) udpLast = ms == -1;
            if (ms >= 0) {
                if (previous >= 0 && ms < previous) sorted = false;
                previous = ms;
            }
        }
        check("TCP survivors sorted by handshake time", sorted);
        check("UDP transport kept and placed last", udpLast);

        // cancellation stops the sweep early
        ServerSelector.Cancel cancelNow = new ServerSelector.Cancel() {
            @Override public boolean isCancelled() { return true; }
        };
        @SuppressWarnings("unchecked")
        List<Object> cancelled = (List<Object>) sweep.invoke(null, candidates, progress, cancelNow);
        check("cancel returns an empty result (" + cancelled.size() + ")", cancelled.isEmpty());

        for (ServerSocket s : listeners) s.close();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
