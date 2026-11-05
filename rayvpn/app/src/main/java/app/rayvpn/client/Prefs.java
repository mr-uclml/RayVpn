package app.rayvpn.client;

import android.content.Context;
import android.content.SharedPreferences;

/** Everything the UI remembers: the chosen mode, the appearance, the counters. */
final class Prefs {

    private static final String FILE = "rayvpn_ui";
    private static final String KEY_MODE = "mode";
    private static final String KEY_THEME = "theme";
    private static final String KEY_LOCALE = "locale";
    private static final String KEY_NOTIFY = "notify";
    private static final String KEY_TOTAL_DOWN = "total_down";
    private static final String KEY_TOTAL_UP = "total_up";
    private static final String KEY_SESSIONS = "sessions";
    private static final String KEY_BEST_PING = "best_ping";

    private Prefs() {
    }

    static SharedPreferences of(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ── appearance ──────────────────────────────────────────────────────────

    static ServerSelector.Mode mode(Context c) {
        return ServerSelector.Mode.fromKey(of(c).getString(KEY_MODE, "balance"));
    }

    static void mode(Context c, ServerSelector.Mode mode) {
        of(c).edit().putString(KEY_MODE, mode.key).apply();
    }

    static boolean light(Context c) {
        return "light".equals(of(c).getString(KEY_THEME, "dark"));
    }

    static void light(Context c, boolean value) {
        of(c).edit().putString(KEY_THEME, value ? "light" : "dark").apply();
    }

    static String locale(Context c) {
        return of(c).getString(KEY_LOCALE, "fa");
    }

    static void locale(Context c, String value) {
        of(c).edit().putString(KEY_LOCALE, value).apply();
    }

    static boolean notifications(Context c) {
        return of(c).getBoolean(KEY_NOTIFY, true);
    }

    static void notifications(Context c, boolean value) {
        of(c).edit().putBoolean(KEY_NOTIFY, value).apply();
    }

    // ── counters ────────────────────────────────────────────────────────────

    static long totalDown(Context c) {
        return of(c).getLong(KEY_TOTAL_DOWN, 0L);
    }

    static long totalUp(Context c) {
        return of(c).getLong(KEY_TOTAL_UP, 0L);
    }

    static int sessions(Context c) {
        return of(c).getInt(KEY_SESSIONS, 0);
    }

    static int bestPing(Context c) {
        return of(c).getInt(KEY_BEST_PING, -1);
    }

    static void addTotals(Context c, long down, long up) {
        if (down <= 0 && up <= 0) return;
        SharedPreferences p = of(c);
        p.edit()
                .putLong(KEY_TOTAL_DOWN, p.getLong(KEY_TOTAL_DOWN, 0L) + Math.max(0, down))
                .putLong(KEY_TOTAL_UP, p.getLong(KEY_TOTAL_UP, 0L) + Math.max(0, up))
                .apply();
    }

    static void addSession(Context c) {
        SharedPreferences p = of(c);
        p.edit().putInt(KEY_SESSIONS, p.getInt(KEY_SESSIONS, 0) + 1).apply();
    }

    static void bestPing(Context c, long ms) {
        if (ms <= 0) return;
        SharedPreferences p = of(c);
        int current = p.getInt(KEY_BEST_PING, -1);
        if (current < 0 || ms < current) {
            p.edit().putInt(KEY_BEST_PING, (int) ms).apply();
        }
    }

    static void resetCounters(Context c) {
        of(c).edit()
                .putLong(KEY_TOTAL_DOWN, 0L)
                .putLong(KEY_TOTAL_UP, 0L)
                .putInt(KEY_SESSIONS, 0)
                .putInt(KEY_BEST_PING, -1)
                .apply();
    }
}
