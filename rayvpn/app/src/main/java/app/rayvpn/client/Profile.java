package app.rayvpn.client;

import org.json.JSONException;
import org.json.JSONObject;

public class Profile {
    public String id = "";
    public String name = "";
    public String protocol = "";
    public String address = "";
    public int port;
    public String user = "";
    public String method = "";
    public String flow = "";
    public String network = "tcp";
    public String security = "";
    public String sni = "";
    public String hostHeader = "";
    public String path = "";
    public String alpn = "";
    public String fingerprint = "";
    public String publicKey = "";
    public String shortId = "";
    public String spiderX = "";
    public String headerType = "";
    public String serviceName = "";
    public String mode = "";
    public String seed = "";
    public String extra = "";
    public boolean allowInsecure;
    public String ech = "";
    public String pinned = "";
    public String mldsa = "";
    public String rawJson = "";
    public String sourceLink = "";
    public String subscriptionId = "";
    public long addedAt;
    public long pingMs = -2;

    public String subtitle() {
        if ("raw".equals(protocol)) {
            return "JSON خام Xray";
        }
        String kind = protocol == null ? "" : protocol.toUpperCase();
        String via = security == null || security.isEmpty() || "none".equals(security)
                ? (network == null ? "" : network)
                : security;
        return kind + " · " + via + " · " + address + ":" + port;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("protocol", protocol);
        o.put("address", address);
        o.put("port", port);
        o.put("user", user);
        o.put("method", method);
        o.put("flow", flow);
        o.put("network", network);
        o.put("security", security);
        o.put("sni", sni);
        o.put("hostHeader", hostHeader);
        o.put("path", path);
        o.put("alpn", alpn);
        o.put("fingerprint", fingerprint);
        o.put("publicKey", publicKey);
        o.put("shortId", shortId);
        o.put("spiderX", spiderX);
        o.put("headerType", headerType);
        o.put("serviceName", serviceName);
        o.put("mode", mode);
        o.put("seed", seed);
        o.put("extra", extra);
        o.put("allowInsecure", allowInsecure);
        o.put("ech", ech);
        o.put("pinned", pinned);
        o.put("mldsa", mldsa);
        o.put("rawJson", rawJson);
        o.put("sourceLink", sourceLink);
        o.put("subscriptionId", subscriptionId);
        o.put("addedAt", addedAt);
        o.put("pingMs", pingMs);
        return o;
    }

    public static Profile fromJson(JSONObject o) {
        Profile p = new Profile();
        p.id = o.optString("id");
        p.name = o.optString("name");
        p.protocol = o.optString("protocol");
        p.address = o.optString("address");
        p.port = o.optInt("port");
        p.user = o.optString("user");
        p.method = o.optString("method");
        p.flow = o.optString("flow");
        p.network = o.optString("network", "tcp");
        p.security = o.optString("security");
        p.sni = o.optString("sni");
        p.hostHeader = o.optString("hostHeader");
        p.path = o.optString("path");
        p.alpn = o.optString("alpn");
        p.fingerprint = o.optString("fingerprint");
        p.publicKey = o.optString("publicKey");
        p.shortId = o.optString("shortId");
        p.spiderX = o.optString("spiderX");
        p.headerType = o.optString("headerType");
        p.serviceName = o.optString("serviceName");
        p.mode = o.optString("mode");
        p.seed = o.optString("seed");
        p.extra = o.optString("extra");
        p.allowInsecure = o.optBoolean("allowInsecure");
        p.ech = o.optString("ech");
        p.pinned = o.optString("pinned");
        p.mldsa = o.optString("mldsa");
        p.rawJson = o.optString("rawJson");
        p.sourceLink = o.optString("sourceLink");
        p.subscriptionId = o.optString("subscriptionId");
        p.addedAt = o.optLong("addedAt");
        p.pingMs = o.optLong("pingMs", -2);
        return p;
    }
}
