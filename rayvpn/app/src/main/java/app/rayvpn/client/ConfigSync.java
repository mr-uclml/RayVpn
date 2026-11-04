package app.rayvpn.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConfigSync {
    public static final String ACTION_UPDATED = "app.rayvpn.client.CONFIGS_UPDATED";
    public static final String CONFIG_URL = "https://raw.githubusercontent.com/mr-uclml/RayVpn/refs/heads/main/configs.txt";
    private static final String FILE_COMMIT_URL = "https://github.com/mr-uclml/RayVpn/latest-commit/main/configs.txt";
    private static final String REFS_URL = "https://github.com/mr-uclml/RayVpn.git/info/refs?service=git-upload-pack";
    private static final String RAW_BY_COMMIT = "https://raw.githubusercontent.com/mr-uclml/RayVpn/";
    private static final long INTERVAL_MS = 15000L;
    private static final String CHANNEL = "rayvpn_updates";
    private static final int NOTICE = 8;
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final Pattern MAIN_REF = Pattern.compile("([0-9a-f]{40}) refs/heads/main");

    public static volatile boolean updateAvailable;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Context app;
    private static boolean started;
    private static boolean inFlight;
    private static boolean pending;
    private static boolean pendingManual;
    private static String lastHash = "";
    private static String appliedCommit = "";
    private static String seenCommit = "";
    private static String watchEtag = "";

    private ConfigSync() {}

    public static void start(Context context) {
        app = context.getApplicationContext();
        loadMarkers();
        if (started) return;
        started = true;
        handler.post(tick);
    }

    public static void remind() {
        if (updateAvailable) showNotice();
    }

    public static void syncNow(boolean manual) {
        if (app == null) return;
        pending = true;
        pendingManual = pendingManual || manual;
        if (!inFlight) handler.post(tick);
    }

    private static final Runnable tick = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(tick);
            if (inFlight) return;
            inFlight = true;
            final boolean manual = pendingManual;
            pending = false;
            pendingManual = false;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final Outcome outcome = pull(manual);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            inFlight = false;
                            publish(outcome);
                            if (pending) handler.post(tick);
                            else handler.postDelayed(tick, INTERVAL_MS);
                        }
                    });
                }
            }).start();
        }
    };

    private static Outcome pull(boolean manual) {
        Outcome outcome = new Outcome();
        outcome.manual = manual;
        ConfigStore store = new ConfigStore(app);
        store.load();
        boolean apply = manual || store.profiles.isEmpty();
        if (manual) watchEtag = "";
        String commit = fileCommit();
        if (commit.isEmpty()) commit = headCommit();
        if (!apply) {
            if (!commit.isEmpty() && commit.equals(appliedCommit)) return outcome;
            if (!commit.isEmpty() && updateAvailable && commit.equals(seenCommit)) return outcome;
            if (commit.isEmpty() && !appliedCommit.isEmpty()) return outcome;
            Fetched fetched = download(commit);
            if (fetched.error != null) return outcome;
            if (sameAsStored(store, fetched.body)) {
                remember(fetched.hash, commit.isEmpty() ? appliedCommit : commit);
                updateAvailable = false;
                return outcome;
            }
            seenCommit = commit;
            outcome.available = !updateAvailable;
            updateAvailable = true;
            outcome.commit = commit;
            return outcome;
        }
        Fetched fetched = download(commit);
        if (fetched.error != null) {
            outcome.error = fetched.error;
            return outcome;
        }
        if (fetched.hash.equals(lastHash) && !store.profiles.isEmpty()) {
            remember(fetched.hash, commit.isEmpty() ? appliedCommit : commit);
            updateAvailable = false;
            cancelNotice();
            return outcome;
        }
        outcome.commit = commit;
        return applyBody(outcome, fetched.body, fetched.hash);
    }

    private static Fetched download(String commit) {
        Fetched fetched = new Fetched();
        NetUtil.Result result = null;
        if (commit != null && !commit.isEmpty()) {
            result = NetUtil.getNoCache(RAW_BY_COMMIT + commit + "/configs.txt");
            if (result.error == null) fetched.fromCommit = true;
        }
        if (result == null || result.error != null) result = NetUtil.getFresh(CONFIG_URL);
        if (result.error != null) {
            fetched.error = result.error;
            return fetched;
        }
        String body = result.body == null ? "" : result.body.replace("\r\n", "\n").replace("\r", "\n").trim();
        String low = body.toLowerCase(java.util.Locale.US);
        if (low.contains("<html") || low.contains("<!doctype")) {
            fetched.error = "فایل کانفیگ خوانده نشد";
            return fetched;
        }
        fetched.body = body;
        fetched.hash = sha(body);
        return fetched;
    }

    private static boolean sameAsStored(ConfigStore store, String body) {
        List<Profile> parsed;
        if (body == null || body.isEmpty()) parsed = new ArrayList<>();
        else {
            try {
                parsed = ShareLinkParser.parseSubscription(body, CONFIG_URL);
            } catch (Exception e) {
                return false;
            }
        }
        if (store.profiles.size() != parsed.size()) return false;
        for (int i = 0; i < parsed.size(); i++) {
            String have = store.profiles.get(i).sourceLink == null ? "" : store.profiles.get(i).sourceLink;
            String next = parsed.get(i).sourceLink == null ? "" : parsed.get(i).sourceLink;
            if (!have.equals(next)) return false;
        }
        return true;
    }

    private static Outcome applyBody(Outcome outcome, String body, String hash) {
        List<Profile> parsed = new ArrayList<>();
        if (body != null && !body.isEmpty()) {
            try {
                parsed = ShareLinkParser.parseSubscription(body, CONFIG_URL);
            } catch (Exception e) {
                outcome.error = e.getMessage() == null ? "لیست خوانده نشد" : e.getMessage();
                return outcome;
            }
        }
        ConfigStore store = new ConfigStore(app);
        store.load();
        String activeLink = "";
        Profile active = store.byId(VpnTunnelService.activeId);
        if (active != null) activeLink = active.sourceLink == null ? "" : active.sourceLink;
        store.replaceRemote(parsed);
        boolean stillThere = activeLink.isEmpty();
        if (!activeLink.isEmpty()) {
            for (Profile p : parsed) {
                if (activeLink.equals(p.sourceLink)) stillThere = true;
            }
        }
        outcome.removedActive = !stillThere
                && (VpnTunnelService.STATE_CONNECTED.equals(VpnTunnelService.state)
                || VpnTunnelService.STATE_CONNECTING.equals(VpnTunnelService.state));
        remember(hash, outcome.commit == null ? "" : outcome.commit);
        updateAvailable = false;
        outcome.changed = true;
        outcome.count = parsed.size();
        return outcome;
    }

    private static String fileCommit() {
        NetUtil.Result result = NetUtil.watch(FILE_COMMIT_URL, watchEtag, "application/json", BROWSER_UA);
        if (result.code == 429 || result.code == 403) return "";
        if (result.code == 304) return appliedCommit;
        if (result.etag != null && !result.etag.isEmpty()) watchEtag = result.etag;
        if (result.error != null || result.body == null || !result.body.trim().startsWith("{")) return "";
        try {
            String oid = new JSONObject(result.body).optString("oid", "");
            if (oid.length() == 40) return oid;
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String headCommit() {
        NetUtil.Result result = NetUtil.watch(REFS_URL, "", "*/*", "git/2.43.0");
        if (result.error != null || result.body == null) return "";
        Matcher matcher = MAIN_REF.matcher(result.body);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static void publish(Outcome outcome) {
        if (app == null) return;
        if (outcome.removedActive) {
            VpnTunnelService.requestStop(app, "این سرور از لیست حذف شد");
        }
        if (outcome.changed) cancelNotice();
        else if (outcome.available) showNotice();
        if (!outcome.changed && !outcome.available && outcome.error == null && !outcome.manual) return;
        Intent intent = new Intent(ACTION_UPDATED);
        intent.setPackage(app.getPackageName());
        intent.putExtra("changed", outcome.changed);
        intent.putExtra("available", outcome.available);
        intent.putExtra("manual", outcome.manual);
        intent.putExtra("count", outcome.count);
        intent.putExtra("error", outcome.error == null ? "" : outcome.error);
        intent.putExtra("removedActive", outcome.removedActive);
        app.sendBroadcast(intent);
    }

    private static void showNotice() {
        if (Build.VERSION.SDK_INT >= 33
                && app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "آپدیت لیست", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("خبر آماده‌بودن لیست جدید");
            nm.createNotificationChannel(channel);
        }
        Intent open = new Intent(app, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(app, 8, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(app, CHANNEL)
                : new Notification.Builder(app);
        builder.setSmallIcon(R.drawable.ic_stat_vpn)
                .setContentTitle("آپدیت لیست آمد")
                .setContentText("می‌توانی لیست سرورها را به‌روزرسانی کنی")
                .setAutoCancel(true)
                .setContentIntent(tap);
        nm.notify(NOTICE, builder.build());
    }

    private static void cancelNotice() {
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTICE);
    }

    private static void remember(String hash, String commit) {
        if (hash != null && !hash.isEmpty()) lastHash = hash;
        if (commit != null && !commit.isEmpty()) appliedCommit = commit;
        saveMarkers();
    }

    private static void loadMarkers() {
        lastHash = readSmall(new File(app.getFilesDir(), "configs.hash"));
        appliedCommit = readSmall(new File(app.getFilesDir(), "configs.commit"));
    }

    private static void saveMarkers() {
        writeSmall(new File(app.getFilesDir(), "configs.hash"), lastHash);
        writeSmall(new File(app.getFilesDir(), "configs.commit"), appliedCommit);
    }

    private static String sha(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte v : dig) b.append(String.format("%02x", v));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private static String readSmall(File file) {
        if (!file.exists()) return "";
        try {
            FileInputStream in = new FileInputStream(file);
            byte[] buf = new byte[(int) Math.max(0, file.length())];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            in.close();
            return new String(buf, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeSmall(File file, String text) {
        try {
            FileOutputStream out = new FileOutputStream(file);
            out.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception ignored) {
        }
    }

    private static final class Fetched {
        String body = "";
        String hash = "";
        String error;
        boolean fromCommit;
    }

    private static final class Outcome {
        boolean changed;
        boolean available;
        boolean manual;
        boolean removedActive;
        int count;
        String error;
        String commit = "";
    }
}
