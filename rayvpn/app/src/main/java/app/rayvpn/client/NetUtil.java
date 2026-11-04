package app.rayvpn.client;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public final class NetUtil {
    private NetUtil() {}

    public static final class Result {
        public final int code;
        public final String body;
        public final String error;
        public final String etag;

        public Result(int code, String body, String error) {
            this(code, body, error, "");
        }

        public Result(int code, String body, String error, String etag) {
            this.code = code;
            this.body = body;
            this.error = error;
            this.etag = etag == null ? "" : etag;
        }
    }

    public static Result get(String url) {
        return exchange(url, false, null, null, null, 15000, 25000);
    }

    public static Result getFresh(String url) {
        String sep = url.contains("?") ? "&" : "?";
        return exchange(url + sep + "t=" + System.currentTimeMillis(), true, null, null, null, 15000, 25000);
    }

    public static Result getNoCache(String url) {
        return exchange(url, true, null, null, null, 15000, 25000);
    }

    public static Result watch(String url, String ifNoneMatch, String accept, String userAgent) {
        return exchange(url, true, ifNoneMatch, accept, userAgent, 8000, 10000);
    }

    private static Result exchange(String url, boolean fresh, String ifNoneMatch, String accept, String userAgent, int connectMs, int readMs) {
        HttpURLConnection conn = null;
        try {
            trustAll();
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setInstanceFollowRedirects(true);
            conn.setUseCaches(!fresh);
            conn.setRequestProperty("User-Agent", userAgent == null || userAgent.isEmpty() ? "RayVPN" : userAgent);
            conn.setRequestProperty("Accept", accept == null || accept.isEmpty() ? "*/*" : accept);
            if (fresh) {
                conn.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0");
                conn.setRequestProperty("Pragma", "no-cache");
            }
            if (ifNoneMatch != null && !ifNoneMatch.isEmpty()) {
                conn.setRequestProperty("If-None-Match", ifNoneMatch);
            }
            int code = conn.getResponseCode();
            String etag = conn.getHeaderField("ETag");
            if (code == 304) return new Result(304, "", null, etag);
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = in == null ? "" : read(in);
            if (code >= 400) return new Result(code, body, "HTTP " + code, etag);
            return new Result(code, body, null, etag);
        } catch (Exception e) {
            return new Result(0, "", e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    private static volatile boolean trusted;

    private static void trustAll() {
        if (trusted) return;
        try {
            TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
                    return true;
                }
            });
            trusted = true;
        } catch (Exception ignored) {
        }
    }
}
