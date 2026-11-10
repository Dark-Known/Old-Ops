package service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import util.MiniJson;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The HTTP(S) endpoint the monitored application posts to.
 *
 * <pre>
 * POST /socketLink/events        Authorization: Bearer &lt;source token&gt;
 *   {"type":"disconnected","seq":41,"instance":"2026-10-28T09:00:00Z","reason":"connection reset"}
 *   or a batch: {"events":[{...},{...}]}
 *   -&gt; 200 {"accepted":2,"duplicates":0}
 * GET  /socketLink/health        no authentication, answers "ok" so the sender can test the route
 * </pre>
 * <p>
 * The token identifies the source; nothing in the body can name a different one.
 */
public final class LinkIngestServer implements AutoCloseable {

    static final int MAX_BODY = 64 * 1024;
    static final int MAX_BATCH = 200;
    private static final Logger log = Logger.getLogger(LinkIngestServer.class.getName());
    private final HttpServer server;
    private final boolean tls;
    private LinkIngestServer(HttpServer server, boolean tls) {
        this.server = server;
        this.tls = tls;
    }

    public static LinkIngestServer start(String bind, int port, String keystore, String keystorePassword,
                                         Handler handler) throws Exception {
        InetSocketAddress addr = new InetSocketAddress(bind, port);
        HttpServer http;
        boolean tls = keystore != null;
        if (tls) {
            HttpsServer https = HttpsServer.create(addr, 0);
            https.setHttpsConfigurator(new HttpsConfigurator(sslContext(keystore, keystorePassword)));
            http = https;
        } else {
            http = HttpServer.create(addr, 0);
        }
        http.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "link-ingest");
            t.setDaemon(true);
            return t;
        }));
        for (String prefix : new String[]{"/socketLink"}) {
            http.createContext(prefix + "/health", ex -> {
                try {
                    try {
                        handler.exchange(remoteOf(ex), ex.getRequestMethod(), ex.getRequestURI().getPath(), null, "", 200, "ok");
                    } catch (RuntimeException ignored) {
                    }
                    reply(ex, 200, "ok");
                } finally {
                    ex.close();
                }
            });
            http.createContext(prefix + "/events", ex -> {
                try {
                    events(ex, handler);
                } finally {
                    ex.close();
                }
            });
        }
        http.start();
        log.info("Socket-link endpoint listening on " + (tls ? "https" : "http") + "://" + bind + ":" + http.getAddress().getPort());
        return new LinkIngestServer(http, tls);
    }

    private static SSLContext sslContext(String keystore, String password) throws Exception {
        char[] pw = password == null ? new char[0] : password.toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(keystore))) {
            ks.load(in, pw);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pw);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    private static String remoteOf(HttpExchange ex) {
        return ex.getRemoteAddress() != null && ex.getRemoteAddress().getAddress() != null
                ? ex.getRemoteAddress().getAddress().getHostAddress() : "?";
    }

    private static void turnAway(HttpExchange ex, Handler handler, String remote, int status, String why, Seen seen) throws IOException {
        try {
            handler.rejected(remote, status, why);
        } catch (RuntimeException ignored) {
        }
        answer(ex, handler, remote, seen, status, why);
    }

    private static void answer(HttpExchange ex, Handler handler, String remote, Seen seen, int status, String text) throws IOException {
        try {
            handler.exchange(remote, seen.method(), ex.getRequestURI().getPath(), seen.token(), seen.body(), status, text);
        } catch (RuntimeException ignored) {
        }
        reply(ex, status, text);
    }

    private static void events(HttpExchange ex, Handler handler) throws IOException {
        String remote = remoteOf(ex);
        String method = ex.getRequestMethod();
        if (!"POST".equals(method)) {
            turnAway(ex, handler, remote, 405, "POST only", new Seen(method, null, ""));
            return;
        }
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            turnAway(ex, handler, remote, 401, "missing bearer token", new Seen(method, null, ""));
            return;
        }
        String token = auth.substring(7).trim();

        byte[] body = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            turnAway(ex, handler, remote, 413, "body too large", new Seen(method, token, "(" + body.length + "+ bytes)"));
            return;
        }
        String text = new String(body, StandardCharsets.UTF_8);
        Seen seen = new Seen(method, token, text);

        List<LinkMonitor.Report> reports;
        try {
            reports = parse(text);
        } catch (RuntimeException e) {
            turnAway(ex, handler, remote, 400, "bad json: " + e.getMessage(), seen);
            return;
        }
        LinkMonitor.Ingest result;
        try {
            result = handler.handle(token, remote, reports);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Socket-link report failed", e);
            turnAway(ex, handler, remote, 500, "server error", seen);
            return;
        }
        if (result == null) {
            turnAway(ex, handler, remote, 401, "unknown token", seen);
            return;
        }
        answer(ex, handler, remote, seen, 200, "{\"accepted\":" + result.accepted() + ",\"duplicates\":" + result.duplicates() + "}");
    }

    /**
     * Accepts a single event object or {"events":[...]}.
     */
    static List<LinkMonitor.Report> parse(String json) {
        Map<String, Object> root = MiniJson.parseObject(json);
        List<Object> arr = MiniJson.getArray(root, "events");
        List<Map<String, Object>> objs = new ArrayList<>();
        if (arr != null) {
            if (arr.size() > MAX_BATCH)
                throw new IllegalArgumentException("more than " + MAX_BATCH + " events in one request");
            for (Object o : arr) {
                if (!(o instanceof Map)) throw new IllegalArgumentException("events must be objects");
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
                objs.add(m);
            }
        } else {
            objs.add(root);
        }
        List<LinkMonitor.Report> out = new ArrayList<>();
        for (Map<String, Object> m : objs) {
            String type = MiniJson.getString(m, "type", "heartbeat");
            String state = MiniJson.getString(m, "state", null);
            if (LinkMonitor.impliedState(new LinkMonitor.Report(type, state, -1, null, null, 0)) == null
                    && !"heartbeat".equalsIgnoreCase(type)) {
                throw new IllegalArgumentException("unknown event type '" + type + "'");
            }
            long seq = -1;
            Object sv = m.get("seq");
            if (sv instanceof Number) seq = ((Number) sv).longValue();
            out.add(new LinkMonitor.Report(type, state, seq, MiniJson.getString(m, "instance", null),
                    clip(MiniJson.getString(m, "reason", null), 300), MiniJson.getInt(m, "reconnectsLastHour", 0)));
        }
        return out;
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        String t = s.replaceAll("[\\r\\n]+", " ");
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static void reply(HttpExchange ex, int code, String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", text.startsWith("{") ? "application/json" : "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public boolean tls() {
        return tls;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public interface Handler {
        /**
         * Handles one batch from {@code remote}; returns null when the token belongs to no source.
         */
        LinkMonitor.Ingest handle(String bearerToken, String remote, List<LinkMonitor.Report> reports);

        /**
         * A request that was turned away (HTTP status and reason), for the activity log.
         */
        void rejected(String remote, int status, String why);

        /**
         * Called once for every request to the endpoint, whatever the outcome, for the activity log.
         */
        default void exchange(String remote, String method, String path, String bearerToken,
                              String requestBody, int status, String responseBody) {
        }
    }

    /**
     * What the current request looked like, kept so the one place that answers can also log it.
     */
    private record Seen(String method, String token, String body) {
    }
}
