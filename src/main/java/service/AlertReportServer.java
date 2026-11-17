package service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Serves the alert reports, so the "Open logs" button in Teams opens the report from this application over HTTPS and
 * the page can be downloaded.
 *
 * <pre>
 * GET /alerts/reports/&lt;file&gt;.html?k=&lt;key&gt;            the report in the browser
 * GET /alerts/reports/&lt;file&gt;.html?k=&lt;key&gt;&amp;download=1   the same file as a download
 * </pre>
 *
 * The key is a signature of the file name made with a secret that only this installation knows, so a link can not be
 * guessed or turned into a link for another file. Only plain {@code *.html} names inside the report folder are served.
 */
public final class AlertReportServer implements AutoCloseable {

    private static final Logger log = Logger.getLogger(AlertReportServer.class.getName());
    static final String PATH = "/alerts/reports/";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,200}\\.html");

    /** Told about every request for a report, for the Event Monitor. */
    public interface Listener {
        /** @param status 200, 403 (bad key), 404 or 405; {@code name} is what was asked for */
        void served(String remote, String name, boolean download, int status);
    }

    private final HttpServer server;
    private final boolean tls;
    private final byte[] secret;

    private AlertReportServer(HttpServer server, boolean tls, byte[] secret) { this.server = server; this.tls = tls; this.secret = secret; }

    public int port() { return server.getAddress().getPort(); }
    public boolean tls() { return tls; }

    /** The key that goes into a link for this file name. */
    public String keyFor(String fileName) { return sign(secret, fileName); }

    /** The address of a report: https://host:port/alerts/reports/name?k=key (add &amp;download=1 to download). */
    public String urlFor(String host, String fileName) {
        return link(tls, host, port(), fileName, keyFor(fileName));
    }

    static String link(boolean tls, String host, int port, String fileName, String key) {
        return (tls ? "https://" : "http://") + host + ":" + port + PATH
                + java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20") + "?k=" + key;
    }

    /** The link the running service will accept for this file, made without starting a server (used by the test card). */
    static String linkWithoutServer(File dataDir, String host, int port, String fileName) {
        try { return link(true, host, port, fileName, sign(secret(dataDir), fileName)); }
        catch (IOException e) { return null; }
    }

    /**
     * @param dataDir  data folder (holds the signing secret and the certificate)
     * @param reports  where the report folder is now (asked on every request, the setting can change)
     * @param hosts    extra names for the certificate
     * @param tls      false only for tests
     */
    public static AlertReportServer start(File dataDir, String bind, int port, Supplier<Path> reports, List<String> hosts, boolean tls, Listener listener) throws Exception {
        byte[] secret = secret(dataDir);
        InetSocketAddress addr = new InetSocketAddress(bind, port);
        HttpServer http;
        if (tls) {
            LinkTls.Material m = LinkTls.ensure(dataDir, hosts);
            HttpsServer https = HttpsServer.create(addr, 0);
            https.setHttpsConfigurator(new HttpsConfigurator(sslContext(m.keystorePath(), m.password())));
            http = https;
        } else {
            http = HttpServer.create(addr, 0);
        }
        http.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "alert-report-server");
            t.setDaemon(true);
            return t;
        }));
        http.createContext(PATH, ex -> {
            try { serve(ex, secret, reports.get(), listener); } catch (RuntimeException e) { log.log(Level.WARNING, "Report request failed", e); text(ex, 500, "error"); }
            finally { ex.close(); }
        });
        http.start();
        log.info("Alert reports served on " + (tls ? "https" : "http") + "://" + bind + ":" + http.getAddress().getPort() + PATH);
        return new AlertReportServer(http, tls, secret);
    }

    @Override public void close() { server.stop(0); }

    private static void serve(HttpExchange ex, byte[] secret, Path dir, Listener listener) throws IOException {
        int[] status = {0};
        String[] shown = {""};
        boolean[] dl = {false};
        try {
            serve(ex, secret, dir, status, shown, dl);
        } finally {
            if (listener != null && status[0] != 0) {
                String remote = ex.getRemoteAddress() != null && ex.getRemoteAddress().getAddress() != null ? ex.getRemoteAddress().getAddress().getHostAddress() : "?";
                try { listener.served(remote, shown[0], dl[0], status[0]); } catch (RuntimeException ignored) { }
            }
        }
    }

    private static void serve(HttpExchange ex, byte[] secret, Path dir, int[] st, String[] shown, boolean[] dlOut) throws IOException {
        if (!"GET".equals(ex.getRequestMethod()) && !"HEAD".equals(ex.getRequestMethod())) { st[0] = 405; text(ex, 405, "GET only"); return; }
        String raw = ex.getRequestURI().getRawPath();
        String name = URLDecoder.decode(raw.substring(Math.min(raw.length(), PATH.length())), StandardCharsets.UTF_8);
        String query = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
        String key = "";
        boolean download = false;
        for (String kv : query.split("&")) {
            if (kv.startsWith("k=")) key = URLDecoder.decode(kv.substring(2), StandardCharsets.UTF_8);
            if (kv.equals("download=1")) download = true;
        }
        shown[0] = name.length() > 200 ? name.substring(0, 200) : name;
        dlOut[0] = download;
        if (!NAME.matcher(name).matches()) { st[0] = 404; text(ex, 404, "not found"); return; }
        if (!MessageDigest.isEqual(sign(secret, name).getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8))) {
            st[0] = 403; text(ex, 403, "this link is not valid"); return;
        }
        Path file = dir.resolve(name).normalize();
        if (!file.getParent().equals(dir.normalize()) || !Files.isRegularFile(file)) {
            st[0] = 404; text(ex, 404, "this report no longer exists (reports are deleted after a number of days)"); return;
        }
        byte[] body = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        ex.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:");
        if (download) ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + name + "\"");
        st[0] = 200;
        boolean head = "HEAD".equals(ex.getRequestMethod());
        ex.sendResponseHeaders(200, head ? -1 : body.length);
        if (!head) ex.getResponseBody().write(body);
    }

    private static void text(HttpExchange ex, int status, String msg) {
        try {
            byte[] b = msg.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
        } catch (IOException ignored) { }
    }

    static String sign(byte[] secret, String name) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] h = mac.doFinal(name.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The secret of this installation, created on first use. */
    static synchronized byte[] secret(File dataDir) throws IOException {
        Path f = new File(dataDir, "alert-report.key").toPath();
        if (Files.isRegularFile(f)) {
            byte[] b = Files.readAllBytes(f);
            if (b.length >= 32) return b;
        }
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        Files.createDirectories(f.getParent());
        Files.write(f, b, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return b;
    }

    private static SSLContext sslContext(String keystore, String password) throws Exception {
        char[] pw = password == null ? new char[0] : password.toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(keystore))) { ks.load(in, pw); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pw);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }
}
