package app.rayvpn.client;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
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

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

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

public class MainActivity extends AppCompatActivity {
    private static final int REQ_VPN = 42;
    private static final int REQ_NOTIF = 43;

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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        askedNotif = true;
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
    }

    private void onPingClicked() {
        if (pinging) {
            pingCancel = true;
            pingAllBtn.setText("در حال توقف…");
            return;
        }
        if (store.profiles.isEmpty()) {
            toast("هنوز سروری نیست");
            return;
        }
        pinging = true;
        pingCancel = false;
        testingIds.clear();
        for (Profile p : store.profiles) {
            if (p.id != null) testingIds.add(p.id);
        }
        pingProgress = "۰/" + store.profiles.size();
        updatePingButton();
        adapter.notifyDataSetChanged();
        final List<Profile> targets = new ArrayList<>(store.profiles);
        new Thread(new Runnable() {
            @Override
            public void run() {
                phaseTcp(targets);
                phaseReal(targets);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        pinging = false;
                        pingProgress = "";
                        testingIds.clear();
                        store.save();
                        render();
                    }
                });
            }
        }).start();
    }

    private void phaseTcp(List<Profile> targets) {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        final AtomicInteger done = new AtomicInteger();
        List<Future<?>> jobs = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            final Profile p = targets.get(i);
            jobs.add(pool.submit(new Runnable() {
                @Override
                public void run() {
                    if (pingCancel) return;
                    final long ms = tcpPing(p.address, p.port, 1800);
                    final int n = done.incrementAndGet();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (ms >= 0 && testingIds.contains(p.id)) p.pingMs = ms;
                            pingProgress = n + "/" + targets.size();
                            adapter.notifyDataSetChanged();
                            updatePingButton();
                        }
                    });
                }
            }));
        }
        for (Future<?> job : jobs) {
            try { job.get(5, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
        pool.shutdownNow();
    }

    private void phaseReal(List<Profile> targets) {
        for (int i = 0; i < targets.size(); i++) {
            if (pingCancel) return;
            final Profile p = targets.get(i);
            final int n = i + 1;
            final long real = realDelay(p);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    p.pingMs = real;
                    if (p.id != null) testingIds.remove(p.id);
                    pingProgress = "دقیق " + n + "/" + targets.size();
                    adapter.notifyDataSetChanged();
                    updatePingButton();
                }
            });
        }
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
        for (Profile p : store.profiles) if (p.pingMs != -2) any = true;
        if (!any) {
            toast("اول پینگ همه را بزنید");
            return;
        }
        store.sortByPing();
        render();
        toast("از سریع‌ترین به کندترین");
    }

    private void onConnectClicked() {
        if (VpnTunnelService.STATE_CONNECTED.equals(VpnTunnelService.state)
                || VpnTunnelService.STATE_CONNECTING.equals(VpnTunnelService.state)) {
            VpnTunnelService.requestStop(this);
            return;
        }
        Profile p = store.selected();
        if (p == null) {
            toast("یک سرور از لیست انتخاب کنید");
            return;
        }
        try {
            XrayConfigBuilder.buildTunnel(p);
        } catch (Exception e) {
            toast(e.getMessage() == null ? "این سرور قابل اتصال نیست" : e.getMessage());
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
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
        emptyText.setVisibility(store.profiles.isEmpty() ? View.VISIBLE : View.GONE);
        emptyText.setText("سروری دریافت نشد.\nدکمهٔ تازه‌سازی را بزنید.");
        Profile selected = store.selected();
        if (selected == null) serverName.setText("سروری انتخاب نشده");
        else serverName.setText(selected.name == null || selected.name.isEmpty() ? selected.address : selected.name);

        if (applying) versionText.setText("در حال به‌روزرسانی…");
        else if (ConfigSync.updateAvailable) versionText.setText("آپدیت آماده است");
        else if (store.profiles.isEmpty()) versionText.setText("لیست خالی است");
        else versionText.setText(store.profiles.size() + " سرور");
        updateBar.setVisibility(ConfigSync.updateAvailable ? View.VISIBLE : View.GONE);
        updatePingButton();

        String st = VpnTunnelService.state;
        syncConnectAnim(VpnTunnelService.STATE_CONNECTING.equals(st));
        if (VpnTunnelService.STATE_CONNECTED.equals(st)) {
            statusPill.setText("متصل");
            statusPill.setTextColor(0xFF2EE6A6);
            connectBtn.setText("قطع");
            connectBtn.setTextColor(0xFFFFFFFF);
            connectBtn.setBackgroundResource(R.drawable.bg_connect_off);
            detailText.setText(VpnTunnelService.detail == null || VpnTunnelService.detail.isEmpty()
                    ? "تونل روشن است" : VpnTunnelService.detail);
        } else if (VpnTunnelService.STATE_CONNECTING.equals(st)) {
            statusPill.setText("در حال اتصال");
            statusPill.setTextColor(0xFFF5C15A);
            connectBtn.setText("اتصال");
            connectBtn.setTextColor(0xFF062018);
            connectBtn.setBackgroundResource(R.drawable.bg_connect_wait);
            detailText.setText("در حال اتصال…");
        } else if (VpnTunnelService.STATE_ERROR.equals(st)) {
            statusPill.setText("خطا");
            statusPill.setTextColor(0xFFFF5C7A);
            connectBtn.setText("اتصال");
            connectBtn.setTextColor(0xFF062018);
            connectBtn.setBackgroundResource(R.drawable.bg_connect);
            detailText.setText(VpnTunnelService.detail == null ? "اتصال برقرار نشد" : VpnTunnelService.detail);
        } else {
            statusPill.setText("قطع");
            statusPill.setTextColor(0xFF93A0BD);
            connectBtn.setText("اتصال");
            connectBtn.setTextColor(0xFF062018);
            connectBtn.setBackgroundResource(R.drawable.bg_connect);
            if (selected != null) detailText.setText(selected.subtitle() + pingSuffix(selected));
            else detailText.setText("یک سرور را از لیست انتخاب کنید");
        }
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
        if (pinging) pingAllBtn.setText(pingProgress.isEmpty() ? "پینگ…" : pingProgress);
        else pingAllBtn.setText("پینگ همه");
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private class ProfileAdapter extends BaseAdapter {
        @Override public int getCount() { return store.profiles.size(); }
        @Override public Object getItem(int position) { return store.profiles.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) row = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_profile, parent, false);
            Profile p = store.profiles.get(position);
            TextView name = row.findViewById(R.id.name);
            TextView sub = row.findViewById(R.id.sub);
            TextView ping = row.findViewById(R.id.ping);
            name.setText(p.name == null || p.name.isEmpty() ? p.address : p.name);
            sub.setText(p.subtitle());
            boolean on = p.id != null && p.id.equals(store.selectedId);
            row.setBackgroundResource(on ? R.drawable.bg_card_on : R.drawable.bg_card);
            if (p.id != null && testingIds.contains(p.id) && p.pingMs < 0) {
                ping.setText("…");
                ping.setTextColor(0xFFF5C15A);
            } else if (p.pingMs >= 0) {
                ping.setText(p.pingMs + " ms");
                ping.setTextColor(p.pingMs < 180 ? 0xFF2EE6A6 : p.pingMs < 400 ? 0xFFF5C15A : 0xFFFF5C7A);
            } else if (p.pingMs == -1) {
                ping.setText("قطع");
                ping.setTextColor(0xFFFF5C7A);
            } else {
                ping.setText("—");
                ping.setTextColor(0xFF93A0BD);
            }
            return row;
        }
    }
}
