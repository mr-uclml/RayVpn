package app.rayvpn.client;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Tiny HTTP/1.1 GET client that goes through a loopback SOCKS5 inbound with
 * username/password. Used only by the app itself (speed test and exit IP lookup),
 * so it reads at most {@code maxBytes} of body and never follows redirects.
 */
final class SocksHttp {
    private static final int MAX_LINE = 8192;

    static final class Result {
        final int status;
        final byte[] body;
        final long bodyBytes;
        final long elapsedNs;

        Result(int status, byte[] body, long bodyBytes, long elapsedNs) {
            this.status = status;
            this.body = body;
            this.bodyBytes = bodyBytes;
            this.elapsedNs = elapsedNs;
        }
    }

    private SocksHttp() {}

    /**
     * @param tls       true for https (certificate and host name are verified)
     * @param elapsedNs time from sending the request until {@code maxBytes} arrived (or EOF)
     */
    static Result get(boolean tls, String host, int targetPort, String path,
                      int socksPort, String user, String pass,
                      int maxBytes, int timeoutMs) throws IOException {
        Socket raw = new Socket();
        Socket sock = null;
        try {
            raw.connect(new InetSocketAddress("127.0.0.1", socksPort), timeoutMs);
            raw.setSoTimeout(timeoutMs);
            socks5Connect(raw, host, targetPort, user, pass);
            sock = raw;
            if (tls) {
                SSLSocket ssl;
                try {
                    SSLSocketFactory factory = (SSLSocketFactory) SSLContext.getDefault().getSocketFactory();
                    ssl = (SSLSocket) factory.createSocket(raw, host, targetPort, true);
                } catch (java.security.NoSuchAlgorithmException e) {
                    throw new IOException("TLS unavailable", e);
                }
                SSLParameters params = ssl.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(params);
                ssl.setUseClientMode(true);
                ssl.setSoTimeout(timeoutMs);
                ssl.startHandshake();
                sock = ssl;
            }

            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "User-Agent: RayVPN\r\n"
                    + "Accept: */*\r\n"
                    + "Accept-Encoding: identity\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = sock.getOutputStream();
            long start = System.nanoTime();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = new BufferedInputStream(sock.getInputStream(), 16384);
            String statusLine = readLine(in);
            int status = parseStatus(statusLine);
            long contentLength = -1;
            boolean chunked = false;
            while (true) {
                String line = readLine(in);
                if (line.isEmpty()) break;
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
                String value = line.substring(colon + 1).trim();
                if (name.equals("content-length")) {
                    try {
                        contentLength = Long.parseLong(value);
                    } catch (NumberFormatException ignored) {
                    }
                } else if (name.equals("transfer-encoding") && value.toLowerCase(Locale.US).contains("chunked")) {
                    chunked = true;
                }
            }

            ByteArrayOutputStream store = new ByteArrayOutputStream();
            long total;
            if (chunked) {
                total = readChunked(in, maxBytes, store);
            } else {
                total = readPlain(in, contentLength, maxBytes, store);
            }
            long elapsed = System.nanoTime() - start;
            return new Result(status, store.toByteArray(), total, elapsed);
        } finally {
            if (sock != null && sock != raw) {
                try { sock.close(); } catch (IOException ignored) {}
            }
            try { raw.close(); } catch (IOException ignored) {}
        }
    }

    private static int parseStatus(String statusLine) throws IOException {
        String[] parts = statusLine.split(" ");
        if (parts.length < 2) throw new IOException("bad status line");
        try {
            return Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            throw new IOException("bad status code", e);
        }
    }

    private static long readPlain(InputStream in, long contentLength, long maxBytes, ByteArrayOutputStream store) throws IOException {
        byte[] buf = new byte[8192];
        long total = 0;
        while (total < maxBytes) {
            if (contentLength >= 0 && total >= contentLength) break;
            int want = (int) Math.min(buf.length, maxBytes - total);
            int n = in.read(buf, 0, want);
            if (n < 0) break;
            store.write(buf, 0, n);
            total += n;
        }
        return total;
    }

    private static long readChunked(InputStream in, long maxBytes, ByteArrayOutputStream store) throws IOException {
        long total = 0;
        byte[] buf = new byte[8192];
        while (total < maxBytes) {
            String sizeLine = readLine(in);
            int semi = sizeLine.indexOf(';');
            if (semi >= 0) sizeLine = sizeLine.substring(0, semi);
            long size;
            try {
                size = Long.parseLong(sizeLine.trim(), 16);
            } catch (NumberFormatException e) {
                throw new IOException("bad chunk size", e);
            }
            if (size == 0) break;
            long remaining = size;
            while (remaining > 0 && total < maxBytes) {
                int want = (int) Math.min(Math.min(buf.length, remaining), maxBytes - total);
                int n = in.read(buf, 0, want);
                if (n < 0) return total;
                store.write(buf, 0, n);
                total += n;
                remaining -= n;
            }
            if (remaining > 0 || total >= maxBytes) break;
            readLine(in); // CRLF after each chunk
        }
        return total;
    }

    /** SOCKS5 CONNECT by domain name, authenticated with username/password. */
    static void socks5Connect(Socket s, String host, int port, String user, String pass) throws IOException {
        OutputStream out = s.getOutputStream();
        InputStream in = s.getInputStream();

        out.write(new byte[]{0x05, 0x01, 0x02});
        out.flush();
        byte[] method = readFully(in, 2);
        if (method[0] != 0x05 || method[1] != 0x02) throw new IOException("socks: auth method rejected");

        byte[] u = user.getBytes(StandardCharsets.UTF_8);
        byte[] p = pass.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream auth = new ByteArrayOutputStream();
        auth.write(0x01);
        auth.write(u.length);
        auth.write(u, 0, u.length);
        auth.write(p.length);
        auth.write(p, 0, p.length);
        out.write(auth.toByteArray());
        out.flush();
        byte[] authReply = readFully(in, 2);
        if (authReply[1] != 0x00) throw new IOException("socks: auth failed");

        byte[] h = host.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream connect = new ByteArrayOutputStream();
        connect.write(0x05);
        connect.write(0x01);
        connect.write(0x00);
        connect.write(0x03);
        connect.write(h.length);
        connect.write(h, 0, h.length);
        connect.write((port >> 8) & 0xFF);
        connect.write(port & 0xFF);
        out.write(connect.toByteArray());
        out.flush();

        byte[] head = readFully(in, 4);
        if (head[0] != 0x05 || head[1] != 0x00) throw new IOException("socks: connect refused " + (head[1] & 0xFF));
        int atyp = head[3] & 0xFF;
        int skip;
        if (atyp == 0x01) skip = 4;
        else if (atyp == 0x04) skip = 16;
        else if (atyp == 0x03) skip = readFully(in, 1)[0] & 0xFF;
        else throw new IOException("socks: bad address type");
        readFully(in, skip + 2);
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
        return buf;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            if (b == '\n') break;
            if (line.size() >= MAX_LINE) throw new IOException("line too long");
            line.write(b);
        }
        byte[] bytes = line.toByteArray();
        int len = bytes.length;
        if (len > 0 && bytes[len - 1] == '\r') len--;
        return new String(bytes, 0, len, StandardCharsets.ISO_8859_1);
    }
}
