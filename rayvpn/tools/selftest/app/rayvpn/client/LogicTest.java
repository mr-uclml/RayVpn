package app.rayvpn.client;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LogicTest {
    static int failures = 0;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    static Profile mk(String host, int port, String proto, String uuid, String name) {
        Profile p = new Profile();
        p.address = host;
        p.port = port;
        p.protocol = proto;
        p.user = uuid;
        p.name = name;
        p.network = "tcp";
        p.security = "tls";
        p.sni = host;
        return p;
    }

    public static void main(String[] args) throws Exception {
        Profile a1 = mk("a.example.com", 443, "vless", "uuid-1", "Server A");
        Profile a2 = mk("A.EXAMPLE.com", 443, "vless", "uuid-1", "Server A (copy)");
        Profile b  = mk("b.example.com", 443, "vless", "uuid-2", "Server B");
        Profile c  = mk("b.example.com", 8443, "vless", "uuid-2", "Server B alt port");
        Profile d  = mk("b.example.com", 443, "vmess", "uuid-2", "Server B vmess");

        System.out.println("identity");
        check("name is ignored", a1.identity().equals(a2.identity()));
        check("same host, different port differs", !b.identity().equals(c.identity()));
        check("same host, different protocol differs", !b.identity().equals(d.identity()));
        check("different credentials differ",
                !mk("x.com", 443, "vless", "u1", "n").identity().equals(mk("x.com", 443, "vless", "u2", "n").identity()));

        System.out.println("prepare()");
        List<Profile> in = Arrays.asList(a1, a2, b, c, d);
        List<Profile> out = ServerSelector.prepare(in);
        check("duplicates collapse: 5 -> 4 (got " + out.size() + ")", out.size() == 4);
        check("first occurrence wins", out.contains(a1) && !out.contains(a2));
        check("input list untouched", in.size() == 5);

        System.out.println("needsTcpGate()");
        Profile hy = mk("h.example.com", 443, "hysteria", "x", "h");
        Profile kcp = mk("k.example.com", 443, "vless", "x", "k");
        kcp.network = "kcp";
        check("vless/tcp is probed", ServerSelector.needsTcpGate(b));
        check("hysteria is not probed", !ServerSelector.needsTcpGate(hy));
        check("mKCP is not probed", !ServerSelector.needsTcpGate(kcp));

        System.out.println("tcpPing()");
        ServerSocket listener = new ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"));
        int open = listener.getLocalPort();
        long ms = ServerSelector.tcpPing("127.0.0.1", open, 850);
        check("open port answers (" + ms + " ms)", ms > 0 && ms < 850);
        long start = System.nanoTime();
        long dead = ServerSelector.tcpPing("127.0.0.1", 1, 300);
        long spent = (System.nanoTime() - start) / 1000000L;
        check("closed port gives -1", dead == -1);
        check("refused port does not burn the timeout (" + spent + " ms)", spent < 300);
        listener.close();

        System.out.println("ServerCache defaults (no prefs yet)");
        check("unknown server ranks normal", ServerCache.rank(b) == 1);
        check("no cached delay", ServerCache.delay(b) == -1);
        check("no cached speed", ServerCache.speed(b) == -1);
        List<Profile> two = new ArrayList<>(Arrays.asList(b, c));
        check("hasSpeeds false", !ServerCache.hasSpeeds(two));

        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
