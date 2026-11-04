package app.rayvpn.client;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

public final class XrayConfigBuilder {
    private XrayConfigBuilder() {}

    /**
     * Loopback SOCKS5 inbound protected by a random user/password. The app uses it
     * from inside its own process (speed test, exit IP lookup); it is never exposed.
     */
    public static final class LocalSocks {
        public final int port;
        public final String user;
        public final String pass;

        public LocalSocks(int port, String user, String pass) {
            this.port = port;
            this.user = user;
            this.pass = pass;
        }
    }

    public static String buildTunnel(Profile p) throws Exception {
        return buildTunnel(p, null);
    }

    public static String buildTunnel(Profile p, LocalSocks socks) throws Exception {
        if (p == null) throw new IllegalArgumentException("کانفیگی انتخاب نشده");
        if ("raw".equals(p.protocol)) {
            if (p.rawJson == null || p.rawJson.trim().isEmpty()) {
                throw new IllegalArgumentException("JSON خام خالی است");
            }
            JSONObject root = new JSONObject(p.rawJson);
            injectTun(root);
            if (socks != null) {
                JSONArray inbounds = root.getJSONArray("inbounds");
                inbounds.put(socksInbound(socks, "api-in"));
            }
            return root.toString();
        }
        JSONObject root = new JSONObject();
        JSONObject log = new JSONObject();
        log.put("loglevel", "warning");
        root.put("log", log);

        JSONObject dns = new JSONObject();
        dns.put("queryStrategy", "UseIPv4");
        JSONArray servers = new JSONArray();
        servers.put("https://1.1.1.1/dns-query");
        servers.put("1.1.1.1");
        dns.put("servers", servers);
        root.put("dns", dns);

        JSONArray inbounds = new JSONArray();
        inbounds.put(tunInbound());
        if (socks != null) inbounds.put(socksInbound(socks, "api-in"));
        root.put("inbounds", inbounds);

        JSONArray outbounds = new JSONArray();
        outbounds.put(proxyOutbound(p));
        JSONObject dnsOut = new JSONObject();
        dnsOut.put("protocol", "dns");
        dnsOut.put("tag", "dns-out");
        outbounds.put(dnsOut);
        JSONObject direct = new JSONObject();
        direct.put("protocol", "freedom");
        direct.put("tag", "direct");
        outbounds.put(direct);
        JSONObject block = new JSONObject();
        block.put("protocol", "blackhole");
        block.put("tag", "block");
        outbounds.put(block);
        root.put("outbounds", outbounds);

        JSONObject routing = new JSONObject();
        routing.put("domainStrategy", "AsIs");
        JSONArray rules = new JSONArray();
        if (socks != null) {
            // the app's own lookups go through the tunnel, never direct
            JSONObject apiRule = new JSONObject();
            apiRule.put("type", "field");
            apiRule.put("inboundTag", new JSONArray().put("api-in"));
            apiRule.put("outboundTag", "proxy");
            rules.put(apiRule);
        }
        JSONObject dnsRule = new JSONObject();
        dnsRule.put("type", "field");
        dnsRule.put("inboundTag", new JSONArray().put("tun-in"));
        dnsRule.put("port", "53");
        dnsRule.put("outboundTag", "dns-out");
        rules.put(dnsRule);
        JSONObject lan = new JSONObject();
        lan.put("type", "field");
        lan.put("outboundTag", "direct");
        lan.put("ip", new JSONArray()
                .put("10.0.0.0/8")
                .put("127.0.0.0/8")
                .put("172.16.0.0/12")
                .put("192.168.0.0/16")
                .put("169.254.0.0/16")
                .put("224.0.0.0/4")
                .put("255.255.255.255/32")
                .put("::1/128")
                .put("fc00::/7")
                .put("fe80::/10"));
        rules.put(lan);
        routing.put("rules", rules);
        root.put("routing", routing);
        return root.toString();
    }

    /**
     * Short-lived core used before the tunnel exists: only a loopback SOCKS inbound
     * plus the server outbound, so a speed test can run through this one config.
     */
    public static String buildProbe(Profile p, LocalSocks socks) throws Exception {
        JSONObject root;
        JSONArray inbounds = new JSONArray();
        inbounds.put(socksInbound(socks, "probe-in"));
        if ("raw".equals(p.protocol)) {
            if (p.rawJson == null || p.rawJson.trim().isEmpty()) {
                throw new IllegalArgumentException("JSON خام خالی است");
            }
            root = new JSONObject(p.rawJson);
            root.put("inbounds", inbounds);
            return root.toString();
        }
        root = new JSONObject();
        JSONObject log = new JSONObject();
        log.put("loglevel", "warning");
        root.put("log", log);

        JSONObject dns = new JSONObject();
        dns.put("queryStrategy", "UseIPv4");
        JSONArray servers = new JSONArray();
        servers.put("https://1.1.1.1/dns-query");
        servers.put("1.1.1.1");
        dns.put("servers", servers);
        root.put("dns", dns);

        root.put("inbounds", inbounds);
        JSONArray outs = new JSONArray();
        outs.put(proxyOutbound(p));
        root.put("outbounds", outs);
        return root.toString();
    }

