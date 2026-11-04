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
import java.util.concurrent.atomic.AtomicInteger;

import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;

/**
 * The VPN "backend": picks a server for the requested mode (fast / balance / best),
 * brings the tunnel up and then looks up the exit IP and country. The UI only sends
 * the mode and shows the three states it gets back.
 */
public class VpnTunnelService extends VpnService implements CoreCallbackHandler {
    public static final String ACTION_STOP = "app.rayvpn.client.STOP";
    public static final String ACTION_STATE = "app.rayvpn.client.STATE";
    public static final String EXTRA_MODE = "mode";

    public static final String STATE_IDLE = "idle";
    public static final String STATE_CONNECTING = "connecting";
    public static final String STATE_CONNECTED = "connected";
    public static final String STATE_ERROR = "error";

    public static volatile String state = STATE_IDLE;
    public static volatile String detail = "";
    public static volatile String activeId = "";
    public static volatile String traffic = "";

    /** Exit IP and country of the running tunnel (filled after connecting). */
    public static volatile String exitIp = "";
    public static volatile String exitCountry = "";
    public static volatile String exitCode = "";
    public static volatile boolean exitFailed;

    public static final List<String> logs = new ArrayList<>();

    private static final String CHANNEL = "rayvpn";
    private static final int NOTIF = 1;
    private static final int EXIT_ATTEMPTS = 4;
    private static final long EXIT_RETRY_MS = 2000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread worker;
    private Handler workerHandler;
    private ParcelFileDescriptor tun;
    private CoreController core;
    private String tunError = "";
    private static volatile VpnTunnelService running;

    /** Bumped on every start and stop; background work from an older value is dropped. */
    private final AtomicInteger session = new AtomicInteger();
    private volatile int apiPort;
    private volatile String apiUser = "";
    private volatile String apiPass = "";

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
        String modeKey = intent == null ? "" : intent.getStringExtra(EXTRA_MODE);
        beginSelection(ServerSelector.Mode.fromKey(modeKey));
        return START_NOT_STICKY;
    }

    // ── selection (runs off the core thread so "stop" is always responsive) ──

    private void beginSelection(final ServerSelector.Mode mode) {
        final int mine = session.incrementAndGet();
        exitIp = "";
        exitCountry = "";
        exitCode = "";
        exitFailed = false;
        setState(STATE_CONNECTING, "در حال انتخاب سرور…", null);
        new Thread(new Runnable() {
            @Override public void run() { selectAndConnect(mode, mine); }
        }, "rayvpn-select").start();
    }

    private void selectAndConnect(ServerSelector.Mode mode, final int mine) {
        final Profile chosen;
        try {
            ConfigStore store = new ConfigStore(this);
            store.load();
            List<Profile> profiles = new ArrayList<>(store.profiles);
            chosen = ServerSelector.select(mode, profiles,
                    new ServerSelector.Progress() {
                        @Override public void update(String text) {
                            if (session.get() == mine) setState(STATE_CONNECTING, text, null);
                        }
                    },
                    new ServerSelector.Cancel() {
                        @Override public boolean isCancelled() {
                            return session.get() != mine;
                        }
                    });
        } catch (Throwable e) {
            String msg = e.getMessage() == null ? "انتخاب سرور انجام نشد" : e.getMessage();
            failSession(mine, msg);
            return;
        }
        if (chosen == null) return; // cancelled by the user
        workerHandler.post(new Runnable() {
            @Override public void run() { startTunnel(mine, chosen); }
        });
    }

    private void failSession(final int mine, final String message) {
        if (session.get() != mine) return;
        workerHandler.post(new Runnable() {
            @Override public void run() {
                if (session.get() != mine) return;
                session.incrementAndGet();
                log("خطا: " + message);
                setState(STATE_ERROR, message, "");
                stopForeground(true);
                stopSelf();
            }
        });
    }

    // ── tunnel ─────────────────────────────────────────────────────────────

    private void startTunnel(int mine, Profile profile) {
        if (session.get() != mine) return;
        setState(STATE_CONNECTING, "در حال ساخت تونل", profile.id);
        try {
            if (RayApp.coreError != null && !RayApp.coreError.isEmpty()) {
                throw new IllegalStateException("هسته بارگذاری نشد: " + RayApp.coreError);
            }
            apiPort = ServerSelector.freePort();
            if (apiPort < 0) throw new IllegalStateException("پورت آزاد پیدا نشد");
            apiUser = ServerSelector.token();
            apiPass = ServerSelector.token();
            XrayConfigBuilder.LocalSocks socks = new XrayConfigBuilder.LocalSocks(apiPort, apiUser, apiPass);
            String config = XrayConfigBuilder.buildTunnel(profile, socks);
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
            setState(STATE_CONNECTED, "در حال دریافت IP…", profile.id);
            updateNotification("متصل", "در حال دریافت IP…", true);
            startExitLookup(mine);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            log("خطا: " + msg);
            try {
                if (core != null && core.getIsRunning()) core.stopLoop();
            } catch (Exception ignored) {
            }
            closeTun();
            session.incrementAndGet();
            setState(STATE_ERROR, msg, "");
            stopForeground(true);
            stopSelf();
        }
    }

    /** Asks the connected tunnel for its public IP and country (a few tries while it warms up). */
    private void startExitLookup(final int mine) {
        final int port = apiPort;
        final String user = apiUser;
        final String pass = apiPass;
        new Thread(new Runnable() {
            @Override public void run() {
                for (int attempt = 0; attempt < EXIT_ATTEMPTS; attempt++) {
                    if (session.get() != mine) return;
                    ExitInfo.Info info = ExitInfo.lookup(port, user, pass);
                    if (info != null) {
                        if (session.get() != mine) return;
                        exitIp = info.ip;
                        exitCountry = info.country;
                        exitCode = info.code;
                        exitFailed = false;
                        setState(STATE_CONNECTED, "", null);
                        updateNotification("متصل", exitLine(), true);
                        return;
                    }
                    try {
                        Thread.sleep(EXIT_RETRY_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (session.get() != mine) return;
                exitFailed = true;
                setState(STATE_CONNECTED, "", null);
                updateNotification("متصل", "IP نامشخص", true);
            }
        }, "rayvpn-exit").start();
    }

    private static String exitLine() {
        String country = exitCountry == null || exitCountry.isEmpty() ? exitCode : exitCountry;
        if (country == null || country.isEmpty()) return exitIp;
        return exitIp + " · " + country;
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
        session.incrementAndGet();
        try {
            if (core != null && core.getIsRunning()) core.stopLoop();
        } catch (Exception e) {
            log("stop: " + e.getMessage());
        }
        core = null;
        closeTun();
        exitIp = "";
        exitCountry = "";
        exitCode = "";
        exitFailed = false;
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

    /** @param id null keeps the current active id */
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
        NotificationChannel channel = new NotificationChannel(CHANNEL, "وضعیت اتصال", NotificationManager.IMPORTANCE_LOW);
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
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setColor(0xFF35F0B0)
                .setColorized(false)
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
