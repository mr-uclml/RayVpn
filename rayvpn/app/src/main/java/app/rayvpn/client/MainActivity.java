package app.rayvpn.client;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;


import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import libv2ray.Libv2ray;

public class MainActivity extends Activity {
    private static final int REQ_VPN = 42;
    private static final int REQ_NOTIF = 43;

    private static final int C_ACCENT = 0xFF35F0B0;
    private static final int C_MUTED = 0xFF8B9ABA;
    private static final int C_WARN = 0xFFFFC46B;
    private static final int C_DANGER = 0xFFFF5C7A;
    private static final int C_TEXT = 0xFFF3F7FF;
    private static final int C_INK = 0xFF032018;

    private ConfigStore store;
    private TextView connectBtn;
    private TextView statusPill;
    private TextView serverName;
    private TextView detailText;
    private TextView emptyText;
    private TextView versionText;
    private TextView pingAllBtn;
    private View updateBar;
    private View ringA;
    private View ringB;
    private ListView list;
    private ProfileAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final java.util.Set<String> testingIds = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final Object DELAY_LOCK = new Object();
    private volatile boolean pinging;
    private volatile boolean pingCancel;
    private boolean applying;
    private boolean askedNotif;
    private boolean connectPulse;
    private String pingProgress = "";

    // ── view + animation state ─────────────────────────────────────────────
    private View root;
    private View hero;
    private View header;
    private View actions;
    private View glow;
    private View spinRing;
    private View statusDot;
    private View refreshBtn;
    private View refreshIcon;
    private View listWrap;
    private String lastPillText;
    private int lastPillColor = C_MUTED;
    private int lastBtnBg;
    private int lastBtnColor;
    private int lastGlowRes;
    private String lastState;
    private int dotColor = C_MUTED;
    private boolean breathing;
    private boolean spinning;
    private boolean refreshSpinning;
    private boolean emptyPulsing;
    private boolean updateBarShown;
    private ObjectAnimator breatheAnim;
    private ObjectAnimator spinAnim;
    private ObjectAnimator refreshAnim;
    private ObjectAnimator emptyAnim;
    private TextView pingChip;
    private TextView qualityChip;
    private TextView testConnBtn;
    private TextView hintText;
    private View metricsRow;
    private boolean testingActive;
    private int pingTotal;
    private int pingDone;
    private final java.util.Map<String, String> lastPingText = new java.util.HashMap<>();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            render();
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = new ConfigStore(this);
        connectBtn = findViewById(R.id.connectBtn);
        statusPill = findViewById(R.id.statusPill);
        serverName = findViewById(R.id.serverName);
        detailText = findViewById(R.id.detailText);
        emptyText = findViewById(R.id.emptyText);
        versionText = findViewById(R.id.versionText);
        pingAllBtn = findViewById(R.id.pingAllBtn);
        updateBar = findViewById(R.id.updateBar);
        ringA = findViewById(R.id.ringA);
        ringB = findViewById(R.id.ringB);
        list = findViewById(R.id.list);
        adapter = new ProfileAdapter();
        list.setAdapter(adapter);