    public static String buildDelay(Profile p) throws Exception {
        JSONObject root = new JSONObject();
        if ("raw".equals(p.protocol)) {
            root = new JSONObject(p.rawJson);
            JSONArray inbounds = root.optJSONArray("inbounds");
            if (inbounds != null) {
                JSONArray kept = new JSONArray();
                for (int i = 0; i < inbounds.length(); i++) {
                    JSONObject ib = inbounds.optJSONObject(i);
                    if (ib != null && !"tun".equalsIgnoreCase(ib.optString("protocol"))) kept.put(ib);
                }
                root.put("inbounds", kept);
            }
            return root.toString();
        }
        JSONArray inbounds = new JSONArray();
        JSONObject socks = new JSONObject();
        socks.put("tag", "socks-in");
        socks.put("listen", "127.0.0.1");
        socks.put("port", 20808);
        socks.put("protocol", "socks");
        JSONObject settings = new JSONObject();
        settings.put("udp", true);
        socks.put("settings", settings);
        inbounds.put(socks);
        root.put("inbounds", inbounds);
        JSONArray outs = new JSONArray();
        outs.put(proxyOutbound(p));
        root.put("outbounds", outs);
        return root.toString();
    }

    private static void injectTun(JSONObject root) throws JSONException {
        JSONArray inbounds = root.optJSONArray("inbounds");
        JSONArray merged = new JSONArray();
        merged.put(tunInbound());
        if (inbounds != null) {
            for (int i = 0; i < inbounds.length(); i++) {
                JSONObject ib = inbounds.optJSONObject(i);
                if (ib == null) continue;
                if ("tun".equalsIgnoreCase(ib.optString("protocol"))) continue;
                merged.put(ib);
            }
        }
        root.put("inbounds", merged);
        if (!root.has("log")) {
            JSONObject log = new JSONObject();
            log.put("loglevel", "warning");
            root.put("log", log);
        }
    }

    private static JSONObject socksInbound(LocalSocks s, String tag) throws JSONException {
        JSONObject ib = new JSONObject();
        ib.put("tag", tag);
        ib.put("listen", "127.0.0.1");
        ib.put("port", s.port);
        ib.put("protocol", "socks");
        JSONObject settings = new JSONObject();
        settings.put("auth", "password");
        settings.put("accounts", new JSONArray().put(new JSONObject().put("user", s.user).put("pass", s.pass)));
        settings.put("udp", false);
        ib.put("settings", settings);
        return ib;
    }

    private static JSONObject tunInbound() throws JSONException {
        JSONObject ib = new JSONObject();
        ib.put("tag", "tun-in");
        ib.put("port", 0);
        ib.put("protocol", "tun");
        JSONObject settings = new JSONObject();
        settings.put("name", "xray0");
        settings.put("mtu", 1500);
        ib.put("settings", settings);
        JSONObject sniff = new JSONObject();
        sniff.put("enabled", true);
        sniff.put("routeOnly", true);
        sniff.put("destOverride", new JSONArray().put("http").put("tls").put("quic"));
        ib.put("sniffing", sniff);
        return ib;
    }

