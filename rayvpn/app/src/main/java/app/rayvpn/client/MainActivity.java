package app.rayvpn.client;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * The dashboard: three quiet pages (connect / stats / settings) over the same
 * tunnel service. Nothing here decides which server to use — the service does
 * that; the screen only shows the three states it reports.
 */
public class MainActivity extends Activity {

    private static final int REQ_VPN = 42;
    private static final int REQ_NOTIF = 43;

    private static final int TAB_CONNECT = 0;
    private static final int TAB_STATS = 1;
    private static final int TAB_SETTINGS = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private ConfigStore store;
    private ServerSelector.Mode mode = ServerSelector.Mode.BALANCE;
    private boolean persian;

    private int tab = TAB_CONNECT;
    private String lastState;
    private String lastPill;
    private String lastHeroKey = "";
    private int lastPillColor = -1;
    private int lastCoreStyle = -1;
    private boolean applying;
    private boolean pingRunning;
    private boolean refreshSpinning;
    private boolean updateBarShown;

    /** Session counters already folded into the stored totals. */
    private long flushedDown;
    private long flushedUp;
    private boolean sessionCounted;
    private long lastFlush;

    private View root;
    private View header;
    private View connectWrap;
    private View glow;

    private View spinArc;
    private View ringA;
    private View ringB;
    private View ringOuter;
    private View statusDot;
    private View updateBar;
    private View refreshRow;
    private ImageView refreshIcon;

    private RayText statusText;
    private RayText connectCore;
    private RayText heroTitle;
    private RayText heroSub;
    private RayText valDown;
    private RayText valUp;
    private RayText valTime;
    private RayText pingBtn;
    private RayText modeFast;
    private RayText modeBalance;
    private RayText modeBest;
    private RayText footerHint;

    private View tabConnect;
    private View tabStats;
    private View tabSettings;

    private View navConnect;
    private View navStats;
    private View navSettings;
    private ImageView navConnectIcon;
    private ImageView navStatsIcon;
    private ImageView navSettingsIcon;
    private RayText navConnectText;
    private RayText navStatsText;
    private RayText navSettingsText;

    private SpeedGraph graph;
    private RayText speedDown;
    private RayText speedUp;
    private RayText sessDown;
    private RayText sessUp;
    private RayText sessTime;
    private RayText totalData;
    private RayText totalSessions;
    private RayText totalPing;

    private RayText themeDark;
    private RayText themeLight;
    private RayText langFa;
    private RayText langEn;
    private RayText serversCount;
    private RayText refreshText;
    private RayText aboutVersion;
    private ToggleView notifToggle;

