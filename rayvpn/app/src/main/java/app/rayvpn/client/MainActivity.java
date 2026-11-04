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
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

/**
 * Main screen. The user never sees the server list: they pick one of three
 * connection modes (fast / balance / best) and the service does the selection.
 * After connecting only the exit country and IP are shown.
 */
public class MainActivity extends Activity {
    private static final int REQ_VPN = 42;
    private static final int REQ_NOTIF = 43;
    private static final String PREFS = "rayvpn_prefs";
    private static final String KEY_MODE = "server_mode";

    private static final int C_ACCENT = 0xFF35F0B0;
    private static final int C_MUTED = 0xFF8B9ABA;
    private static final int C_WARN = 0xFFFFC46B;
    private static final int C_DANGER = 0xFFFF5C7A;
    private static final int C_TEXT = 0xFFF3F7FF;
    private static final int C_TEXT_DIM = 0xFFC7D1E6;
    private static final int C_INK = 0xFF032018;

    private ConfigStore store;
    private ServerSelector.Mode mode = ServerSelector.Mode.BALANCE;
    private ServerSelector.Mode shownMode;
    private boolean shownBusy;
    private String lastHeroKey = "";

    private View root;
    private View header;
    private View hero;
    private View actions;
    private View glow;
    private View spinRing;
    private View statusDot;
    private View refreshBtn;
    private View refreshIcon;
    private View updateBar;
    private View ringA;
    private View ringB;
    private TextView connectBtn;
    private TextView statusPill;
    private TextView serverName;
    private TextView detailText;
    private TextView versionText;
    private TextView modeFast;
    private TextView modeBalance;
    private TextView modeBest;