    static JSONObject proxyOutbound(Profile p) throws JSONException {
        String proto = nz(p.protocol).toLowerCase(Locale.US);
        if (proto.isEmpty()) throw new JSONException("پروتکل مشخص نیست");
        if (p.address == null || p.address.trim().isEmpty()) throw new JSONException("آدرس سرور خالی است");
        if (p.port < 1 || p.port > 65535) throw new JSONException("پورت نامعتبر است");
        JSONObject o = new JSONObject();
        o.put("tag", "proxy");
        o.put("protocol", proto.equals("hy2") || proto.equals("hysteria2") ? "hysteria" : proto);
        JSONObject settings = new JSONObject();
        if (proto.equals("vless")) {
            if (nz(p.user).isEmpty()) throw new JSONException("UUID خالی است");
            settings.put("address", p.address.trim());
            settings.put("port", p.port);
            settings.put("id", p.user.trim());
            settings.put("encryption", nz(p.method).isEmpty() ? "none" : p.method.trim());
            if (!nz(p.flow).isEmpty()) settings.put("flow", p.flow.trim());
            o.put("streamSettings", stream(p));
        } else if (proto.equals("vmess")) {
            if (nz(p.user).isEmpty()) throw new JSONException("UUID خالی است");
            settings.put("address", p.address.trim());
            settings.put("port", p.port);
            settings.put("id", p.user.trim());
            settings.put("security", nz(p.method).isEmpty() ? "auto" : p.method.trim());
            o.put("streamSettings", stream(p));
        } else if (proto.equals("trojan")) {
            if (nz(p.user).isEmpty()) throw new JSONException("رمز Trojan خالی است");
            settings.put("address", p.address.trim());
            settings.put("port", p.port);
            settings.put("password", p.user);
            o.put("streamSettings", stream(p));
        } else if (proto.equals("shadowsocks") || proto.equals("ss")) {
            o.put("protocol", "shadowsocks");
            if (nz(p.user).isEmpty()) throw new JSONException("رمز Shadowsocks خالی است");
            settings.put("address", p.address.trim());
            settings.put("port", p.port);
            settings.put("method", nz(p.method).isEmpty() ? "chacha20-ietf-poly1305" : p.method.trim());
            settings.put("password", p.user);
            if (hasStream(p)) o.put("streamSettings", stream(p));
        } else if (proto.equals("hysteria") || proto.equals("hysteria2") || proto.equals("hy2")) {
            if (nz(p.user).isEmpty()) throw new JSONException("رمز Hysteria خالی است");
            settings.put("version", 2);
            settings.put("address", p.address.trim());
            settings.put("port", p.port);
            Profile copy = p;
            if (nz(copy.network).isEmpty() || copy.network.equals("tcp")) copy.network = "hysteria";
            if (nz(copy.security).isEmpty()) copy.security = "tls";
            o.put("streamSettings", stream(copy));
        } else {
            throw new JSONException("پروتکل پشتیبانی نمی‌شود: " + proto);
        }
        o.put("settings", settings);
        return o;
    }

    private static boolean hasStream(Profile p) {
        String net = nz(p.network).toLowerCase(Locale.US);
        String sec = nz(p.security).toLowerCase(Locale.US);
        return (!net.isEmpty() && !net.equals("tcp") && !net.equals("raw"))
                || sec.equals("tls") || sec.equals("reality");
    }

