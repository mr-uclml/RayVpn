package app.rayvpn.client;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Public IP and country of the tunnel's exit, looked up through the running
 * tunnel (via its loopback SOCKS inbound). Only these two values are shown to the user.
 */
final class ExitInfo {
    static final class Info {
        final String ip;
        final String country;
        final String code;

        Info(String ip, String country, String code) {
            this.ip = ip;
            this.country = country;
            this.code = code;
        }
    }

    private static final int LOOKUP_TIMEOUT_MS = 8000;
    private static final int MAX_BODY = 16 * 1024;

    private ExitInfo() {}

    /** @return the exit info, or null when no provider answered. */
    static Info lookup(int socksPort, String user, String pass) {
        Info info = viaHttps(socksPort, user, pass);
        if (info == null) info = viaHttp(socksPort, user, pass);
        return info;
    }

    private static Info viaHttps(int socksPort, String user, String pass) {
        try {
            SocksHttp.Result r = SocksHttp.get(true, "ipapi.co", 443, "/json/",
                    socksPort, user, pass, MAX_BODY, LOOKUP_TIMEOUT_MS);
            if (r.status != 200) return null;
            JSONObject o = new JSONObject(new String(r.body, StandardCharsets.UTF_8));
            String ip = o.optString("ip", "");
            if (ip.isEmpty()) return null;
            return new Info(ip, o.optString("country_name", ""), o.optString("country_code", ""));
        } catch (Exception e) {
            return null;
        }
    }

    private static Info viaHttp(int socksPort, String user, String pass) {
        try {
            SocksHttp.Result r = SocksHttp.get(false, "ip-api.com", 80,
                    "/json/?fields=status,country,countryCode,query",
                    socksPort, user, pass, MAX_BODY, LOOKUP_TIMEOUT_MS);
            if (r.status != 200) return null;
            JSONObject o = new JSONObject(new String(r.body, StandardCharsets.UTF_8));
            if (!"success".equals(o.optString("status"))) return null;
            String ip = o.optString("query", "");
            if (ip.isEmpty()) return null;
            return new Info(ip, o.optString("country", ""), o.optString("countryCode", ""));
        } catch (Exception e) {
            return null;
        }
    }
}
