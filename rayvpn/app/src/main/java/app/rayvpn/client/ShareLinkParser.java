package app.rayvpn.client;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShareLinkParser {
    private static final Pattern LINK = Pattern.compile(
            "(?i)(vless|vmess|trojan|ss|shadowsocks|hysteria2|hy2)://[^\\s\"'`<>]+");

    private ShareLinkParser() {}

    public static class ParseException extends Exception {
        public ParseException(String message) { super(message); }
    }

    /** A subscription read result: usable profiles plus how many broken entries were skipped. */
    public static final class Report {
        public final List<Profile> profiles;
        public final int skipped;
        public final String firstError;

        Report(List<Profile> profiles, int skipped, String firstError) {
            this.profiles = profiles;
            this.skipped = skipped;
            this.firstError = firstError == null ? "" : firstError;
        }
    }

    /** Collects skipped entries while parsing one text. */
    private static final class Ctx {
        int skipped;
        String firstError = "";

        void skip(String why) {
            skipped++;
            if (firstError.isEmpty()) firstError = why == null ? "لینک خراب" : why;
        }
    }

    public static boolean isHttpUrl(String text) {
        String t = clean(text);
        if (t.indexOf('\n') >= 0 || t.indexOf(' ') >= 0) return false;
        String low = t.toLowerCase(Locale.US);
        if (!(low.startsWith("http://") || low.startsWith("https://"))) return false;
        return low.indexOf("://", 8) < 0;
    }

    public static List<Profile> parseText(String text) throws ParseException {
        return parseText(text, new Ctx());
    }

    private static List<Profile> parseText(String text, Ctx ctx) throws ParseException {
        String cleaned = clean(text);
        if (cleaned.isEmpty()) throw new ParseException("متنی وارد نشده");
        if (cleaned.startsWith("{")) return parseJson(cleaned);
        List<Profile> links = extractLinks(cleaned, ctx);
        if (!links.isEmpty()) return links;
        String decoded = tryBase64(cleaned);
        if (decoded != null && !decoded.equals(cleaned)) {
            if (decoded.trim().startsWith("{")) return parseJson(decoded.trim());
            links = extractLinks(decoded, ctx);
            if (!links.isEmpty()) return links;
            if (decoded.contains("proxies:")) return parseClash(decoded);
        }
        if (cleaned.contains("proxies:")) return parseClash(cleaned);
        if (!ctx.firstError.isEmpty()) throw new ParseException(ctx.firstError);
        throw new ParseException("لینک قابل‌خواندن پیدا نشد. vless / vmess / trojan / ss یا JSON یا اشتراک را بچسبانید.");
    }

    public static List<Profile> parseSubscription(String body, String sourceUrl) throws ParseException {
        return parseSubscriptionReport(body, sourceUrl).profiles;
    }

    /**
     * Reads a whole subscription. A broken entry is skipped and counted; only a body
     * with no usable entry at all is an error, so one bad line never drops the list.
     */
    public static Report parseSubscriptionReport(String body, String sourceUrl) throws ParseException {
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) throw new ParseException("پاسخ اشتراک خالی بود");
        String low = text.toLowerCase(Locale.US);
        if (low.contains("<html") || low.contains("<!doctype")) {
            throw new ParseException("سرور به‌جای لیست کانفیگ یک صفحهٔ وب برگرداند");
        }
        Ctx ctx = new Ctx();
        try {
            List<Profile> list = parseText(text, ctx);
            for (Profile p : list) {
                if (p.sourceLink == null) p.sourceLink = "";
            }
            return new Report(list, ctx.skipped, ctx.firstError);
        } catch (ParseException first) {
            String decoded = tryBase64(text.replace("\n", "").replace("\r", ""));
            if (decoded != null) {
                Ctx retry = new Ctx();
                try {
                    List<Profile> list = parseText(decoded, retry);
                    for (Profile p : list) {
                        if (p.sourceLink == null) p.sourceLink = "";
                    }
                    return new Report(list, retry.skipped, retry.firstError);
                } catch (ParseException ignored) {
                }
            }
            throw new ParseException(first.getMessage());
        }
    }

    private static List<Profile> parseJson(String text) throws ParseException {
        try {
            JSONObject root = new JSONObject(text);
            JSONArray outs = root.optJSONArray("outbounds");
            if (outs != null && outs.length() > 0) {
                JSONObject first = outs.optJSONObject(0);
                if (first != null && first.has("protocol")) {
                    Profile p = new Profile();
                    p.id = UUID.randomUUID().toString();
                    p.protocol = "raw";
                    p.rawJson = text;
                    p.name = first.optString("tag", "کانفیگ JSON");
                    if (p.name.isEmpty()) p.name = "کانفیگ JSON";
                    p.sourceLink = text;
                    p.addedAt = System.currentTimeMillis();
                    List<Profile> one = new ArrayList<>();
                    one.add(p);
                    return one;
                }
                if (first != null && (first.has("type") || first.has("server"))) {
                    return parseSingBox(outs);
                }
            }
            if (root.has("add") || root.has("id") || root.has("ps")) {
                return singleton(fromVmessJson(root, ""));
            }
        } catch (ParseException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseException("JSON نامعتبر است: " + e.getMessage());
        }
        throw new ParseException("این JSON کانفیگ Xray یا sing-box شناخته نشد");
    }

    private static List<Profile> parseSingBox(JSONArray outs) throws Exception {
        List<Profile> list = new ArrayList<>();
        for (int i = 0; i < outs.length(); i++) {
            try {
                JSONObject o = outs.optJSONObject(i);
                if (o == null) continue;
                String type = o.optString("type", "").toLowerCase(Locale.US);
                if (type.equals("direct") || type.equals("block") || type.equals("dns") || type.equals("selector")
                        || type.equals("urltest") || type.isEmpty()) continue;
                if (!o.has("server") && !o.has("server_port")) continue;
                Profile p = new Profile();
                p.id = UUID.randomUUID().toString();
                p.addedAt = System.currentTimeMillis();
                p.name = o.optString("tag", type);
                p.protocol = type.equals("shadowsocks") ? "shadowsocks" : type;
                if (type.equals("hysteria2")) p.protocol = "hysteria";
                p.address = o.optString("server");
                p.port = o.optInt("server_port", o.optInt("port"));
                p.user = first(o.optString("uuid"), o.optString("password"), o.optString("auth"));
                p.method = first(o.optString("method"), o.optString("security"));
                p.flow = o.optString("flow");
                JSONObject tls = o.optJSONObject("tls");
                if (tls != null && tls.optBoolean("enabled", true)) {
                    p.security = "tls";
                    p.sni = tls.optString("server_name");
                    p.allowInsecure = tls.optBoolean("insecure");
                    JSONObject utls = tls.optJSONObject("utls");
                    if (utls != null) p.fingerprint = utls.optString("fingerprint");
                    JSONObject reality = tls.optJSONObject("reality");
                    if (reality != null && reality.optBoolean("enabled", true)) {
                        p.security = "reality";
                        p.publicKey = reality.optString("public_key");
                        p.shortId = reality.optString("short_id");
                    }
                    JSONArray alpn = tls.optJSONArray("alpn");
                    if (alpn != null) {
                        StringBuilder b = new StringBuilder();
                        for (int a = 0; a < alpn.length(); a++) {
                            if (b.length() > 0) b.append(",");
                            b.append(alpn.optString(a));
                        }
                        p.alpn = b.toString();
                    }
                }
                JSONObject transport = o.optJSONObject("transport");
                if (transport != null) {
                    p.network = transport.optString("type", "tcp");
                    p.path = transport.optString("path");
                    p.serviceName = transport.optString("service_name");
                    JSONObject headers = transport.optJSONObject("headers");
                    if (headers != null) p.hostHeader = headers.optString("Host");
                } else {
                    p.network = "tcp";
                }
                if (p.name == null || p.name.isEmpty()) p.name = p.protocol + " " + p.address;
                list.add(p);
            } catch (RuntimeException broken) {
                // one broken outbound must not drop the rest of the list
            }
        }
        if (list.isEmpty()) throw new ParseException("در JSON خروجی قابل‌استفاده‌ای نبود");
        return list;
    }

    /** Parses every link on its own: a broken one is counted and skipped, the rest are kept. */
    private static List<Profile> extractLinks(String text, Ctx ctx) {
        List<Profile> list = new ArrayList<>();
        Matcher m = LINK.matcher(text);
        while (m.find()) {
            try {
                list.add(parseOne(m.group()));
            } catch (ParseException e) {
                ctx.skip(e.getMessage());
            } catch (RuntimeException e) {
                ctx.skip("لینک خراب: " + e.getMessage());
            }
        }
        return list;
    }

    public static Profile parseOne(String raw) throws ParseException {
        String link = clean(raw).replace("\n", "").replace("\r", "");
        int scheme = link.indexOf("://");
        if (scheme < 0) throw new ParseException("لینک ناقص است");
        String proto = link.substring(0, scheme).toLowerCase(Locale.US);
        String rest = link.substring(scheme + 3);
        if (proto.equals("vmess")) return parseVmess(rest);
        if (proto.equals("ss") || proto.equals("shadowsocks")) return parseSs(rest);
        if (proto.equals("vless") || proto.equals("trojan") || proto.equals("hysteria2") || proto.equals("hy2")) {
            return parseStandard(proto, rest);
        }
        throw new ParseException("پروتکل پشتیبانی نمی‌شود: " + proto);
    }

    private static Profile parseVmess(String rest) throws ParseException {
        String payload = rest;
        int hash = payload.indexOf('#');
        if (hash >= 0) payload = payload.substring(0, hash);
        String json = tryBase64(payload);
        if (json == null) throw new ParseException("vmess را نتوانستیم بخوانیم");
        json = json.trim();
        if (!json.startsWith("{")) throw new ParseException("vmess قالب شناخته‌شده‌ای ندارد");
        try {
            return fromVmessJson(new JSONObject(json), rest);
        } catch (Exception e) {
            throw new ParseException("vmess نامعتبر است: " + e.getMessage());
        }
    }

    private static Profile fromVmessJson(JSONObject o, String source) throws ParseException {
        Profile p = new Profile();
        p.id = UUID.randomUUID().toString();
        p.protocol = "vmess";
        p.name = first(o.optString("ps"), o.optString("remark"), "VMess");
        p.address = first(o.optString("add"), o.optString("address"));
        p.port = parsePort(o.opt("port"));
        p.user = first(o.optString("id"), o.optString("uuid"));
        p.method = first(o.optString("scy"), o.optString("security"), "auto");
        p.network = first(o.optString("net"), "tcp");
        p.headerType = o.optString("type");
        if ("http".equalsIgnoreCase(p.headerType) && (p.network.isEmpty() || "tcp".equals(p.network))) {
            // header obfuscation, network stays tcp
        } else if ("none".equalsIgnoreCase(p.headerType)) {
            p.headerType = "";
        }
        p.hostHeader = firstHost(o.optString("host"));
        p.path = o.optString("path");
        p.sni = o.optString("sni");
        p.alpn = o.optString("alpn");
        p.fingerprint = o.optString("fp");
        p.publicKey = o.optString("pbk");
        p.shortId = o.optString("sid");
        p.spiderX = o.optString("spx");
        String tls = o.optString("tls");
        if ("reality".equalsIgnoreCase(tls)) p.security = "reality";
        else if ("tls".equalsIgnoreCase(tls)) p.security = "tls";
        else p.security = "none";
        p.allowInsecure = truthy(o.optString("allowInsecure")) || truthy(o.optString("insecure"));
        p.serviceName = o.optString("serviceName");
        p.mode = o.optString("mode");
        p.seed = o.optString("seed");
        p.sourceLink = source.startsWith("vmess://") ? source : "vmess://" + source;
        p.addedAt = System.currentTimeMillis();
        if (p.address.isEmpty() || p.port <= 0 || p.user.isEmpty()) {
            throw new ParseException("vmess آدرس، پورت یا UUID ندارد");
        }
        if (p.name.isEmpty()) p.name = "VMess " + p.address;
        return p;
    }

    private static Profile parseStandard(String proto, String rest) throws ParseException {
        String fragment = "";
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            fragment = urlDecode(rest.substring(hash + 1));
            rest = rest.substring(0, hash);
        }
        String query = "";
        int q = rest.indexOf('?');
        if (q >= 0) {
            query = rest.substring(q + 1);
            rest = rest.substring(0, q);
        }
        int at = rest.lastIndexOf('@');
        if (at < 0) throw new ParseException("لینک " + proto + " ناقص است");
        String user = urlDecode(rest.substring(0, at));
        String hostport = rest.substring(at + 1);
        HostPort hp = splitHostPort(hostport);
        Map<String, String> params = parseQuery(query);

        Profile p = new Profile();
        p.id = UUID.randomUUID().toString();
        p.addedAt = System.currentTimeMillis();
        p.sourceLink = proto + "://" + user + "@" + hostport + (query.isEmpty() ? "" : "?" + query);
        if (!fragment.isEmpty()) p.sourceLink += "#" + fragment;
        p.user = user;
        p.address = hp.host;
        p.port = hp.port;
        p.name = fragment.isEmpty() ? proto.toUpperCase(Locale.US) + " " + hp.host : fragment;

        if (proto.equals("vless")) {
            p.protocol = "vless";
            p.method = params.getOrDefault("encryption", "none");
            p.flow = params.getOrDefault("flow", "");
            p.network = params.getOrDefault("type", "tcp");
            p.security = params.getOrDefault("security", "none");
        } else if (proto.equals("trojan")) {
            p.protocol = "trojan";
            p.network = params.getOrDefault("type", "tcp");
            p.security = params.getOrDefault("security", "tls");
        } else {
            p.protocol = "hysteria";
            p.network = "hysteria";
            p.security = "tls";
            if (params.containsKey("obfs") && !params.get("obfs").isEmpty()) {
                throw new ParseException("obfs های Hysteria در این نسخه پشتیبانی نمی‌شود");
            }
        }
        applyCommonParams(p, params);
        if (p.address.isEmpty() || p.port <= 0) throw new ParseException("آدرس یا پورت لینک نامعتبر است");
        return p;
    }

    private static void applyCommonParams(Profile p, Map<String, String> params) {
        p.sni = first(params.get("sni"), params.get("peer"), params.get("serverName"));
        p.hostHeader = firstHost(first(params.get("host"), params.get("authority")));
        p.path = first(params.get("path"), params.get("serviceName"));
        p.serviceName = params.getOrDefault("serviceName", "");
        if (p.serviceName.isEmpty() && "grpc".equalsIgnoreCase(p.network)) p.serviceName = strip(p.path);
        p.headerType = params.getOrDefault("headerType", "");
        p.fingerprint = first(params.get("fp"), params.get("fingerprint"));
        p.publicKey = first(params.get("pbk"), params.get("publicKey"));
        p.shortId = params.getOrDefault("sid", "");
        p.spiderX = params.getOrDefault("spx", "");
        p.alpn = params.getOrDefault("alpn", "");
        p.mode = params.getOrDefault("mode", "");
        p.seed = params.getOrDefault("seed", "");
        p.extra = params.getOrDefault("extra", "");
        p.ech = params.getOrDefault("ech", "");
        p.pinned = first(params.get("pcs"), params.get("pinSHA256"));
        p.mldsa = params.getOrDefault("pqv", "");
        p.allowInsecure = truthy(params.get("allowInsecure")) || truthy(params.get("insecure")) || "1".equals(params.get("allowInsecure"));
        if ("none".equalsIgnoreCase(p.headerType)) p.headerType = "";
        if (p.network == null || p.network.isEmpty()) p.network = "tcp";
    }

    private static Profile parseSs(String rest) throws ParseException {
        String fragment = "";
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            fragment = urlDecode(rest.substring(hash + 1));
            rest = rest.substring(0, hash);
        }
        String query = "";
        int q = rest.indexOf('?');
        if (q >= 0) {
            query = rest.substring(q + 1);
            rest = rest.substring(0, q);
        }
        Map<String, String> params = parseQuery(query);
        if (params.containsKey("plugin") && !params.get("plugin").isEmpty()) {
            throw new ParseException("افزونهٔ Shadowsocks (plugin) پشتیبانی نمی‌شود");
        }
        String method;
        String password;
        String host;
        int port;
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            String userinfo = urlDecode(rest.substring(0, at));
            HostPort hp = splitHostPort(rest.substring(at + 1));
            host = hp.host;
            port = hp.port;
            String decodedUser = userinfo.contains(":") ? userinfo : tryBase64(userinfo);
            if (decodedUser == null || !decodedUser.contains(":")) {
                throw new ParseException("لینک Shadowsocks را نتوانستیم بخوانیم");
            }
            int colon = decodedUser.indexOf(':');
            method = decodedUser.substring(0, colon);
            password = decodedUser.substring(colon + 1);
        } else {
            String decoded = tryBase64(rest);
            if (decoded == null) throw new ParseException("لینک Shadowsocks نامعتبر است");
            int at2 = decoded.lastIndexOf('@');
            if (at2 < 0 || !decoded.substring(0, at2).contains(":")) {
                throw new ParseException("لینک Shadowsocks نامعتبر است");
            }
            int colon = decoded.indexOf(':');
            method = decoded.substring(0, colon);
            String after = decoded.substring(colon + 1);
            at2 = after.lastIndexOf('@');
            password = after.substring(0, at2);
            HostPort hp = splitHostPort(after.substring(at2 + 1));
            host = hp.host;
            port = hp.port;
        }
        Profile p = new Profile();
        p.id = UUID.randomUUID().toString();
        p.protocol = "shadowsocks";
        p.method = method;
        p.user = password;
        p.address = host;
        p.port = port;
        p.network = "tcp";
        p.security = "none";
        p.name = fragment.isEmpty() ? "SS " + host : fragment;
        p.sourceLink = "ss://" + rest;
        p.addedAt = System.currentTimeMillis();
        if (host.isEmpty() || port <= 0 || password.isEmpty()) {
            throw new ParseException("Shadowsocks آدرس یا رمز ندارد");
        }
        return p;
    }

    private static List<Profile> parseClash(String yaml) throws ParseException {
        String[] lines = yaml.split("\n");
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals("proxies:") || lines[i].trim().startsWith("proxies:")) {
                start = i + 1;
                break;
            }
        }
        if (start < 0) throw new ParseException("لیست proxies در فایل Clash پیدا نشد");
        List<Map<String, String>> items = new ArrayList<>();
        Map<String, String> cur = null;
        String nest = "";
        for (int i = start; i < lines.length; i++) {
            String raw = lines[i];
            if (raw.trim().isEmpty() || raw.trim().startsWith("#")) continue;
            if (!raw.startsWith(" ") && !raw.startsWith("\t") && raw.contains(":") && !raw.trim().startsWith("-")) {
                break;
            }
            String trimmed = raw.trim();
            if (trimmed.startsWith("- ")) {
                if (cur != null) items.add(cur);
                cur = new LinkedHashMap<>();
                nest = "";
                String body = trimmed.substring(2).trim();
                if (body.startsWith("{") && body.endsWith("}")) {
                    parseInline(cur, body.substring(1, body.length() - 1));
                    items.add(cur);
                    cur = null;
                    continue;
                }
                putKv(cur, "", body);
                continue;
            }
            if (cur == null) continue;
            int indent = raw.indexOf(trimmed);
            if (trimmed.endsWith(":") && !trimmed.contains(": ")) {
                nest = trimmed.substring(0, trimmed.length() - 1);
                continue;
            }
            String prefix = indent > 4 ? nest : "";
            if (indent <= 4) nest = "";
            putKv(cur, indent > 4 ? nest : "", trimmed);
        }
        if (cur != null) items.add(cur);
        List<Profile> list = new ArrayList<>();
        for (Map<String, String> item : items) {
            String type = item.getOrDefault("type", "").toLowerCase(Locale.US);
            if (type.isEmpty() || type.equals("ssr") || type.equals("direct")) continue;
            Profile p = new Profile();
            p.id = UUID.randomUUID().toString();
            p.addedAt = System.currentTimeMillis();
            p.name = unquote(item.getOrDefault("name", type));
            p.address = unquote(item.getOrDefault("server", ""));
            try {
                p.port = parsePort(item.get("port"));
            } catch (ParseException badPort) {
                continue;
            }
            if (type.equals("ss")) type = "shadowsocks";
            if (type.equals("hysteria2") || type.equals("hy2")) type = "hysteria";
            p.protocol = type;
            p.user = unquote(first(item.get("uuid"), item.get("password"), item.get("auth")));
            p.method = unquote(item.getOrDefault("cipher", item.getOrDefault("method", "")));
            p.network = unquote(item.getOrDefault("network", "tcp"));
            p.flow = unquote(item.getOrDefault("flow", ""));
            p.sni = unquote(first(item.get("sni"), item.get("servername")));
            p.hostHeader = unquote(first(item.get("ws-opts.headers.Host"), item.get("host")));
            p.path = unquote(first(item.get("ws-opts.path"), item.get("http-opts.path"), item.get("path")));
            p.serviceName = unquote(item.getOrDefault("grpc-opts.grpc-service-name", ""));
            p.publicKey = unquote(item.getOrDefault("reality-opts.public-key", ""));
            p.shortId = unquote(item.getOrDefault("reality-opts.short-id", ""));
            p.fingerprint = unquote(first(item.get("client-fingerprint"), item.get("fingerprint")));
            boolean tls = truthy(item.get("tls")) || "tls".equalsIgnoreCase(item.get("security"));
            if (!p.publicKey.isEmpty()) p.security = "reality";
            else if (tls || type.equals("trojan") || type.equals("hysteria")) p.security = "tls";
            else p.security = "none";
            p.allowInsecure = truthy(item.get("skip-cert-verify"));
            if (p.address.isEmpty() || p.port <= 0) continue;
            if (!type.equals("vless") && !type.equals("vmess") && !type.equals("trojan")
                    && !type.equals("shadowsocks") && !type.equals("hysteria")) continue;
            list.add(p);
        }
        if (list.isEmpty()) throw new ParseException("از اشتراک Clash کانفیگ قابل‌استفاده‌ای خوانده نشد");
        return list;
    }

    private static void parseInline(Map<String, String> cur, String body) {
        String[] parts = body.split(",");
        for (String part : parts) putKv(cur, "", part.trim());
    }

    private static void putKv(Map<String, String> cur, String prefix, String body) {
        int colon = body.indexOf(':');
        if (colon <= 0) return;
        String key = body.substring(0, colon).trim();
        String val = body.substring(colon + 1).trim();
        if (key.startsWith("- ")) key = key.substring(2).trim();
        String full = prefix == null || prefix.isEmpty() ? key : prefix + "." + key;
        cur.put(full, val);
        if (key.equals("Host") || key.equals("host")) cur.put(prefix == null || prefix.isEmpty() ? "host" : prefix + ".Host", val);
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return map;
        String[] parts = query.split("&");
        for (String part : parts) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            String v = eq < 0 ? "" : part.substring(eq + 1);
            map.put(urlDecode(k), urlDecode(v));
        }
        return map;
    }

    private static HostPort splitHostPort(String hostport) throws ParseException {
        String hp = hostport.trim();
        while (hp.endsWith("/")) hp = hp.substring(0, hp.length() - 1);
        if (hp.startsWith("[")) {
            int end = hp.indexOf(']');
            if (end < 0) throw new ParseException("IPv6 نامعتبر است");
            String host = hp.substring(1, end);
            String portPart = hp.substring(end + 1);
            if (!portPart.startsWith(":")) throw new ParseException("پورت IPv6 پیدا نشد");
            return new HostPort(host, parsePort(portPart.substring(1)));
        }
        int colon = hp.lastIndexOf(':');
        if (colon < 0) throw new ParseException("پورت پیدا نشد");
        return new HostPort(hp.substring(0, colon), parsePort(hp.substring(colon + 1)));
    }

    private static int parsePort(Object value) throws ParseException {
        if (value == null) throw new ParseException("پورت خالی است");
        String s = String.valueOf(value).trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) s = s.substring(1, s.length() - 1);
        try {
            int port = Integer.parseInt(s);
            if (port < 1 || port > 65535) throw new ParseException("پورت نامعتبر است");
            return port;
        } catch (NumberFormatException e) {
            throw new ParseException("پورت نامعتبر است");
        }
    }

    private static String tryBase64(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replace("\n", "").replace("\r", "").replace(" ", "");
        if (s.isEmpty()) return null;
        s = s.replace('-', '+').replace('_', '/');
        int mod = s.length() % 4;
        if (mod != 0) s = s + "====".substring(mod);
        try {
            byte[] data = android.util.Base64.decode(s, android.util.Base64.DEFAULT);
            String text = new String(data, java.nio.charset.StandardCharsets.UTF_8);
            if (text.indexOf('\uFFFD') >= 0 && text.length() < 8) return null;
            return text;
        } catch (Exception e) {
            return null;
        }
    }

    private static String urlDecode(String s) {
        if (s == null) return "";
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String clean(String text) {
        if (text == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\uFEFF' || c == '\u200e' || c == '\u200f') continue;
            if (c >= '۰' && c <= '۹') b.append((char) ('0' + (c - '۰')));
            else if (c >= '٠' && c <= '٩') b.append((char) ('0' + (c - '٠')));
            else b.append(c);
        }
        return b.toString().trim();
    }

    private static boolean truthy(String s) {
        if (s == null) return false;
        String v = unquote(s).toLowerCase(Locale.US);
        return v.equals("1") || v.equals("true") || v.equals("yes");
    }

    private static String unquote(String s) {
        if (s == null) return "";
        String v = s.trim();
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            if (v.length() >= 2) v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static String firstHost(String host) {
        String h = unquote(host);
        int comma = h.indexOf(',');
        if (comma > 0) h = h.substring(0, comma).trim();
        return h;
    }

    private static String strip(String s) {
        String v = s == null ? "" : s;
        while (v.startsWith("/")) v = v.substring(1);
        return v;
    }

    private static String first(String... values) {
        if (values == null) return "";
        for (String v : values) {
            if (v != null && !unquote(v).isEmpty()) return unquote(v);
        }
        return "";
    }

    private static List<Profile> singleton(Profile p) {
        List<Profile> list = new ArrayList<>();
        list.add(p);
        return list;
    }

    private static final class HostPort {
        final String host;
        final int port;
        HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
}