    private boolean applying;
    private boolean askedNotif;
    private boolean connectPulse;
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
    private boolean updateBarShown;
    private ObjectAnimator breatheAnim;
    private ObjectAnimator spinAnim;
    private ObjectAnimator refreshAnim;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            render();
        }
    };

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
            if (removed) toast("سرور قبلی حذف شد");
            else if (changed) toast(intent.getIntExtra("count", 0) + " سرور به‌روز شد");
            else if (available) {
                toast("آپدیت آمد. می‌توانی به‌روزرسانی کنی");
                askNotify();
            } else if (error != null && !error.isEmpty() && (manual || store.profiles.isEmpty())) toast(error);
            else if (manual) toast("تغییری نبود");
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
        mode = ServerSelector.Mode.fromKey(
                getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_MODE, "balance"));

        root = findViewById(R.id.root);
        header = findViewById(R.id.header);
        hero = findViewById(R.id.hero);
        actions = findViewById(R.id.actions);
        glow = findViewById(R.id.glow);
        spinRing = findViewById(R.id.spinRing);
        statusDot = findViewById(R.id.statusDot);
        refreshBtn = findViewById(R.id.refreshBtn);
        refreshIcon = findViewById(R.id.refreshIcon);
        updateBar = findViewById(R.id.updateBar);
        ringA = findViewById(R.id.ringA);
        ringB = findViewById(R.id.ringB);
        connectBtn = findViewById(R.id.connectBtn);
        statusPill = findViewById(R.id.statusPill);
        serverName = findViewById(R.id.serverName);
        detailText = findViewById(R.id.detailText);
        versionText = findViewById(R.id.versionText);
        modeFast = findViewById(R.id.modeFast);
        modeBalance = findViewById(R.id.modeBalance);
        modeBest = findViewById(R.id.modeBest);

        glow.setAlpha(0.7f);
        lastBtnBg = R.drawable.bg_connect;
        lastBtnColor = C_INK;
        lastGlowRes = R.drawable.bg_glow;
        if (statusDot.getBackground() != null) statusDot.getBackground().setTint(C_MUTED);

        Ui.applyFont(root);
        Ui.pressable(connectBtn);
        Ui.pressable(refreshBtn);
        Ui.pressable(findViewById(R.id.updateBtn));
        Ui.pressable(modeFast);
        Ui.pressable(modeBalance);
        Ui.pressable(modeBest);
        root.post(new Runnable() {
            @Override public void run() { playEntrance(); }
        });

        connectBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onConnectClicked(); }
        });
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applyUpdate(); }
        });
        findViewById(R.id.updateBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applyUpdate(); }
        });
        modeFast.setOnClickListener(modeClick(ServerSelector.Mode.FAST));
        modeBalance.setOnClickListener(modeClick(ServerSelector.Mode.BALANCE));
        modeBest.setOnClickListener(modeClick(ServerSelector.Mode.BEST));
    }

    private View.OnClickListener modeClick(final ServerSelector.Mode picked) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.bounce(v);
                onModeClicked(picked);
            }
        };
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

    // ── actions ────────────────────────────────────────────────────────────

    private static boolean busy(String st) {
        return VpnTunnelService.STATE_CONNECTING.equals(st) || VpnTunnelService.STATE_CONNECTED.equals(st);
    }

    private void onModeClicked(ServerSelector.Mode picked) {
        if (busy(VpnTunnelService.state)) {
            toast("اول اتصال فعلی را قطع کن");
            return;
        }
        if (picked == mode) return;
        mode = picked;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_MODE, picked.key).apply();
        render();
    }

    private void onConnectClicked() {
        if (busy(VpnTunnelService.state)) {
            VpnTunnelService.requestStop(this);
            return;
        }
        if (store.profiles.isEmpty()) {
            toast("هنوز سروری دریافت نشده");
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
        Intent i = new Intent(this, VpnTunnelService.class);
        i.putExtra(VpnTunnelService.EXTRA_MODE, mode.key);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        VpnTunnelService.state = VpnTunnelService.STATE_CONNECTING;
        VpnTunnelService.detail = "انتخاب سرور…";
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

    // ── rendering ──────────────────────────────────────────────────────────

    private void render() {
        boolean empty = store.profiles.isEmpty();
        if (applying) Ui.setTextSmooth(versionText, "در حال به‌روزرسانی…");
        else if (ConfigSync.updateAvailable) Ui.setTextSmooth(versionText, "نسخهٔ تازه آماده است");
        else if (empty) Ui.setTextSmooth(versionText, "سروری دریافت نشده");
        else Ui.setTextSmooth(versionText, persianCount(store.profiles.size()) + " سرور آماده");

        setUpdateBarVisible(ConfigSync.updateAvailable);
        setRefreshSpinning(applying);

        String st = VpnTunnelService.state;
        updateStatusUi(st);
        handleStatePulse(st);
        renderHero(st);
        renderModes(st);
    }

    /** Hero shows the mode while idle, the progress while connecting, and country + IP once connected. */
    private void renderHero(String st) {
        String title;
        String sub;
        if (VpnTunnelService.STATE_CONNECTED.equals(st)) {
            String ip = VpnTunnelService.exitIp;
            if (ip != null && !ip.isEmpty()) {
                title = countryLabel();
                sub = ip;
            } else if (VpnTunnelService.exitFailed) {
                title = "کشور نامشخص";
                sub = "IP نامشخص";
            } else {
                title = "در حال بررسی";
                sub = "در حال دریافت IP…";
            }
        } else if (VpnTunnelService.STATE_CONNECTING.equals(st)) {
            title = "در حال اتصال";
            String d = VpnTunnelService.detail;
            sub = d == null || d.isEmpty() ? "انتخاب سرور…" : d;
        } else if (VpnTunnelService.STATE_ERROR.equals(st)) {
            title = "اتصال برقرار نشد";
            String d = VpnTunnelService.detail;
            sub = d == null ? "" : d;
        } else {
            title = modeTitle(mode);
            sub = modeHint(mode);
        }
        String key = title + "|" + sub;
        if (key.equals(lastHeroKey)) return;
        lastHeroKey = key;
        Ui.setTextSmooth(serverName, title);
        Ui.setTextSmooth(detailText, sub);
    }

    private static String countryLabel() {
        String country = VpnTunnelService.exitCountry;
        if (country != null && !country.isEmpty()) return country;
        String code = VpnTunnelService.exitCode;
        if (code != null && !code.isEmpty()) return code;
        return "کشور نامشخص";
    }

    private static String modeTitle(ServerSelector.Mode m) {
        switch (m) {
            case FAST: return "حالت سریع";
            case BEST: return "حالت بهترین";
            default: return "حالت متعادل";
        }
    }

    private static String modeHint(ServerSelector.Mode m) {
        switch (m) {
            case FAST: return "اولین سروری که جواب بدهد";
            case BEST: return "۱۰ سرور با کمترین پینگ، با تست سرعت";
            default: return "سروری با میانگین پینگ زیر ۲۰۰ میلی‌ثانیه";
        }
    }

    private void renderModes(String st) {
        boolean locked = busy(st);
        if (mode == shownMode && locked == shownBusy) return;
        shownMode = mode;
        shownBusy = locked;
        styleMode(modeFast, ServerSelector.Mode.FAST, locked);
        styleMode(modeBalance, ServerSelector.Mode.BALANCE, locked);
        styleMode(modeBest, ServerSelector.Mode.BEST, locked);
    }

    private void styleMode(TextView view, ServerSelector.Mode m, boolean locked) {
        boolean selected = m == mode;
        view.setBackgroundResource(selected ? R.drawable.bg_btn_primary : R.drawable.bg_btn_ghost);
        animateTextColor(view, selected ? C_INK : C_TEXT_DIM, 240);
        view.setEnabled(!locked);
        view.animate().alpha(locked ? 0.5f : 1f).setDuration(240).setInterpolator(Ui.SOFT).start();
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

    private void toast(String text) {
        Ui.snack(root, text);
    }
}
