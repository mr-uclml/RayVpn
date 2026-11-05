package app.rayvpn.client;

import java.lang.reflect.Field;
import java.util.Map;

public class CacheTest {
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
    static Object entryFor(Profile p) throws Exception {
        Field f = ServerCache.class.getDeclaredField("ENTRIES");
        f.setAccessible(true);
        Map<String, Object> map = (Map<String, Object>) f.get(null);
        Object e = map.get(p.identity());
        if (e == null) {
            Class<?> entryClass = Class.forName("app.rayvpn.client.ServerCache$Entry");
            java.lang.reflect.Constructor<?> ctor = entryClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            e = ctor.newInstance();
            map.put(p.identity(), e);
        }
        return e;
    }

    static void set(Object entry, String field, Object value) throws Exception {
        Field f = entry.getClass().getDeclaredField(field);
        f.setAccessible(true);
        if (value instanceof Long) f.setLong(entry, (Long) value);
        else if (value instanceof Double) f.setDouble(entry, (Double) value);
    }

    public static void main(String[] args) throws Exception {
        long now = System.currentTimeMillis();
        long hour = 60 * 60 * 1000L;
        long minute = 60 * 1000L;

        Profile fresh = mk("fresh.example");
        Object e = entryFor(fresh);
        set(e, "okAt", now);
        set(e, "touchedAt", now);
        set(e, "delayMs", 120L);
        set(e, "speedBps", 5_000_000.0);
        check("recent success ranks first", ServerCache.rank(fresh) == 0);
        check("recent delay is reused", ServerCache.delay(fresh) == 120L);
        check("recent speed is reused", ServerCache.speed(fresh) > 4_000_000.0);

        Profile stale = mk("stale.example");
        Object s = entryFor(stale);
        set(s, "okAt", now - 7 * hour);
        set(s, "touchedAt", now - 7 * hour);
        set(s, "delayMs", 90L);
        set(s, "speedBps", 9_000_000.0);
        check("success older than the window loses its bonus", ServerCache.rank(stale) == 1);
        check("stale delay is ignored", ServerCache.delay(stale) == -1L);
        check("stale speed is ignored", ServerCache.speed(stale) == -1.0);

        Profile broken = mk("broken.example");
        Object b = entryFor(broken);
        set(b, "okAt", now - 2 * minute);
        set(b, "failAt", now);
        set(b, "touchedAt", now);
        check("recent failure goes last", ServerCache.rank(broken) == 2);

        Profile oldFail = mk("oldfail.example");
        Object o = entryFor(oldFail);
        set(o, "failAt", now - 20 * minute);
        set(o, "touchedAt", now - 20 * minute);
        check("old failure is forgotten", ServerCache.rank(oldFail) == 1);

        Profile recovered = mk("recovered.example");
        Object r = entryFor(recovered);
        set(r, "failAt", now - 30 * minute);
        set(r, "okAt", now - 5 * minute);
        set(r, "touchedAt", now - 5 * minute);
        check("a success after a failure wins", ServerCache.rank(recovered) == 0);

        unknown = mk("unknown.example");
        check("unknown server ranks normal", ServerCache.rank(unknown) == 1);

        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static Profile unknown;
}
