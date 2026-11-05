package app.rayvpn.client;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Remembers how each server behaved the last time it was used.
 *
 * <p>Selection is much faster and much steadier when the app knows what worked a minute
 * ago instead of starting from scratch on every connect:
 *
 * <ul>
 *   <li>a server that answered within the last {@value #OK_TTL_MS} ms is tried before the
 *       rest, so reconnecting usually succeeds on the first probe;</li>
 *   <li>a server that just failed is pushed behind the untested ones instead of being
 *       retried immediately;</li>
 *   <li>the last measured download speed lets {@link ServerSelector.Mode#BEST} start from
 *       the servers that were actually fast, when that data is fresh enough.</li>
 * </ul>
 *
 * Entries are keyed by {@link Profile#identity()}, so a subscription refresh that renames
 * or reorders servers keeps its history. The table is capped and persisted in
 * SharedPreferences.
 */
final class ServerCache {

    private static final String PREFS = "rayvpn_cache";
    private static final String KEY = "entries";
    private static final int MAX_ENTRIES = 500;

    /** How long a success keeps its "try me first" bonus. */
    private static final long OK_TTL_MS = 6L * 60 * 60 * 1000;
    /** How long a failure keeps a server at the back of the queue. */
    private static final long FAIL_TTL_MS = 15L * 60 * 1000;
    /** How long a measured speed stays trustworthy for BEST (same window as a success). */
    private static final long SPEED_TTL_MS = 6L * 60 * 60 * 1000;
    private static final long SAVE_INTERVAL_MS = 1500L;

    private static final class Entry {
        long okAt;
        long delayMs = -1;
        double speedBps = -1;
        long failAt;
        long touchedAt;
    }

    private static final Map<String, Entry> ENTRIES = new HashMap<>();
    private static SharedPreferences prefs;
    private static boolean dirty;
    private static long lastSave;

    private ServerCache() {}

    static synchronized void init(Context context) {
        if (prefs != null || context == null) return;
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY, "");
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Entry e = new Entry();
                e.okAt = o.optLong("ok");
                e.delayMs = o.optLong("delay", -1);
                e.speedBps = o.optDouble("speed", -1);
                e.failAt = o.optLong("fail");
                e.touchedAt = o.optLong("at");
                ENTRIES.put(o.optString("k"), e);
            }
        } catch (Exception ignored) {
        }
    }

    // ── what the selector asks ─────────────────────────────────────────────

    /**
     * 0 = worked recently (try first), 1 = unknown or stale (normal), 2 = failed recently
     * (try last).
     */
    static synchronized int rank(Profile p) {
        Entry e = entry(p);
        if (e == null) return 1;
        long now = System.currentTimeMillis();
        // the most recent event wins: a server that just failed is not trusted again
        // because it happened to work two minutes earlier
        if (e.failAt > 0 && e.failAt >= e.okAt && now - e.failAt <= FAIL_TTL_MS) return 2;
        if (e.okAt > 0 && now - e.okAt <= OK_TTL_MS) return 0;
        return 1;
    }

    /** Cached tunnel delay in ms, or -1 when there is nothing recent. */
    static synchronized long delay(Profile p) {
        Entry e = entry(p);
        if (e == null || e.delayMs < 0) return -1;
        if (System.currentTimeMillis() - e.okAt > OK_TTL_MS) return -1;
        return e.delayMs;
    }

    /** Cached download speed in bytes/s, or -1 when there is nothing recent. */
    static synchronized double speed(Profile p) {
        Entry e = entry(p);
        if (e == null || e.speedBps <= 0) return -1;
        long now = System.currentTimeMillis();
        if (now - e.touchedAt > SPEED_TTL_MS) return -1;
        return e.speedBps;
    }

    /** True when any of these servers has a usable speed measurement. */
    static synchronized boolean hasSpeeds(List<Profile> profiles) {
        if (profiles == null) return false;
        for (Profile p : profiles) {
            if (speed(p) > 0) return true;
        }
        return false;
    }

    // ── what the selector reports ──────────────────────────────────────────

    static synchronized void recordDelay(Profile p, long ms) {
        if (p == null) return;
        Entry e = obtain(p);
        e.okAt = System.currentTimeMillis();
        e.delayMs = ms;
        touch(e);
    }

    static synchronized void recordSpeed(Profile p, double bps) {
        if (p == null || bps <= 0) return;
        Entry e = obtain(p);
        e.speedBps = bps;
        e.okAt = System.currentTimeMillis();
        touch(e);
    }

    static synchronized void recordFailure(Profile p) {
        if (p == null) return;
        Entry e = obtain(p);
        e.failAt = System.currentTimeMillis();
        touch(e);
    }

    /** Writes pending measurements out; cheap to call after a selection finished. */
    static synchronized void flush() {
        if (dirty) save();
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    private static Entry entry(Profile p) {
        if (p == null) return null;
        return ENTRIES.get(p.identity());
    }

    private static Entry obtain(Profile p) {
        String key = p.identity();
        Entry e = ENTRIES.get(key);
        if (e == null) {
            e = new Entry();
            ENTRIES.put(key, e);
        }
        return e;
    }

    private static void touch(Entry e) {
        e.touchedAt = System.currentTimeMillis();
        dirty = true;
        long now = System.currentTimeMillis();
        if (now - lastSave >= SAVE_INTERVAL_MS) save();
        else trim();
    }

    private static void trim() {
        if (ENTRIES.size() <= MAX_ENTRIES) return;
        List<Map.Entry<String, Entry>> all = new ArrayList<>(ENTRIES.entrySet());
        java.util.Collections.sort(all, new java.util.Comparator<Map.Entry<String, Entry>>() {
            @Override public int compare(Map.Entry<String, Entry> a, Map.Entry<String, Entry> b) {
                return Long.compare(b.getValue().touchedAt, a.getValue().touchedAt);
            }
        });
        Iterator<Map.Entry<String, Entry>> it = all.iterator();
        int kept = 0;
        while (it.hasNext()) {
            Map.Entry<String, Entry> next = it.next();
            if (kept++ < MAX_ENTRIES) continue;
            ENTRIES.remove(next.getKey());
        }
    }

    private static void save() {
        lastSave = System.currentTimeMillis();
        dirty = false;
        trim();
        if (prefs == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (Map.Entry<String, Entry> pair : ENTRIES.entrySet()) {
                Entry e = pair.getValue();
                JSONObject o = new JSONObject();
                o.put("k", pair.getKey());
                o.put("ok", e.okAt);
                o.put("delay", e.delayMs);
                o.put("speed", e.speedBps);
                o.put("fail", e.failAt);
                o.put("at", e.touchedAt);
                arr.put(o);
            }
            prefs.edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }
}