    private ObjectAnimator breathe;
    private ObjectAnimator spin;
    private ObjectAnimator refreshAnim;
    private boolean breathing;
    private boolean spinning;
    private boolean pulsing;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            render();
        }
    };

    private final BroadcastReceiver configReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            applying = false;
            store.load();
            render();
            boolean changed = intent.getBooleanExtra("changed", false);
            boolean available = intent.getBooleanExtra("available", false);
            boolean manual = intent.getBooleanExtra("manual", false);
            boolean removed = intent.getBooleanExtra("removedActive", false);
            String error = intent.getStringExtra("error");
            if (removed) {
                toast(getString(R.string.msg_previous_removed));
            } else if (changed) {
                int skipped = intent.getIntExtra("skipped", 0);
                int count = intent.getIntExtra("count", 0);
                toast(skipped > 0
                        ? getString(R.string.msg_server_added_skipped, num(count), num(skipped))
                        : getString(R.string.msg_server_added, num(count)));
            } else if (available) {
                toast(getString(R.string.msg_update_ready));
                askNotify();
            } else if (error != null && !error.isEmpty() && (manual || store.profiles.isEmpty())) {
                toast(error);
            } else if (manual) {
                toast(getString(R.string.msg_no_change));
            }
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateLive();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(Prefs.light(this) ? R.style.Theme_RayVPN_Light : R.style.Theme_RayVPN);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        persian = LocaleHelper.isPersian(this);
        store = new ConfigStore(this);
        mode = Prefs.mode(this);

        root = findViewById(R.id.root);
        header = findViewById(R.id.header);
        connectWrap = findViewById(R.id.connectWrap);
        glow = findViewById(R.id.glow);
        spinArc = findViewById(R.id.spinArc);
        ringA = findViewById(R.id.ringA);
        ringB = findViewById(R.id.ringB);
        ringOuter = findViewById(R.id.ringOuter);
        statusDot = findViewById(R.id.statusDot);
        updateBar = findViewById(R.id.updateBar);
        refreshRow = findViewById(R.id.refreshRow);
        refreshIcon = findViewById(R.id.refreshIcon);

        statusText = findViewById(R.id.statusText);
        connectCore = findViewById(R.id.connectCore);
        heroTitle = findViewById(R.id.heroTitle);
        heroSub = findViewById(R.id.heroSub);
        valDown = findViewById(R.id.valDown);
        valUp = findViewById(R.id.valUp);
        valTime = findViewById(R.id.valTime);
        pingBtn = findViewById(R.id.pingBtn);
        modeFast = findViewById(R.id.modeFast);
        modeBalance = findViewById(R.id.modeBalance);
        modeBest = findViewById(R.id.modeBest);
        footerHint = findViewById(R.id.footerHint);

        tabConnect = findViewById(R.id.tabConnect);
        tabStats = findViewById(R.id.tabStats);
        tabSettings = findViewById(R.id.tabSettings);

        navConnect = findViewById(R.id.navConnect);
        navStats = findViewById(R.id.navStats);
        navSettings = findViewById(R.id.navSettings);
        navConnectIcon = findViewById(R.id.navConnectIcon);
        navStatsIcon = findViewById(R.id.navStatsIcon);
        navSettingsIcon = findViewById(R.id.navSettingsIcon);
        navConnectText = findViewById(R.id.navConnectText);
        navStatsText = findViewById(R.id.navStatsText);
        navSettingsText = findViewById(R.id.navSettingsText);

        graph = findViewById(R.id.graph);
        speedDown = findViewById(R.id.speedDown);
        speedUp = findViewById(R.id.speedUp);
        sessDown = findViewById(R.id.sessDown);
        sessUp = findViewById(R.id.sessUp);
        sessTime = findViewById(R.id.sessTime);
        totalData = findViewById(R.id.totalData);
        totalSessions = findViewById(R.id.totalSessions);
        totalPing = findViewById(R.id.totalPing);

        themeDark = findViewById(R.id.themeDark);
        themeLight = findViewById(R.id.themeLight);
        langFa = findViewById(R.id.langFa);
        langEn = findViewById(R.id.langEn);
        serversCount = findViewById(R.id.serversCount);
        refreshText = findViewById(R.id.refreshText);
        aboutVersion = findViewById(R.id.aboutVersion);
        notifToggle = findViewById(R.id.notifToggle);

        Ui.applyFont(root);
        Ui.pressable(connectCore);
        Ui.pressable(pingBtn);
        Ui.pressable(findViewById(R.id.updateBtn));
        Ui.pressable(themeDark);
        Ui.pressable(themeLight);
        Ui.pressable(langFa);
        Ui.pressable(langEn);
        Ui.pressable(findViewById(R.id.statsReset));

        connectCore.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onConnectClicked(); }
        });
        pingBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onPingClicked(); }
        });
        findViewById(R.id.updateBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applyUpdate(); }
        });
        updateBar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applyUpdate(); }
        });
        refreshRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applyUpdate(); }
        });

        modeFast.setOnClickListener(modeClick(ServerSelector.Mode.FAST));
        modeBalance.setOnClickListener(modeClick(ServerSelector.Mode.BALANCE));
        modeBest.setOnClickListener(modeClick(ServerSelector.Mode.BEST));

        themeDark.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickTheme(false); }
        });
        themeLight.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickTheme(true); }
        });
        langFa.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickLanguage("fa"); }
        });
        langEn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickLanguage("en"); }
        });
        findViewById(R.id.statsReset).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { resetStats(); }
        });

        notifToggle.setChecked(Prefs.notifications(this), false);
        notifToggle.setListener(new ToggleView.Listener() {
            @Override public void onToggle(boolean checked) {
                Prefs.notifications(MainActivity.this, checked);
                if (checked) askNotify();
            }
        });

        navConnect.setOnClickListener(navClick(TAB_CONNECT));
        navStats.setOnClickListener(navClick(TAB_STATS));
        navSettings.setOnClickListener(navClick(TAB_SETTINGS));

        aboutVersion.setText(getString(R.string.about_version, versionName()));
        connectCore.weight(Ui.W_SEMIBOLD);
        glow.setAlpha(0.75f);
        statusDot.getBackground().setTint(Ui.color(this, R.attr.rayMuted));

        renderTabs(false);
        render();
        root.post(new Runnable() {
            @Override public void run() { playEntrance(); }
        });
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private View.OnClickListener modeClick(final ServerSelector.Mode picked) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.bounce(v);
                if (busy(VpnTunnelService.state)) {
                    toast(getString(R.string.msg_stop_first));
                    return;
                }
                if (picked == mode) return;
                mode = picked;
                Prefs.mode(MainActivity.this, picked);
                render();
            }
        };
    }

    private View.OnClickListener navClick(final int which) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (which == tab) return;
                tab = which;
                renderTabs(true);
            }
        };
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter state = new IntentFilter(VpnTunnelService.ACTION_STATE);
        IntentFilter configs = new IntentFilter(ConfigSync.ACTION_UPDATED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, state, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(configReceiver, configs, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, state);
            registerReceiver(configReceiver, configs);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        store.load();
        render();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        ConfigSync.syncNow(false);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        flushTotals();
        super.onPause();
    }

    @Override
    protected void onStop() {
        try { unregisterReceiver(stateReceiver); } catch (Exception ignored) { }
        try { unregisterReceiver(configReceiver); } catch (Exception ignored) { }
        super.onStop();
    }

    // ── actions ────────────────────────────────────────────────────────────

    private static boolean busy(String state) {
        return VpnTunnelService.STATE_CONNECTING.equals(state)
                || VpnTunnelService.STATE_CONNECTED.equals(state);
    }

    private void onConnectClicked() {
        Ui.bounce(connectCore);
        if (busy(VpnTunnelService.state)) {
            VpnTunnelService.requestStop(this);
            return;
        }
        if (store.profiles.isEmpty()) {
            toast(getString(R.string.msg_no_servers));
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
        Intent intent = new Intent(this, VpnTunnelService.class);
        intent.putExtra(VpnTunnelService.EXTRA_MODE, mode.key);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        VpnTunnelService.state = VpnTunnelService.STATE_CONNECTING;
        VpnTunnelService.detail = getString(R.string.svc_selecting);
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
            else toast(getString(R.string.msg_vpn_denied));
        }
    }

    private void applyUpdate() {
        if (applying) return;
        applying = true;
        render();
        ConfigSync.syncNow(true);
    }

    private void askNotify() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
    }

    private void pickTheme(boolean light) {
        if (Prefs.light(this) == light) return;
        Prefs.light(this, light);
        recreate();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void pickLanguage(String tag) {
        if (tag.equals(Prefs.locale(this))) return;
        Prefs.locale(this, tag);
        recreate();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void resetStats() {
        Prefs.resetCounters(this);
        flushedDown = 0;
        flushedUp = 0;
        renderStats();
        toast(getString(R.string.stats_reset_done));
    }

    private void onPingClicked() {
        if (pingRunning || !VpnTunnelService.STATE_CONNECTED.equals(VpnTunnelService.state)) return;
        pingRunning = true;
        pingBtn.setEnabled(false);
        pingBtn.setText(getString(R.string.ping_running));
        new Thread(new Runnable() {
            @Override public void run() {
                final long ms = VpnTunnelService.measureActivePing();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pingRunning = false;
                        pingBtn.setEnabled(true);
                        if (ms < 0) {
                            pingBtn.setText(getString(R.string.ping_no_answer));
                            toast(getString(R.string.msg_ping_failed));
                        } else {
                            pingBtn.setText(num(ms) + " " + getString(R.string.unit_ms));
                            Prefs.bestPing(MainActivity.this, ms);
                            renderStats();
                        }
                        Ui.bounce(pingBtn);
                    }
                });
            }
        }, "rayvpn-ping").start();
    }

    // ── rendering ──────────────────────────────────────────────────────────

    private String num(long value) {
        return Ui.num(value, persian);
    }

    private String num(int value) {
        return Ui.num(String.valueOf(value), persian);
    }

    private void render() {
        String state = VpnTunnelService.state;
        renderStatus(state);
        renderCore(state);
        renderHero(state);
        renderModes(state);
        renderPingChip(state);
        renderServers();
        renderUpdate();
        renderSettingsState();
        renderStats();
        animateForState(state);
    }

    private void renderStatus(String state) {
        int attr;
        String text;
        if (VpnTunnelService.STATE_CONNECTED.equals(state)) {
            text = getString(R.string.state_connected);
            attr = R.attr.rayAccent;
        } else if (VpnTunnelService.STATE_CONNECTING.equals(state)) {
            text = getString(R.string.state_connecting);
            attr = R.attr.rayWarn;
        } else if (VpnTunnelService.STATE_ERROR.equals(state)) {
            text = getString(R.string.state_error);
            attr = R.attr.rayDanger;
        } else {
            text = getString(R.string.state_ready);
            attr = R.attr.rayMuted;
        }
        int color = Ui.color(this, attr);
        if (lastPill == null || !text.equals(lastPill)) {
            lastPill = text;
            Ui.setTextSmooth(statusText, text);
            statusText.setScaleX(0.94f);
            statusText.setScaleY(0.94f);
            statusText.animate().scaleX(1f).scaleY(1f)
                    .setDuration(320).setInterpolator(Ui.SPRING).start();
        }
        if (color != lastPillColor) {
            lastPillColor = color;
            animateTextColor(statusText, color, 260);
            animateDotColor(color, 260);
        }
    }

    private void renderCore(String state) {
        int style;
        String label;
        if (VpnTunnelService.STATE_CONNECTED.equals(state)) {
            style = 2;
            label = getString(R.string.action_disconnect);
        } else if (VpnTunnelService.STATE_CONNECTING.equals(state)) {
            style = 1;
            label = getString(R.string.action_cancel);
        } else {
            style = 0;
            label = getString(R.string.action_connect);
        }
        if (!label.contentEquals(connectCore.getText())) {
            Ui.setTextSmooth(connectCore, label);
        }
        if (style == lastCoreStyle) return;
        lastCoreStyle = style;
        connectCore.setBackgroundResource(style == 2 ? R.drawable.core_on
                : style == 1 ? R.drawable.core_busy : R.drawable.core_idle);
        int tint = style == 2 ? Ui.color(this, R.attr.rayAccent)
                : style == 1 ? Ui.color(this, R.attr.rayWarn)
                : Ui.color(this, R.attr.rayTextDim);
        connectCore.setTextColor(tint);
        ringOuter.setBackgroundResource(style == 0 ? R.drawable.ring_hairline : R.drawable.ring_accent);
        ringA.setBackgroundResource(style == 1 ? R.drawable.ring_pulse : R.drawable.ring_pulse_on);
        ringB.setBackgroundResource(style == 1 ? R.drawable.ring_pulse : R.drawable.ring_pulse_on);
        connectCore.setScaleX(0.93f);
        connectCore.setScaleY(0.93f);
        connectCore.animate().scaleX(1f).scaleY(1f)
                .setDuration(420).setInterpolator(Ui.SPRING).start();
    }

    private void renderHero(String state) {
        String title;
        String sub;
        if (VpnTunnelService.STATE_CONNECTED.equals(state)) {
            String ip = VpnTunnelService.exitIp;
            if (ip != null && !ip.isEmpty()) {
                title = countryLabel();
                sub = ip;
            } else if (VpnTunnelService.exitFailed) {
                title = getString(R.string.country_unknown);
                sub = getString(R.string.ip_unknown);
            } else {
                title = getString(R.string.state_connected);
                sub = getString(R.string.ip_fetching);
            }
        } else if (VpnTunnelService.STATE_CONNECTING.equals(state)) {
            title = getString(R.string.hero_connecting);
            String detail = VpnTunnelService.detail;
            sub = detail == null || detail.isEmpty() ? getString(R.string.svc_selecting) : detail;
        } else if (VpnTunnelService.STATE_ERROR.equals(state)) {
            title = getString(R.string.hero_error);
            String detail = VpnTunnelService.detail;
            sub = detail == null ? "" : detail;
        } else {
            title = modeTitle(mode);
            sub = modeHint(mode);
        }
        String key = title + "|" + sub;
        if (key.equals(lastHeroKey)) return;
        lastHeroKey = key;
        Ui.setTextSmooth(heroTitle, title);
        Ui.setTextSmooth(heroSub, sub);
    }

    private String countryLabel() {
        String country = VpnTunnelService.exitCountry;
        if (country != null && !country.isEmpty()) return country;
        String code = VpnTunnelService.exitCode;
        if (code != null && !code.isEmpty()) return code;
        return getString(R.string.country_unknown);
    }

    private String modeTitle(ServerSelector.Mode m) {
        switch (m) {
            case FAST: return getString(R.string.mode_fast_title);
            case BEST: return getString(R.string.mode_best_title);
            default: return getString(R.string.mode_balance_title);
        }
    }

    private String modeHint(ServerSelector.Mode m) {
        switch (m) {
            case FAST: return getString(R.string.mode_fast_hint);
            case BEST: return getString(R.string.mode_best_hint);
            default: return getString(R.string.mode_balance_hint);
        }
    }

    private void renderModes(String state) {
        boolean locked = busy(state);
        style(modeFast, mode == ServerSelector.Mode.FAST, locked);
        style(modeBalance, mode == ServerSelector.Mode.BALANCE, locked);
        style(modeBest, mode == ServerSelector.Mode.BEST, locked);
    }

    private void style(RayText view, boolean selected, boolean locked) {
        view.setBackgroundResource(selected ? R.drawable.bg_segment_on : R.drawable.bg_segment);
        int color = Ui.color(this, selected ? R.attr.rayAccent : R.attr.rayTextDim);
        if (view.getCurrentTextColor() != color) animateTextColor(view, color, 220);
        view.setEnabled(!locked);
        view.animate().alpha(locked ? 0.45f : 1f).setDuration(220).setInterpolator(Ui.SOFT).start();
    }

    private void renderPingChip(String state) {
        boolean show = VpnTunnelService.STATE_CONNECTED.equals(state);
        if (show) {
            if (pingBtn.getVisibility() != View.VISIBLE) {
                pingBtn.setVisibility(View.VISIBLE);
                pingBtn.setAlpha(0f);
                pingBtn.setTranslationY(Ui.dp(this, 5));
                pingBtn.animate().alpha(1f).translationY(0f)
                        .setDuration(240).setInterpolator(Ui.EASE).start();
                if (!pingRunning) pingBtn.setText(getString(R.string.ping_action));
            }
        } else if (pingBtn.getVisibility() == View.VISIBLE) {
            pingBtn.animate().alpha(0f).setDuration(140)
                    .withEndAction(new Runnable() {
                        @Override public void run() { pingBtn.setVisibility(View.GONE); }
                    }).start();
        }
    }

    private void renderServers() {
        int count = store.profiles.size();
        String text = count == 0 ? getString(R.string.servers_empty)
                : getString(R.string.servers_ready, num(count));
        Ui.setTextSmooth(serversCount, text);
        Ui.setTextSmooth(refreshText, applying ? getString(R.string.refresh_running)
                : getString(R.string.refresh_action));
        setRefreshSpinning(applying);
    }

    private void renderUpdate() {
        boolean show = ConfigSync.updateAvailable;
        if (show != updateBarShown) {
            updateBarShown = show;
            updateBar.animate().cancel();
            if (show) {
                updateBar.setVisibility(View.VISIBLE);
                updateBar.setAlpha(0f);
                updateBar.setTranslationY(-Ui.dp(this, 8));
                updateBar.animate().alpha(1f).translationY(0f)
                        .setDuration(280).setInterpolator(Ui.EASE).start();
            } else {
                updateBar.animate().alpha(0f).translationY(-Ui.dp(this, 8)).setDuration(160)
                        .withEndAction(new Runnable() {
                            @Override public void run() { updateBar.setVisibility(View.GONE); }
                        }).start();
            }
        }
    }

    private void renderSettingsState() {
        boolean light = Prefs.light(this);
        themeDark.setBackgroundResource(light ? R.drawable.bg_segment : R.drawable.bg_segment_on);
        themeLight.setBackgroundResource(light ? R.drawable.bg_segment_on : R.drawable.bg_segment);
        themeDark.setTextColor(Ui.color(this, light ? R.attr.rayTextDim : R.attr.rayAccent));
        themeLight.setTextColor(Ui.color(this, light ? R.attr.rayAccent : R.attr.rayTextDim));

        boolean fa = "fa".equals(Prefs.locale(this));
        langFa.setBackgroundResource(fa ? R.drawable.bg_segment_on : R.drawable.bg_segment);
        langEn.setBackgroundResource(fa ? R.drawable.bg_segment : R.drawable.bg_segment_on);
        langFa.setTextColor(Ui.color(this, fa ? R.attr.rayAccent : R.attr.rayTextDim));
        langEn.setTextColor(Ui.color(this, fa ? R.attr.rayTextDim : R.attr.rayAccent));

        notifToggle.setChecked(Prefs.notifications(this), false);
    }

    /** Live numbers on the connect page and the aggregation on the stats page. */
    private void updateLive() {
        String state = VpnTunnelService.state;
        boolean connected = VpnTunnelService.STATE_CONNECTED.equals(state);
        if (connected) {
            long down = VpnTunnelService.bytesDown;
            long up = VpnTunnelService.bytesUp;
            long started = VpnTunnelService.sessionStartedAt;
            long elapsed = started <= 0 ? 0 : System.currentTimeMillis() - started;
            setLive(valDown, Ui.bytes(down, persian));
            setLive(valUp, Ui.bytes(up, persian));
            setLive(valTime, Ui.duration(elapsed, persian));
            if (graph.getVisibility() == View.VISIBLE) {
                graph.push(VpnTunnelService.speedDown, VpnTunnelService.speedUp);
            }
            setLive(speedDown, getString(R.string.stat_download) + " · "
                    + Ui.speed(VpnTunnelService.speedDown, persian));
            setLive(speedUp, getString(R.string.stat_upload) + " · "
                    + Ui.speed(VpnTunnelService.speedUp, persian));
        } else {
            setLive(valDown, getString(R.string.dash));
            setLive(valUp, getString(R.string.dash));
            setLive(valTime, getString(R.string.dash));
        }
        renderStats();
    }

    private void setLive(TextView view, String text) {
        if (view == null) return;
        if (!text.contentEquals(view.getText())) view.setText(text);
    }

    private void renderStats() {
        if (graph == null) return;
        syncSessionBase();
        boolean connected = VpnTunnelService.STATE_CONNECTED.equals(VpnTunnelService.state);
        long down = VpnTunnelService.bytesDown;
        long up = VpnTunnelService.bytesUp;
        long started = VpnTunnelService.sessionStartedAt;
        long elapsed = connected && started > 0 ? System.currentTimeMillis() - started : 0;

        setLive(sessDown, Ui.bytes(down, persian));
        setLive(sessUp, Ui.bytes(up, persian));
        setLive(sessTime, elapsed > 0 ? Ui.duration(elapsed, persian) : getString(R.string.dash));

        long totalDown = Prefs.totalDown(this) + (down - flushedDown);
        long totalUp = Prefs.totalUp(this) + (up - flushedUp);
        setLive(totalData, Ui.bytes(Math.max(0, totalDown) + Math.max(0, totalUp), persian));
        setLive(totalSessions, num(Prefs.sessions(this)));
        int best = Prefs.bestPing(this);
        setLive(totalPing, best < 0 ? getString(R.string.dash)
                : num(best) + " " + getString(R.string.unit_ms));

        if (connected) {
            if (!sessionCounted) {
                sessionCounted = true;
                Prefs.addSession(this);
            }
            if (System.currentTimeMillis() - lastFlush > 5000L) flushTotals();
        } else if (sessionCounted) {
            flushTotals();
        }
    }

    private void flushTotals() {
        lastFlush = System.currentTimeMillis();
        syncSessionBase();
        long down = VpnTunnelService.bytesDown - flushedDown;
        long up = VpnTunnelService.bytesUp - flushedUp;
        if (down > 0 || up > 0) {
            Prefs.addTotals(this, down, up);
            flushedDown = VpnTunnelService.bytesDown;
            flushedUp = VpnTunnelService.bytesUp;
        }
        if (!busy(VpnTunnelService.state)) sessionCounted = false;
    }

    /** Counters restart at zero with every session; forget the old baseline then. */
    private void syncSessionBase() {
        if (VpnTunnelService.bytesDown < flushedDown || VpnTunnelService.bytesUp < flushedUp) {
            flushedDown = 0L;
            flushedUp = 0L;
        }
    }

    // ── tabs ───────────────────────────────────────────────────────────────

    private void renderTabs(boolean animate) {
        View[] pages = {tabConnect, tabStats, tabSettings};
        View[] items = {navConnect, navStats, navSettings};
        ImageView[] icons = {navConnectIcon, navStatsIcon, navSettingsIcon};
        RayText[] labels = {navConnectText, navStatsText, navSettingsText};

        for (int i = 0; i < pages.length; i++) {
            boolean active = i == tab;
            View page = pages[i];
            if (active) {
                if (page.getVisibility() != View.VISIBLE) {
                    page.setVisibility(View.VISIBLE);
                    if (animate) {
                        page.setAlpha(0f);
                        page.setTranslationY(Ui.dp(this, 10));
                        page.animate().alpha(1f).translationY(0f)
                                .setDuration(240).setInterpolator(Ui.EASE).start();
                    } else {
                        page.setAlpha(1f);
                    }
                }
            } else {
                page.animate().cancel();
                page.setAlpha(1f);
                page.setVisibility(View.GONE);
            }
            int tint = Ui.color(this, active ? R.attr.rayAccent : R.attr.rayMuted);
            icons[i].setColorFilter(tint);
            int textColor = Ui.color(this, active ? R.attr.rayText : R.attr.rayMuted);
            if (labels[i].getCurrentTextColor() != textColor) animateTextColor(labels[i], textColor, 200);
            labels[i].weight(active ? Ui.W_SEMIBOLD : Ui.W_MEDIUM);
            icons[i].animate().scaleX(active ? 1.06f : 1f).scaleY(active ? 1.06f : 1f)
                    .setDuration(220).setInterpolator(Ui.SPRING).start();
        }
        items[tab].setSelected(true);
        if (tab == TAB_STATS && !animate) graph.clear();
    }

    // ── motion ─────────────────────────────────────────────────────────────

    private void animateForState(String state) {
        boolean connecting = VpnTunnelService.STATE_CONNECTING.equals(state);
        boolean connected = VpnTunnelService.STATE_CONNECTED.equals(state);

        setSpin(connecting);
        setBreathe(connecting || connected);
        setPulse(connecting || connected);

        if (lastState == null) {
            lastState = state;
            return;
        }
        if (!lastState.equals(state)) {
            lastState = state;
            if (connected) successPulse();
            if (!connected) graph.clear();
        }
    }

    private void setSpin(boolean on) {
        if (on == spinning) return;
        spinning = on;
        if (spin != null) {
            spin.cancel();
            spin = null;
        }
        if (on) {
            spinArc.setVisibility(View.VISIBLE);
            spinArc.setAlpha(0f);
            spinArc.animate().alpha(1f).setDuration(240).start();
            spin = ObjectAnimator.ofFloat(spinArc, "rotation", 0f, 360f);
            spin.setDuration(1250);
            spin.setRepeatCount(ValueAnimator.INFINITE);
            spin.setInterpolator(new LinearInterpolator());
            spin.start();
        } else {
            spinArc.setVisibility(View.GONE);
            spinArc.setRotation(0f);
        }
    }

    private void setBreathe(boolean on) {
        if (on == breathing) return;
        breathing = on;
        if (breathe != null) {
            breathe.cancel();
            breathe = null;
        }
        if (on) {
            breathe = ObjectAnimator.ofFloat(glow, "alpha", 0.45f, 0.95f);
            breathe.setDuration(1900);
            breathe.setRepeatCount(ValueAnimator.INFINITE);
            breathe.setRepeatMode(ValueAnimator.REVERSE);
            breathe.setInterpolator(Ui.SWING);
            breathe.start();
        } else {
            glow.setBackgroundResource(R.drawable.glow);
            glow.animate().alpha(0.75f).setDuration(300).setInterpolator(Ui.SOFT).start();
        }
    }

    private void setPulse(boolean on) {
        if (on == pulsing) return;
        pulsing = on;
        if (on) {
            startRing(ringA, 0);
            startRing(ringB, 620);
        } else {
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
    }

    private void startRing(final View ring, long delay) {
        ring.setVisibility(View.VISIBLE);
        ring.setScaleX(0.86f);
        ring.setScaleY(0.86f);
        ring.setAlpha(0.85f);
        ring.animate().scaleX(1.24f).scaleY(1.24f).alpha(0f)
                .setStartDelay(delay)
                .setDuration(2150)
                .setInterpolator(new LinearInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator animation) {
                        if (pulsing) startRing(ring, 0);
                    }
                }).start();
    }

    private void successPulse() {
        glow.setBackgroundResource(R.drawable.glow);
        glow.setAlpha(1f);
        glow.animate().alpha(0.75f).setDuration(760).setInterpolator(Ui.SOFT).start();
        connectCore.setScaleX(0.9f);
        connectCore.setScaleY(0.9f);
        connectCore.animate().scaleX(1f).scaleY(1f)
                .setDuration(520).setInterpolator(Ui.SPRING).start();
        ringOuter.setAlpha(0.4f);
        ringOuter.animate().alpha(1f).setDuration(700).setInterpolator(Ui.SOFT).start();
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
            refreshAnim.setDuration(950);
            refreshAnim.setRepeatCount(ValueAnimator.INFINITE);
            refreshAnim.setInterpolator(new LinearInterpolator());
            refreshAnim.start();
        } else {
            refreshIcon.animate().rotation(0f).setDuration(200).start();
        }
    }

    private void playEntrance() {
        header.setAlpha(0f);
        header.setTranslationY(-Ui.dp(this, 8));
        header.animate().alpha(1f).translationY(0f)
                .setDuration(420).setInterpolator(Ui.EASE).start();

        connectWrap.setAlpha(0f);
        connectWrap.setScaleX(0.94f);
        connectWrap.setScaleY(0.94f);
        connectWrap.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(90).setDuration(560).setInterpolator(Ui.EASE).start();

        View[] rest = {heroTitle, heroSub, findViewById(R.id.liveRow), findViewById(R.id.modeBar), footerHint};
        for (int i = 0; i < rest.length; i++) {
            Ui.fadeInUp(rest[i], 190 + i * 55L);
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
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(),
                Ui.color(this, R.attr.rayMuted), to);
        anim.setDuration(duration);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                int color = (Integer) a.getAnimatedValue();
                if (statusDot.getBackground() != null) statusDot.getBackground().setTint(color);
            }
        });
        anim.start();
    }

    private void toast(String text) {
        Ui.snack(root, text);
    }
}
