package app.rayvpn.client;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class OrderTest {
    static int failures = 0;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    static Profile mk(String host) {
        Profile p = new Profile();
        p.address = host;
        p.port = 443;
        p.protocol = "vless";
        p.user = "u";
        p.name = host;
        return p;
    }

    @SuppressWarnings("unchecked")
    static void cache(Profile p, Long okAt, Long failAt) throws Exception {
        Field f = ServerCache.class.getDeclaredField("ENTRIES");
        f.setAccessible(true);
        Map<String, Object> map = (Map<String, Object>) f.get(null);
        Class<?> ec = Class.forName("app.rayvpn.client.ServerCache$Entry");
        Constructor<?> ctor = ec.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object e = ctor.newInstance();
        long now = System.currentTimeMillis();
        set(e, "okAt", okAt == null ? 0L : now - okAt);
        set(e, "failAt", failAt == null ? 0L : now - failAt);
        set(e, "touchedAt", now);
        map.put(p.identity(), e);
    }

    static void set(Object entry, String name, long value) throws Exception {
        Field f = entry.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.setLong(entry, value);
    }

    static Object hit(Constructor<?> ctor, Profile p, long tcpMs) throws Exception {
        return ctor.newInstance(p, tcpMs);
    }

    public static void main(String[] args) throws Exception {
        Profile failedServer = mk("failed.example");      // failed 1 minute ago
        Profile knownGood = mk("good.example");           // worked 30 s ago
        Profile middle = mk("middle.example");
        Profile closest = mk("closest.example");

        cache(failedServer, 3600_000L, 60_000L);
        cache(knownGood, 30_000L, null);

        Class<?> hitClass = Class.forName("app.rayvpn.client.ServerSelector$Hit");
        Constructor<?> ctor = hitClass.getDeclaredConstructor(Profile.class, long.class);
        ctor.setAccessible(true);
        List<Object> list = new ArrayList<>();
        list.add(hit(ctor, failedServer, 10));    // fastest handshake, but just failed
        list.add(hit(ctor, knownGood, 400));      // worked a moment ago
        list.add(hit(ctor, middle, 50));
        list.add(hit(ctor, closest, 5));

        Method sort = ServerSelector.class.getDeclaredMethod("sortForTrying", List.class);
        sort.setAccessible(true);
        sort.invoke(null, list);

        Field profileField = hitClass.getDeclaredField("profile");
        profileField.setAccessible(true);
        StringBuilder order = new StringBuilder();
        for (Object h : list) {
            order.append(((Profile) profileField.get(h)).address).append(' ');
        }
        System.out.println("order: " + order.toString().trim());
        check("the server that just worked is tried first", profileField.get(list.get(0)) == knownGood);
        check("then the closest untested ones", profileField.get(list.get(1)) == closest
                && profileField.get(list.get(2)) == middle);
        check("the server that just failed goes last", profileField.get(list.get(3)) == failedServer);

        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