    static JSONObject stream(Profile p) throws JSONException {
        String net = nz(p.network).toLowerCase(Locale.US);
        if (net.isEmpty() || net.equals("raw")) net = "tcp";
        String mode = p.mode;
        if (net.equals("h2") || net.equals("http") || net.equals("quic")) {
            if (nz(mode).isEmpty()) mode = "stream-one";
            net = "xhttp";
        }
        if (net.equals("websocket")) net = "ws";
        if (net.equals("splithttp")) net = "xhttp";
        if (net.equals("mkcp")) net = "kcp";

        String sec = nz(p.security).toLowerCase(Locale.US);
        if (sec.equals("xtls")) sec = "tls";
        String proto = nz(p.protocol).toLowerCase(Locale.US);
        if (sec.isEmpty()) {
            if (proto.equals("trojan") || proto.equals("hysteria") || proto.equals("hysteria2") || proto.equals("hy2") || net.equals("hysteria")) {
                sec = "tls";
            } else {
                sec = "none";
            }
        }
        if (sec.equals("reality") && !(net.equals("tcp") || net.equals("xhttp") || net.equals("grpc"))) {
            throw new JSONException("REALITY فقط با TCP و XHTTP و gRPC کار می‌کند");
        }

        JSONObject s = new JSONObject();
        s.put("network", net);
        s.put("security", sec);
        if (sec.equals("tls")) s.put("tlsSettings", tls(p));
        if (sec.equals("reality")) s.put("realitySettings", reality(p));

        String path = nz(p.path).isEmpty() ? "/" : p.path;
        String host = first(p.hostHeader, p.sni);
        if (host.isEmpty() && !isIp(nz(p.address))) host = nz(p.address);

        if (net.equals("ws")) {
            JSONObject ws = new JSONObject();
            ws.put("path", path);
            if (!host.isEmpty()) {
                ws.put("host", host);
                JSONObject headers = new JSONObject();
                headers.put("Host", host);
                ws.put("headers", headers);
            }
            s.put("wsSettings", ws);
        } else if (net.equals("grpc")) {
            JSONObject g = new JSONObject();
            String svc = first(p.serviceName, stripSlash(p.path));
            if (!svc.isEmpty()) g.put("serviceName", svc);
            if (!host.isEmpty()) g.put("authority", host);
            if ("multi".equalsIgnoreCase(nz(p.mode)) || "gun".equalsIgnoreCase(nz(p.headerType))) {
                g.put("multiMode", true);
            }
            s.put("grpcSettings", g);
        } else if (net.equals("httpupgrade")) {
            JSONObject h = new JSONObject();
            h.put("path", path);
            if (!host.isEmpty()) h.put("host", host);
            s.put("httpupgradeSettings", h);
        } else if (net.equals("xhttp")) {
            JSONObject x = new JSONObject();
            x.put("path", path);
            if (!host.isEmpty()) x.put("host", host);
            x.put("mode", nz(mode).isEmpty() ? "auto" : mode);
            if (nz(p.extra).trim().startsWith("{")) {
                x.put("extra", new JSONObject(p.extra));
            }
            s.put("xhttpSettings", x);
        } else if (net.equals("kcp")) {
            JSONObject k = new JSONObject();
            JSONObject header = new JSONObject();
            header.put("type", nz(p.headerType).isEmpty() ? "none" : p.headerType);
            k.put("header", header);
            if (!nz(p.seed).isEmpty()) k.put("seed", p.seed);
            s.put("kcpSettings", k);
        } else if (net.equals("tcp") && "http".equalsIgnoreCase(nz(p.headerType))) {
            JSONObject tcp = new JSONObject();
            JSONObject header = new JSONObject();
            header.put("type", "http");
            JSONObject request = new JSONObject();
            request.put("path", new JSONArray().put(path));
            if (!host.isEmpty()) {
                JSONObject headers = new JSONObject();
                headers.put("Host", new JSONArray().put(host));
                request.put("headers", headers);
            }
            header.put("request", request);
            tcp.put("header", header);
            s.put("tcpSettings", tcp);
        } else if (net.equals("hysteria")) {
            JSONObject h = new JSONObject();
            h.put("version", 2);
            h.put("auth", nz(p.user));
            s.put("hysteriaSettings", h);
        }
        return s;
    }

    private static JSONObject tls(Profile p) throws JSONException {
        JSONObject t = new JSONObject();
        String sni = first(p.sni, p.hostHeader);
        if (sni.isEmpty() && !isIp(nz(p.address))) sni = nz(p.address);
        if (!sni.isEmpty()) t.put("serverName", sni);
        t.put("fingerprint", first(p.fingerprint, "chrome"));
        if (!nz(p.alpn).isEmpty()) {
            JSONArray a = new JSONArray();
            for (String part : p.alpn.split(",")) {
                if (!part.trim().isEmpty()) a.put(part.trim());
            }
            if (a.length() > 0) t.put("alpn", a);
        }
        if (p.allowInsecure) t.put("allowInsecure", true);
        if (!nz(p.ech).isEmpty()) t.put("echConfigList", p.ech);
        if (!nz(p.pinned).isEmpty()) t.put("pinnedPeerCertSha256", p.pinned);
        return t;
    }

    private static JSONObject reality(Profile p) throws JSONException {
        String sni = first(p.sni, p.hostHeader);
        if (sni.isEmpty()) throw new JSONException("کانفیگ REALITY فیلد SNI ندارد");
        if (nz(p.publicKey).isEmpty()) throw new JSONException("کانفیگ REALITY کلید عمومی ندارد");
        JSONObject r = new JSONObject();
        r.put("serverName", sni);
        r.put("fingerprint", first(p.fingerprint, "chrome"));
        r.put("publicKey", p.publicKey.trim());
        if (p.shortId != null) r.put("shortId", p.shortId.trim());
        if (!nz(p.spiderX).isEmpty()) r.put("spiderX", p.spiderX);
        if (!nz(p.mldsa).isEmpty()) r.put("mldsa65Verify", p.mldsa);
        return r;
    }

    private static String stripSlash(String s) {
        String v = nz(s);
        while (v.startsWith("/")) v = v.substring(1);
        return v;
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }

    static String first(String a, String b) {
        if (a != null && !a.trim().isEmpty()) return a.trim();
        if (b != null && !b.trim().isEmpty()) return b.trim();
        return "";
    }

    static boolean isIp(String host) {
        if (host == null) return false;
        String h = host.trim();
        if (h.startsWith("[") || h.contains(":")) return true;
        return h.matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
    }
}
