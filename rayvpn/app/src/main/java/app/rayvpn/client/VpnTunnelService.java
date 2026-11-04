package app.rayvpn.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.util.ArrayList;
import java.util.List;

import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;

public class VpnTunnelService extends VpnService implements CoreCallbackHandler {
    public static final String ACTION_STOP = "app.rayvpn.client.STOP";
    public static final String ACTION_STATE = "app.rayvpn.client.STATE";
    public static final String EXTRA_PROFILE_ID = "profileId";

    public static final String STATE_IDLE = "idle";
    public static final String STATE_CONNECTING = "connecting";
    public static final String STATE_CONNECTED = "connected";
    public static final String STATE_ERROR = "error";

    public static volatile String state = STATE_IDLE;
    public static volatile String detail = "";
    public static volatile String activeId = "";
    public static volatile String traffic = "";
    public static final List<String> logs = new ArrayList<>();

    private static final String CHANNEL = "rayvpn";
    private static final int NOTIF = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread worker;
    private Handler workerHandler;
    private ParcelFileDescriptor tun;
    private CoreController core;
    private String activeName = "";
    private String tunError = "";
    private static volatile VpnTunnelService running;

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        createChannel();
        worker = new HandlerThread("rayvpn-core");
        worker.start();
        workerHandler = new Handler(worker.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF, notification("ری‌وی‌پی‌ان", "در حال آماده‌سازی تونل", false));
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            workerHandler.post(new Runnable() {
                @Override public void run() { stopTunnel("قطع شد"); }
            });
            return START_NOT_STICKY;
        }
        final String id = intent == null ? "" : intent.getStringExtra(EXTRA_PROFILE_ID);
        workerHandler.post(new Runnable() {
            @Override public void run() { startTunnel(id); }
        });
        return START_NOT_STICKY;
    }

    private void startTunnel(String id) {
        setState(STATE_CONNECTING, "در حال ساخت تونل", id);
        try {
            if (RayApp.coreError != null && !RayApp.coreError.isEmpty()) {
                throw new IllegalStateException("هسته بارگذاری نشد: " + RayApp.coreError);
            }
            ConfigStore store = new ConfigStore(this);
            store.load();
            Profile profile = store.byId(id);
            if (profile == null) profile = store.selected();
            if (profile == null) throw new IllegalStateException("کانفیگی برای اتصال نیست");
            activeName = profile.name == null || profile.name.isEmpty() ? profile.address : profile.name;
            activeId = profile.id;
            String config = XrayConfigBuilder.buildTunnel(profile);
            ParcelFileDescriptor pfd = openTun();
            if (pfd == null) {
                String why = tunError == null || tunError.isEmpty() ? "سیستم اجازهٔ VPN نداد" : tunError;
                throw new IllegalStateException(why);
            }
            tun = pfd;
            int fd = pfd.getFd();
            if (fd < 0) throw new IllegalStateException("descriptor تونل نامعتبر است");
            if (core == null) core = Libv2ray.newCoreController(this);
            if (core.getIsRunning()) core.stopLoop();
            core.startLoop(config, fd);
            setState(STATE_CONNECTED, "متصل به " + activeName, profile.id);
            updateNotification("متصل", activeName, true);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            log("خطا: " + msg);
            try {
                if (core != null && core.getIsRunning()) core.stopLoop();
            } catch (Exception ignored) {
            }
            closeTun();
            setState(STATE_ERROR, msg, "");
            stopForeground(true);
            stopSelf();
        }
    }

    private ParcelFileDescriptor openTun() {
        ParcelFileDescriptor first = establish(true);
        if (first != null) return first;
        return establish(false);
    }

    private ParcelFileDescriptor establish(boolean ipv6) {
        try {
            Builder b = new Builder();
            b.setSession("ری‌وی‌پی‌ان");
            b.setMtu(1500);
            b.addAddress("10.89.0.1", 32);
            b.addRoute("0.0.0.0", 0);
            b.addDnsServer("1.1.1.1");
            b.addDnsServer("8.8.8.8");
            if (ipv6) {
                b.addAddress("fd00:89::1", 128);
                b.addRoute("::", 0);
                b.addDnsServer("2606:4700:4700::1111");
            }
            b.addDisallowedApplication(getPackageName());
            if (Build.VERSION.SDK_INT >= 29) b.setMetered(false);
            Intent ui = new Intent(this, MainActivity.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            b.setConfigureIntent(PendingIntent.getActivity(this, 0, ui, flags));
            return b.establish();
        } catch (Exception e) {
            tunError = e.getMessage() == null ? e.toString() : e.getMessage();
            log("tun: " + tunError);
            return null;
        }
    }

    private void stopTunnel(String message) {
        try {
            if (core != null && core.getIsRunning()) core.stopLoop();
        } catch (Exception e) {
            log("stop: " + e.getMessage());
        }
        core = null;
        closeTun();
        setState(STATE_IDLE, message, "");
        traffic = "";
        stopForeground(true);
        stopSelf();
    }

    private void closeTun() {
        if (tun != null) {
            try { tun.close(); } catch (Exception ignored) {}
            tun = null;
        }
    }

    @Override
    public void onRevoke() {
        workerHandler.post(new Runnable() {
            @Override public void run() { stopTunnel("VPN توسط سیستم قطع شد"); }
        });
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        if (running == this) running = null;
        closeTun();
        if (worker != null) worker.quitSafely();
        if (!STATE_ERROR.equals(state)) {
            state = STATE_IDLE;
        }
        super.onDestroy();
    }

    @Override
    public long startup() {
        log("هسته بالا آمد");
        return 0;
    }

    @Override
    public long shutdown() {
        log("هسته خاموش شد");
        return 0;
    }

    @Override
    public long onEmitStatus(long code, String msg) {
        log(msg == null ? "" : msg);
        return 0;
    }

    public static void requestStop(android.content.Context context) {
        requestStop(context, "قطع شد");
    }

    public static void requestStop(android.content.Context context, final String message) {
        VpnTunnelService live = running;
        if (live != null && live.workerHandler != null) {
            live.workerHandler.post(new Runnable() {
                @Override public void run() { live.stopTunnel(message); }
            });
            return;
        }
        Intent i = new Intent(context, VpnTunnelService.class);
        i.setAction(ACTION_STOP);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
        else context.startService(i);
    }

    private void setState(String next, String message, String id) {
        state = next;
        detail = message == null ? "" : message;
        if (id != null) activeId = id;
        Intent broadcast = new Intent(ACTION_STATE);
        broadcast.setPackage(getPackageName());
        broadcast.putExtra("state", next);
        broadcast.putExtra("detail", detail);
        sendBroadcast(broadcast);
    }

    private void log(String line) {
        if (line == null || line.isEmpty()) return;
        synchronized (logs) {
            logs.add(line);
            while (logs.size() > 40) logs.remove(0);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "اتصال VPN", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("وضعیت تونل ری‌وی‌پی‌ان");
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification notification(String title, String text, boolean connected) {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(this, 1, open, flags);
        Intent stop = new Intent(this, VpnTunnelService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stop, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat_vpn)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(content)
                .addAction(new Notification.Action.Builder(0, "قطع", stopPi).build());
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    private void updateNotification(String title, String text, boolean connected) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF, notification(title, text, connected));
    }
}
