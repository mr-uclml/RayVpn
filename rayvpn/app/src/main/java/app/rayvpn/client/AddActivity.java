package app.rayvpn.client;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;


import java.util.List;

public class AddActivity extends Activity {
    private static final String[] PROTO_LABELS = {"VLESS", "VMess", "Trojan", "Shadowsocks", "Hysteria2"};
    private static final String[] PROTO_VALUES = {"vless", "vmess", "trojan", "shadowsocks", "hysteria"};
    private static final String[] NET_LABELS = {"TCP", "WebSocket", "gRPC", "HTTPUpgrade", "XHTTP", "mKCP"};
    private static final String[] NET_VALUES = {"tcp", "ws", "grpc", "httpupgrade", "xhttp", "kcp"};
    private static final String[] SEC_LABELS = {"بدون لایهٔ امنیتی", "TLS", "REALITY"};
    private static final String[] SEC_VALUES = {"none", "tls", "reality"};

    private EditText pasteBox;
    private ConfigStore store;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add);
        store = new ConfigStore(this);
        store.load();
        pasteBox = findViewById(R.id.pasteBox);
        setupSpinner(R.id.fProtocol, PROTO_LABELS);
        setupSpinner(R.id.fNetwork, NET_LABELS);
        setupSpinner(R.id.fSecurity, SEC_LABELS);

        findViewById(R.id.clipBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pasteClipboard(); }
        });
        findViewById(R.id.importBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { importText(); }
        });
        findViewById(R.id.saveManual).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveManual(); }
        });
    }

    private void setupSpinner(int id, String[] labels) {
        Spinner spinner = findViewById(id);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        spinner.setAdapter(adapter);
    }

    private void pasteClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            toast("کلیپ‌بورد خالی است");
            return;
        }
        ClipData data = cm.getPrimaryClip();
        if (data == null || data.getItemCount() == 0) {
            toast("کلیپ‌بورد خالی است");
            return;
        }
        CharSequence text = data.getItemAt(0).coerceToText(this);
        pasteBox.setText(text == null ? "" : text);
    }

    private void importText() {
        final String text = pasteBox.getText().toString().trim();
        if (text.isEmpty()) {
            toast("اول لینک یا آدرس اشتراک را بچسبانید");
            return;
        }
        if (ShareLinkParser.isHttpUrl(text)) {
            toast("در حال دریافت اشتراک…");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    NetUtil.Result result = NetUtil.get(text);
                    if (result.error != null) {
                        fail(result.error);
                        return;
                    }
                    try {
                        final List<Profile> list = ShareLinkParser.parseSubscription(result.body, text);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                ConfigStore.Subscription sub = store.upsertSubscription(text, list.size() + " کانفیگ");
                                store.replaceSubscription(sub.id, list);
                                toast(list.size() + " کانفیگ از اشتراک اضافه شد");
                                finish();
                            }
                        });
                    } catch (Exception e) {
                        fail(e.getMessage() == null ? "اشتراک خوانده نشد" : e.getMessage());
                    }
                }
            }).start();
            return;
        }
        try {
            List<Profile> list = ShareLinkParser.parseText(text);
            store.addAll(list, null);
            toast(list.size() + " کانفیگ اضافه شد");
            finish();
        } catch (Exception e) {
            toast(e.getMessage() == null ? "خوانده نشد" : e.getMessage());
        }
    }

    private void saveManual() {
        try {
            Profile p = new Profile();
            p.name = text(R.id.fName);
            p.protocol = PROTO_VALUES[spinner(R.id.fProtocol)];
            p.address = text(R.id.fAddress);
            String port = text(R.id.fPort);
            p.port = port.isEmpty() ? 0 : Integer.parseInt(port);
            p.user = text(R.id.fUser);
            p.method = text(R.id.fMethod);
            p.network = NET_VALUES[spinner(R.id.fNetwork)];
            p.security = SEC_VALUES[spinner(R.id.fSecurity)];
            p.sni = text(R.id.fSni);
            p.hostHeader = text(R.id.fHost);
            p.path = text(R.id.fPath);
            p.serviceName = "grpc".equals(p.network) ? p.path : "";
            p.flow = text(R.id.fFlow);
            p.publicKey = text(R.id.fPbk);
            p.shortId = text(R.id.fSid);
            p.fingerprint = text(R.id.fFp);
            p.allowInsecure = ((CheckBox) findViewById(R.id.fInsecure)).isChecked();
            if (p.name.isEmpty()) p.name = p.protocol.toUpperCase() + " " + p.address;
            XrayConfigBuilder.proxyOutbound(p);
            List<Profile> one = new java.util.ArrayList<>();
            one.add(p);
            store.addAll(one, null);
            toast("کانفیگ ذخیره شد");
            finish();
        } catch (Exception e) {
            toast(e.getMessage() == null ? "فرم نامعتبر است" : e.getMessage());
        }
    }

    private String text(int id) {
        return ((EditText) findViewById(id)).getText().toString().trim();
    }

    private int spinner(int id) {
        return ((Spinner) findViewById(id)).getSelectedItemPosition();
    }

    private void fail(final String message) {
        runOnUiThread(new Runnable() {
            @Override public void run() { toast(message); }
        });
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