        root = findViewById(R.id.root);
        hero = findViewById(R.id.hero);
        header = findViewById(R.id.header);
        actions = findViewById(R.id.actions);
        glow = findViewById(R.id.glow);
        spinRing = findViewById(R.id.spinRing);
        statusDot = findViewById(R.id.statusDot);
        refreshBtn = findViewById(R.id.refreshBtn);
        refreshIcon = findViewById(R.id.refreshIcon);
        listWrap = findViewById(R.id.listWrap);
        metricsRow = findViewById(R.id.metricsRow);
        pingChip = findViewById(R.id.pingChip);
        qualityChip = findViewById(R.id.qualityChip);
        testConnBtn = findViewById(R.id.testConnBtn);
        hintText = findViewById(R.id.hintText);
        Ui.pressable(testConnBtn);
        testConnBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { testActiveConnection(); }
        });

        list.setSelector(R.drawable.row_selector);
        glow.setAlpha(0.7f);
        lastBtnBg = R.drawable.bg_connect;
        lastBtnColor = C_INK;
        lastGlowRes = R.drawable.bg_glow;
        if (statusDot.getBackground() != null) statusDot.getBackground().setTint(C_MUTED);

        Ui.applyFont(root);
        Ui.pressable(connectBtn);
        Ui.pressable(refreshBtn);
        Ui.pressable(pingAllBtn);
        Ui.pressable(findViewById(R.id.sortBtn));
        Ui.pressable(findViewById(R.id.updateBtn));
        root.post(new Runnable() {
            @Override public void run() { playEntrance(); }
        });

        connectBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onConnectClicked(); }
        });
        findViewById(R.id.refreshBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applyUpdate(); }
        });
        findViewById(R.id.updateBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applyUpdate(); }
        });
        pingAllBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onPingClicked(); }
        });
        findViewById(R.id.sortBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sortByPing(); }
        });
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= store.profiles.size()) return;
                store.select(store.profiles.get(position).id);
                render();
            }
        });
        list.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= store.profiles.size()) return false;
                Ui.bounce(view);
                testSingle(store.profiles.get(position));
                return true;
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(VpnTunnelService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
        IntentFilter configs = new IntentFilter(ConfigSync.ACTION_UPDATED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(configReceiver, configs, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(configReceiver, configs);
    }

    private final BroadcastReceiver configReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            applying = false;
            store.load();
            render();
            boolean changed = intent.getBooleanExtra("changed", false);
            boolean available = intent.getBooleanExtra("available", false);
            boolean manual = intent.getBooleanExtra("manual", false);
            boolean removed = intent.getBooleanExtra("removedActive", false);
            String error = intent.getStringExtra("error");
            if (removed) toast("سرور قبلی از لیست حذف شد");
            else if (changed) toast(intent.getIntExtra("count", 0) + " سرور به‌روز شد");
            else if (available) {
                toast("آپدیت آمد. می‌توانی لیست را به‌روزرسانی کنی");
                askNotify();
            } else if (error != null && !error.isEmpty() && (manual || store.profiles.isEmpty())) toast(error);
            else if (manual) toast("لیست تغییری نکرده");
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        store.load();
        render();
        ConfigSync.syncNow(false);
    }

    @Override
    protected void onStop() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        try { unregisterReceiver(configReceiver); } catch (Exception ignored) {}
        super.onStop();
    }

    private void applyUpdate() {
        if (applying) return;
        if (pinging) {
            toast("اول پینگ تمام شود");
            return;
        }
        applying = true;
        versionText.setText("در حال به‌روزرسانی…");
        ConfigSync.syncNow(true);
    }

    private void askNotify() {
        if (askedNotif || Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        askedNotif = true;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
    }

    private void onPingClicked() {
        if (pinging) {
            pingCancel = true;
            pingAllBtn.setText("در حال توقف…");
            return;
        }
        if (store.profiles.isEmpty()) {
            toast("هنوز سروری در فهرست نیست");
            return;
        }
        pinging = true;
        pingCancel = false;
        testingIds.clear();
        for (Profile p : store.profiles) {
            if (p.id == null) continue;
            testingIds.add(p.id);
            p.pingMs = -2;
        }
        pingTotal = store.profiles.size();
        pingDone = 0;
        pingProgress = "";
        updatePingButton();
        setHintVisible(false);
        adapter.notifyDataSetChanged();
        render();
        final List<Profile> targets = new ArrayList<>(store.profiles);
        new Thread(new Runnable() {
            @Override public void run() { runAccurateTests(targets); }
        }).start();
    }

    /**
     * The number shown to the user always comes from a real request through the
     * config itself (Xray core + HTTP 204). A silent TCP probe is used only to
     * skip servers that provably cannot work, so a full run stays quick.
     */
    private void runAccurateTests(List<Profile> targets) {
        for (int i = 0; i < targets.size(); i++) {
            if (pingCancel) break;
            final Profile p = targets.get(i);
            if (needsTcpGate(p)) {
                long tcp = tcpPing(p.address, p.port, 1300);
                if (tcp < 0) {
                    publishResult(p, -1L);
                    continue;
                }
            }
            long ms = realDelayBest(p, 1);
            publishResult(p, ms);
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean cancelled = pingCancel;
                pinging = false;
                pingCancel = false;
                pingProgress = "";
                testingIds.clear();
                store.save();
                setHintVisible(true);
                render();
                toast(cancelled ? "تست متوقف شد" : "تست همه تمام شد");
            }
        });
    }

    private void publishResult(final Profile p, final long ms) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                p.pingMs = ms;
                if (p.id != null) testingIds.remove(p.id);
                pingDone++;
                pingProgress = pingDone + "/" + pingTotal;
                adapter.notifyDataSetChanged();
                updatePingButton();
                updateHeroMetrics(store.selected());
            }
        });
    }

    /** Accurate test of one config only (long press on a row). */
    private void testSingle(final Profile p) {
        if (p == null || p.id == null) return;
        if (pinging) {
            toast("اول «تست همه» را تمام کن");
            return;
        }
        if (testingIds.contains(p.id)) return;
        final String label = p.name == null || p.name.isEmpty() ? p.address : p.name;
        testingIds.add(p.id);
        p.pingMs = -2;
        adapter.notifyDataSetChanged();
        updateHeroMetrics(store.selected());
        toast("تست دقیق: " + label);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final long ms = realDelayBest(p, 2);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        p.pingMs = ms;
                        testingIds.remove(p.id);
                        store.save();
                        adapter.notifyDataSetChanged();
                        updateHeroMetrics(store.selected());
                        toast(ms < 0 ? "بی‌پاسخ: " + label
                                : label + " · " + ms + " ms · " + quality(ms));
                    }
                });
            }
        }).start();
    }

    /** Tests only the config that is currently connected. */
    private void testActiveConnection() {
        if (testingActive) return;
        Profile p = null;
        String activeId = VpnTunnelService.activeId;
        if (activeId != null && !activeId.isEmpty()) p = store.byId(activeId);
        if (p == null) p = store.selected();
        if (p == null) {
            toast("سروری برای تست نیست");
            return;
        }
        testingActive = true;
        final Profile target = p;
        if (target.id != null) testingIds.add(target.id);
        target.pingMs = -2;
        testConnBtn.setText("در حال تست…");
        adapter.notifyDataSetChanged();
        updateHeroMetrics(store.selected());
        new Thread(new Runnable() {
            @Override
            public void run() {
                final long ms = realDelayBest(target, 2);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        testingActive = false;
                        target.pingMs = ms;
                        if (target.id != null) testingIds.remove(target.id);
                        store.save();
                        testConnBtn.setText("تست همین اتصال");
                        adapter.notifyDataSetChanged();
                        updateHeroMetrics(target);
                        toast(ms < 0 ? "این اتصال جواب نداد" : "کیفیت " + quality(ms) + " · " + ms + " ms");
                    }
                });
            }
        }).start();
    }

    private long realDelayBest(Profile p, int tries) {
        long best = -1;
        for (int i = 0; i < tries; i++) {
            long ms = realDelay(p);
            if (ms >= 0 && (best < 0 || ms < best)) best = ms;
            if (ms >= 0 && best >= 0) break;
        }
        return best;
    }

    private long realDelay(Profile p) {
        synchronized (DELAY_LOCK) {
            try {
                String cfg = XrayConfigBuilder.buildDelay(p);
                long ms = Libv2ray.measureOutboundDelay(cfg, "https://www.gstatic.com/generate_204");
                return ms < 0 ? -1L : ms;
            } catch (Throwable e) {
                return -1L;
            }
        }
    }

    /** UDP transports cannot be probed with a TCP handshake. */
    private static boolean needsTcpGate(Profile p) {
        if (p == null) return false;
        if ("hysteria".equalsIgnoreCase(p.protocol)) return false;
        if ("kcp".equalsIgnoreCase(p.network)) return false;
        if ("raw".equalsIgnoreCase(p.protocol)) return false;
        return p.address != null && !p.address.isEmpty() && p.port > 0;
    }

    private static String quality(long ms) {
        if (ms < 0) return "بی‌پاسخ";
        if (ms < 120) return "عالی";
        if (ms < 250) return "خوب";
        if (ms < 450) return "متوسط";
        return "کند";
    }

    private static int qualityColor(long ms) {
        if (ms < 0) return C_DANGER;
        if (ms < 120) return C_ACCENT;
        if (ms < 250) return 0xFF6FE3C0;
        if (ms < 450) return C_WARN;
        return C_DANGER;
    }

    private static long tcpPing(String host, int port, int timeoutMs) {
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
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private void sortByPing() {
        if (store.profiles.isEmpty()) {
            toast("لیستی برای مرتب‌سازی نیست");
            return;
        }
        boolean any = false;
        for (Profile p : store.profiles) if (p.pingMs >= 0) any = true;
        if (!any) {
            toast("اول «تست همه» را بزن تا سرعت‌ها مشخص شود");
            return;
        }
        store.sortByPing();
        render();
        toast("سریع‌ترین سرورها بالا آمدند");
    }

    private void onConnectClicked() {
        if (VpnTunnelService.STATE_CONNECTED.equals(VpnTunnelService.state)
                || VpnTunnelService.STATE_CONNECTING.equals(VpnTunnelService.state)) {
            VpnTunnelService.requestStop(this);
            return;
        }
        Profile p = store.selected();
        if (p == null) {
            toast("اول یک سرور را از فهرست انتخاب کن");
            return;
        }
        try {
            XrayConfigBuilder.buildTunnel(p);
        } catch (Exception e) {
            toast(e.getMessage() == null ? "این سرور قابل اتصال نیست" : e.getMessage());
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
        Intent prep = VpnService.prepare(this);
        if (prep != null) startActivityForResult(prep, REQ_VPN);
        else startTunnel();
    }

    private void startTunnel() {
        Profile p = store.selected();
        if (p == null) return;
        Intent i = new Intent(this, VpnTunnelService.class);
        i.putExtra(VpnTunnelService.EXTRA_PROFILE_ID, p.id);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        VpnTunnelService.state = VpnTunnelService.STATE_CONNECTING;
        VpnTunnelService.detail = "در حال اتصال…";
        render();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            ConfigSync.remind();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) startTunnel();
            else toast("اجازهٔ VPN داده نشد");
        }
    }

    private void render() {
        adapter.notifyDataSetChanged();

        boolean empty = store.profiles.isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        emptyText.setText("فهرستی نرسیده\nآیکون تازه‌سازی بالا را بزن");
        syncEmptyPulse(empty);

        Profile selected = store.selected();
        String name = selected == null ? "سروری انتخاب نشده"
                : (selected.name == null || selected.name.isEmpty() ? selected.address : selected.name);
        Ui.setTextSmooth(serverName, name);

        if (applying) Ui.setTextSmooth(versionText, "در حال به‌روزرسانی…");
        else if (ConfigSync.updateAvailable) Ui.setTextSmooth(versionText, "نسخهٔ تازه آماده است");
        else if (empty) Ui.setTextSmooth(versionText, "فهرست خالی است");
        else Ui.setTextSmooth(versionText, persianCount(store.profiles.size()) + " سرور آماده");

        setUpdateBarVisible(ConfigSync.updateAvailable);
        setRefreshSpinning(applying);
        updatePingButton();

        String st = VpnTunnelService.state;
        updateStatusUi(st);
        handleStatePulse(st);
        updateHeroMetrics(selected);
        setRowVisible(testConnBtn, VpnTunnelService.STATE_CONNECTED.equals(st));

        if (VpnTunnelService.STATE_CONNECTED.equals(st)) {
            String base = VpnTunnelService.detail == null || VpnTunnelService.detail.isEmpty()
                    ? "تونل روشن است" : VpnTunnelService.detail;
            if (selected != null && selected.pingMs >= 0) base = base + "  ·  کیفیت " + quality(selected.pingMs);
            Ui.setTextSmooth(detailText, base);
        } else if (VpnTunnelService.STATE_CONNECTING.equals(st)) {
            Ui.setTextSmooth(detailText, "در حال اتصال…");
        } else if (VpnTunnelService.STATE_ERROR.equals(st)) {
            Ui.setTextSmooth(detailText, VpnTunnelService.detail == null ? "اتصال برقرار نشد" : VpnTunnelService.detail);
        } else {
            if (selected != null) Ui.setTextSmooth(detailText, selected.subtitle() + pingSuffix(selected));
            else Ui.setTextSmooth(detailText, "یک سرور را از فهرست انتخاب کن");
        }
    }

    // ── state driven look & feel ───────────────────────────────────────────

    private void updateStatusUi(String st) {
        boolean connecting = VpnTunnelService.STATE_CONNECTING.equals(st);
        boolean connected = VpnTunnelService.STATE_CONNECTED.equals(st);
        boolean error = VpnTunnelService.STATE_ERROR.equals(st);

        String pillText;
        int pillColor;
        String btnText;
        int btnColor;
        int btnBg;
        int glowRes;
        if (connected) {
            pillText = "وصل";
            pillColor = C_ACCENT;
            btnText = "قطع";
            btnColor = C_TEXT;
            btnBg = R.drawable.bg_connect_off;
            glowRes = R.drawable.bg_glow_danger;
        } else if (connecting) {
            pillText = "در حال اتصال";
            pillColor = C_WARN;
            btnText = "اتصال";
            btnColor = C_INK;
            btnBg = R.drawable.bg_connect_wait;
            glowRes = R.drawable.bg_glow;
        } else if (error) {
            pillText = "خطا";
            pillColor = C_DANGER;
            btnText = "اتصال";
            btnColor = C_INK;
            btnBg = R.drawable.bg_connect;
            glowRes = R.drawable.bg_glow;
        } else {
            pillText = "آماده";
            pillColor = C_MUTED;
            btnText = "اتصال";
            btnColor = C_INK;
            btnBg = R.drawable.bg_connect;
            glowRes = R.drawable.bg_glow;
        }

        if (lastPillText == null || !pillText.equals(lastPillText)) {
            lastPillText = pillText;
            Ui.setTextSmooth(statusPill, pillText);
            statusPill.setScaleX(0.88f);
            statusPill.setScaleY(0.88f);
            statusPill.animate().scaleX(1f).scaleY(1f)
                    .setDuration(340).setInterpolator(Ui.SPRING).start();
        }
        if (pillColor != lastPillColor) {
            animateTextColor(statusPill, pillColor, 280);
            animateDotColor(pillColor, 280);
            lastPillColor = pillColor;
        }

        if (!btnText.equals(connectBtn.getText().toString())) {
            Ui.setTextSmooth(connectBtn, btnText);
        }
        if (btnColor != lastBtnColor) {
            connectBtn.setTextColor(btnColor);
            lastBtnColor = btnColor;
        }
        if (btnBg != lastBtnBg) {
            lastBtnBg = btnBg;
            connectBtn.setBackgroundResource(btnBg);
            connectBtn.setScaleX(0.9f);
            connectBtn.setScaleY(0.9f);
            connectBtn.animate().scaleX(1f).scaleY(1f)
                    .setDuration(430).setInterpolator(Ui.SPRING).start();
        }
        if (glowRes != lastGlowRes) {
            lastGlowRes = glowRes;
            glow.setBackgroundResource(glowRes);
        }

        syncConnectAnim(connecting);
        setSpinning(connecting);
        setBreathing(connected || connecting);
    }

    private void handleStatePulse(String st) {
        if (lastState == null) {
            lastState = st;
            return;
        }
        if (lastState.equals(st)) return;
        lastState = st;
        if (VpnTunnelService.STATE_CONNECTED.equals(st)) successPulse();
    }

    private void successPulse() {
        if (connectPulse) return;
        ringB.setVisibility(View.VISIBLE);
        ringB.setAlpha(0.9f);
        ringB.setScaleX(1f);
        ringB.setScaleY(1f);
        ringB.animate().scaleX(1.9f).scaleY(1.9f).alpha(0f)
                .setDuration(760).setInterpolator(Ui.SOFT)
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        if (!connectPulse) {
                            ringB.setVisibility(View.GONE);
                            ringB.setAlpha(1f);
                        }
                    }
                }).start();
    }

    private void setBreathing(boolean on) {
        if (on == breathing) return;
        breathing = on;
        if (breatheAnim != null) {
            breatheAnim.cancel();
            breatheAnim = null;
        }
        if (on) {
            breatheAnim = ObjectAnimator.ofFloat(glow, "alpha", 0.45f, 1f);
            breatheAnim.setDuration(1750);
            breatheAnim.setRepeatCount(ValueAnimator.INFINITE);
            breatheAnim.setRepeatMode(ValueAnimator.REVERSE);
            breatheAnim.setInterpolator(Ui.SWING);
            breatheAnim.start();
        } else {
            glow.animate().alpha(0.7f).setDuration(320).setInterpolator(Ui.SOFT).start();
        }
    }

    private void setSpinning(boolean on) {
        if (on == spinning) return;
        spinning = on;
        if (spinAnim != null) {
            spinAnim.cancel();
            spinAnim = null;
        }
        if (on) {
            spinRing.setVisibility(View.VISIBLE);
            spinRing.setAlpha(0f);
            spinRing.animate().alpha(1f).setDuration(280).start();
            spinAnim = ObjectAnimator.ofFloat(spinRing, "rotation", 0f, 360f);
            spinAnim.setDuration(1500);
            spinAnim.setRepeatCount(ValueAnimator.INFINITE);
            spinAnim.setInterpolator(new LinearInterpolator());
            spinAnim.start();
        } else {
            spinRing.setVisibility(View.GONE);
            spinRing.setRotation(0f);
        }
    }

    private void setRefreshSpinning(boolean on) {
        if (on == refreshSpinning) return;
        refreshSpinning = on;
        if (refreshAnim != null) {
            refreshAnim.cancel();
            refreshAnim = null;
        }
        if (on) {
            refreshAnim = ObjectAnimator.ofFloat(refreshIcon, "rotation", 0f, 360f);
            refreshAnim.setDuration(1100);
            refreshAnim.setRepeatCount(ValueAnimator.INFINITE);
            refreshAnim.setInterpolator(new LinearInterpolator());
            refreshAnim.start();
        } else {
            refreshIcon.animate().rotation(0f).setDuration(220).start();
        }
    }

    private void syncEmptyPulse(boolean empty) {
        if (empty == emptyPulsing) return;
        emptyPulsing = empty;
        if (emptyAnim != null) {
            emptyAnim.cancel();
            emptyAnim = null;
        }
        if (empty) {
            emptyText.setAlpha(1f);
            emptyAnim = ObjectAnimator.ofFloat(emptyText, "alpha", 0.42f, 1f);
            emptyAnim.setDuration(1250);
            emptyAnim.setRepeatCount(ValueAnimator.INFINITE);
            emptyAnim.setRepeatMode(ValueAnimator.REVERSE);
            emptyAnim.setInterpolator(Ui.SWING);
            emptyAnim.start();
        } else {
            emptyText.setAlpha(1f);
        }
    }

    private void setUpdateBarVisible(boolean visible) {
        if (visible == updateBarShown) return;
        updateBarShown = visible;
        updateBar.animate().cancel();
        if (visible) {
            updateBar.setVisibility(View.VISIBLE);
            updateBar.setAlpha(0f);
            updateBar.setTranslationY(-Ui.dp(this, 10));
            updateBar.animate().alpha(1f).translationY(0f)
                    .setDuration(300).setInterpolator(Ui.EASE).start();
        } else {
            updateBar.animate().alpha(0f).translationY(-Ui.dp(this, 10))
                    .setDuration(180).setInterpolator(Ui.SOFT)
                    .withEndAction(new Runnable() {
                        @Override public void run() { updateBar.setVisibility(View.GONE); }
                    }).start();
        }
    }

    private void animateTextColor(final TextView view, int to, long duration) {
        int from = view.getCurrentTextColor();
        if (from == to) return;
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        anim.setDuration(duration);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                view.setTextColor((Integer) a.getAnimatedValue());
            }
        });
        anim.start();
    }

    private void animateDotColor(int to, long duration) {
        if (dotColor == to) return;
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), dotColor, to);
        anim.setDuration(duration);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                dotColor = (Integer) a.getAnimatedValue();
                if (statusDot.getBackground() != null) statusDot.getBackground().setTint(dotColor);
            }
        });
        anim.start();
    }

    private void updateHeroMetrics(Profile p) {
        boolean testing = p != null && p.id != null && testingIds.contains(p.id);
        boolean show = p != null && (testing || p.pingMs != -2);
        setRowVisible(metricsRow, show);
        if (!show) return;

        String pingText;
        String qualityText;
        int color;
        if (testing || p.pingMs == -2) {
            pingText = "پینگ …";
            qualityText = "کیفیت …";
            color = C_WARN;
        } else if (p.pingMs < 0) {
            pingText = "پینگ —";
            qualityText = "کیفیت —";
            color = C_DANGER;
        } else {
            pingText = "پینگ " + p.pingMs + " ms";
            qualityText = "کیفیت " + quality(p.pingMs);
            color = qualityColor(p.pingMs);
        }
        Ui.setTextSmooth(pingChip, pingText);
        Ui.setTextSmooth(qualityChip, qualityText);
        animateTextColor(pingChip, color, 260);
        animateTextColor(qualityChip, color, 260);
    }

    private void setRowVisible(final View view, boolean visible) {
        if (view == null) return;
        boolean shown = view.getVisibility() == View.VISIBLE;
        if (visible == shown) return;
        view.animate().cancel();
        if (visible) {
            view.setVisibility(View.VISIBLE);
            view.setAlpha(0f);
            view.setTranslationY(Ui.dp(this, 6));
            view.animate().alpha(1f).translationY(0f)
                    .setDuration(260).setInterpolator(Ui.EASE).start();
        } else {
            view.animate().alpha(0f).setDuration(160).setInterpolator(Ui.SOFT)
                    .withEndAction(new Runnable() {
                        @Override public void run() { view.setVisibility(View.GONE); }
                    }).start();
        }
    }

    private void setHintVisible(boolean visible) {
        if (hintText == null) return;
        hintText.animate().cancel();
        if (visible) {
            hintText.setVisibility(View.VISIBLE);
            hintText.animate().alpha(1f).setDuration(220).start();
        } else {
            hintText.animate().alpha(0f).setDuration(150).setInterpolator(Ui.SOFT)
                    .withEndAction(new Runnable() {
                        @Override public void run() { hintText.setVisibility(View.GONE); }
                    }).start();
        }
    }

    private void playEntrance() {
        float d = Ui.dp(this, 14);
        header.setAlpha(0f);
        header.setTranslationY(-d * 0.6f);
        header.animate().alpha(1f).translationY(0f)
                .setStartDelay(30).setDuration(420).setInterpolator(Ui.EASE).start();

        hero.setAlpha(0f);
        hero.setTranslationY(d);
        hero.setScaleX(0.94f);
        hero.setScaleY(0.94f);
        hero.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(110).setDuration(540).setInterpolator(Ui.EASE).start();

        actions.setAlpha(0f);
        actions.setTranslationY(d);
        actions.animate().alpha(1f).translationY(0f)
                .setStartDelay(200).setDuration(460).setInterpolator(Ui.EASE).start();

        listWrap.setAlpha(0f);
        listWrap.setTranslationY(d);
        listWrap.animate().alpha(1f).translationY(0f)
                .setStartDelay(270).setDuration(460).setInterpolator(Ui.EASE).start();
    }

    /** Persian digits for short counters. */
    private static String persianCount(int value) {
        String digits = "۰۱۲۳۴۵۶۷۸۹";
        String raw = String.valueOf(value);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            out.append(ch >= '0' && ch <= '9' ? digits.charAt(ch - '0') : ch);
        }
        return out.toString();
    }

    private String pingSuffix(Profile p) {
        if (p.pingMs >= 0) return "  ·  " + p.pingMs + " ms";
        if (p.pingMs == -1) return "  ·  بدون پاسخ";
        return "";
    }

    private void syncConnectAnim(boolean connecting) {
        if (connecting) {
            if (connectPulse) return;
            connectPulse = true;
            startRing(ringA, 0);
            startRing(ringB, 150);
            return;
        }
        if (!connectPulse) return;
        connectPulse = false;
        ringA.animate().setListener(null);
        ringB.animate().setListener(null);
        ringA.animate().cancel();
        ringB.animate().cancel();
        ringA.setVisibility(View.GONE);
        ringB.setVisibility(View.GONE);
        ringA.setAlpha(1f);
        ringB.setAlpha(1f);
        ringA.setScaleX(1f);
        ringA.setScaleY(1f);
        ringB.setScaleX(1f);
        ringB.setScaleY(1f);
    }

    private void startRing(final View ring, long delay) {
        ring.setVisibility(View.VISIBLE);
        ring.setScaleX(0.84f);
        ring.setScaleY(0.84f);
        ring.setAlpha(0.9f);
        ring.post(new Runnable() {
            @Override
            public void run() {
                ring.setPivotX(ring.getWidth() / 2f);
                ring.setPivotY(ring.getHeight() / 2f);
                pulse(ring, delay);
            }
        });
    }

    private void pulse(final View ring, long delay) {
        ring.animate().setListener(null);
        ring.animate().cancel();
        ring.setScaleX(0.84f);
        ring.setScaleY(0.84f);
        ring.setAlpha(0.9f);
        ring.animate()
                .scaleX(1.12f)
                .scaleY(1.12f)
                .alpha(0.05f)
                .setStartDelay(delay)
                .setDuration(420)
                .setInterpolator(new LinearInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (connectPulse && ring.getVisibility() == View.VISIBLE) pulse(ring, 0);
                    }
                })
                .start();
    }

    private void updatePingButton() {
        if (pinging) {
            pingAllBtn.setText(pingProgress.isEmpty() ? "در حال تست…" : "توقف " + pingProgress);
        } else {
            pingAllBtn.setText("تست همه");
        }
    }

    private void toast(String text) {
        Ui.snack(root, text);
    }

    private class ProfileAdapter extends BaseAdapter {
        @Override public int getCount() { return store.profiles.size(); }
        @Override public Object getItem(int position) { return store.profiles.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            boolean fresh = false;
            if (row == null) {
                row = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_profile, parent, false);
                fresh = true;
                Ui.applyFont(row);
            }
            Profile p = store.profiles.get(position);
            TextView name = row.findViewById(R.id.name);
            TextView sub = row.findViewById(R.id.sub);
            TextView ping = row.findViewById(R.id.ping);
            View bar = row.findViewById(R.id.bar);

            name.setText(p.name == null || p.name.isEmpty() ? p.address : p.name);
            sub.setText(p.subtitle());

            boolean on = p.id != null && p.id.equals(store.selectedId);
            row.setBackgroundResource(on ? R.drawable.bg_card_on : R.drawable.bg_card_plain);
            if (bar.getAlpha() != (on ? 1f : 0f)) {
                bar.animate().cancel();
                bar.animate().alpha(on ? 1f : 0f)
                        .setDuration(on ? 250 : 170)
                        .setInterpolator(on ? Ui.EASE : Ui.SOFT).start();
            }

            String text;
            int color;
            boolean testing = p.id != null && testingIds.contains(p.id) && p.pingMs < 0;
            if (testing) {
                text = "…";
                color = C_WARN;
            } else if (p.pingMs >= 0) {
                text = p.pingMs + " ms";
                color = qualityColor(p.pingMs);
            } else if (p.pingMs == -1) {
                text = "بی‌پاسخ";
                color = C_DANGER;
            } else {
                text = "—";
                color = C_MUTED;
            }

            String previous = lastPingText.get(p.id == null ? "" : p.id);
            if (previous == null || !previous.equals(text) || fresh) {
                ping.setText(text);
                ping.setTextColor(color);
                lastPingText.put(p.id == null ? "" : p.id, text);
                if (!fresh && !testing && !"—".equals(text)) {
                    ping.setScaleX(0.82f);
                    ping.setScaleY(0.82f);
                    ping.setAlpha(0.35f);
                    ping.animate().scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(360).setInterpolator(Ui.SPRING).start();
                }
            } else if (ping.getCurrentTextColor() != color) {
                animateTextColor(ping, color, 240);
            }

            if (fresh) {
                row.setAlpha(0f);
                row.setTranslationY(Ui.dp(MainActivity.this, 10));
                row.animate().alpha(1f).translationY(0f)
                        .setStartDelay(Math.min(position, 7) * 42L)
                        .setDuration(330).setInterpolator(Ui.EASE).start();
                if (on) bar.setAlpha(1f);
            }
            return row;
        }
    }
}
