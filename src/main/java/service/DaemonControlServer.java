package service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Local control endpoint of the daemon: lets the GUI deliver "Run now / Cancel / ..." immediately
 * instead of waiting for the 3-second poll of the {@code gui_commands} table.
 *
 * <ul>
 *   <li>Listens on 127.0.0.1 only, on a random port.</li>
 *   <li>Every request needs the random token written to {@code <dataDir>/daemon-control.json};
 *       whoever can read that file (i.e. whoever can read the data directory) can send commands,
 *       the same trust the database queue already has.</li>
 *   <li>It is an optimisation only: the database queue remains the fallback
 *       ({@link CommandQueueService#enqueue}), so nothing depends on this server being up.</li>
 *   <li>Disable with {@code <daemonControlApi>false</daemonControlApi>} in app-config.xml.</li>
 * </ul>
 */
public final class DaemonControlServer implements AutoCloseable {

    public static final String FILE_NAME = "daemon-control.json";
    public static final String TOKEN_HEADER = "X-Opstool-Token";
    /**
     * A heartbeat comment goes out this often so clients can tell "idle" from "daemon gone".
     */
    static final long DEFAULT_HEARTBEAT_MS = 5_000;
    private static final Logger log = Logger.getLogger(DaemonControlServer.class.getName());
    /**
     * Cap on concurrent status streams (one per GUI window is plenty; each holds a server thread).
     */
    private static final int MAX_STREAMS = 4;
    private final HttpServer server;
    private final File controlFile;
    private final String token;
    private final StatusBroadcaster bus;
    private DaemonControlServer(HttpServer server, File controlFile, String token, StatusBroadcaster bus) {
        this.server = server;
        this.controlFile = controlFile;
        this.token = token;
        this.bus = bus;
    }

    public static boolean enabledByConfig() {
        String v = util.AppConfig.readValue("daemonControlApi");
        return v == null || !v.trim().equalsIgnoreCase("false");
    }

    public static DaemonControlServer start(File dataDir, Executor executor) throws IOException {
        return start(dataDir, executor, new StatusBroadcaster(), DEFAULT_HEARTBEAT_MS);
    }

    /**
     * As above, and also serves {@code GET /status} and the push stream {@code GET /status/stream} from {@code bus}.
     */
    public static DaemonControlServer start(File dataDir, Executor executor, StatusBroadcaster bus) throws IOException {
        return start(dataDir, executor, bus, DEFAULT_HEARTBEAT_MS);
    }

    static DaemonControlServer start(File dataDir, Executor executor, StatusBroadcaster bus, long heartbeatMs) throws IOException {
        DaemonCallLog.configure(dataDir);
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        StringBuilder hex = new StringBuilder();
        for (byte b : raw) hex.append(String.format("%02x", b));
        String token = hex.toString();

        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        // Cached rather than fixed: each open status stream occupies a thread for as long as it is connected,
        // and must never starve /command. The number of streams is capped separately (MAX_STREAMS).
        http.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "daemon-control");
            t.setDaemon(true);
            return t;
        }));
        http.createContext("/command", ex -> handle(ex, token, executor));
        http.createContext("/status", ex -> status(ex, token, bus));
        http.createContext("/status/stream", ex -> stream(ex, token, bus, heartbeatMs));

        File file = new File(dataDir, FILE_NAME);
        DaemonControlServer s = new DaemonControlServer(http, file, token, bus);
        http.start();
        String json = "{\"port\":" + http.getAddress().getPort() + ",\"token\":\"" + token + "\",\"pid\":"
                + ProcessHandle.current().pid() + "}";
        File tmp = new File(dataDir, FILE_NAME + ".tmp");
        Files.write(tmp.toPath(), json.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        log.info("Daemon control endpoint on 127.0.0.1:" + http.getAddress().getPort());
        return s;
    }

    private static void handle(HttpExchange ex, String token, Executor executor) throws IOException {
        long t0 = System.currentTimeMillis();
        try {
            if (!"POST".equals(ex.getRequestMethod())) {
                DaemonCallLog.record("control", ex.getRequestMethod(), "/command", 405, 0, "", "", "", "POST only");
                reply(ex, 405, "POST only");
                return;
            }
            if (!authorized(ex, token)) {
                DaemonCallLog.record("control", "POST", "/command", 403, 0, "", "", "", "bad token");
                reply(ex, 403, "bad token");
                return;
            }
            byte[] body = ex.getRequestBody().readNBytes(4096);
            Map<String, String> form = parseForm(new String(body, StandardCharsets.UTF_8));
            CommandQueueService.Action action;
            try {
                action = CommandQueueService.Action.valueOf(form.getOrDefault("action", ""));
            } catch (IllegalArgumentException e) {
                DaemonCallLog.record("control", "POST", "/command", 400, 0, form.getOrDefault("action", ""), form.get("taskId"),
                        form.getOrDefault("requestedBy", "gui"), "unknown action");
                reply(ex, 400, "unknown action");
                return;
            }
            String who = form.getOrDefault("requestedBy", "gui");
            try {
                executor.execute(action, form.get("taskId"), who);
                DaemonCallLog.record("control", "POST", "/command", 200, System.currentTimeMillis() - t0, action.name(), form.get("taskId"), who, "OK");
                reply(ex, 200, "OK");
            } catch (Exception e) {
                log.log(Level.WARNING, "Control command " + action + " failed", e);
                DaemonCallLog.record("control", "POST", "/command", 500, System.currentTimeMillis() - t0, action.name(), form.get("taskId"), who,
                        String.valueOf(e.getMessage()));
                reply(ex, 500, String.valueOf(e.getMessage()));
            }
        } finally {
            ex.close();
        }
    }

    private static boolean authorized(HttpExchange ex, String token) {
        String given = ex.getRequestHeaders().getFirst(TOKEN_HEADER);
        return given != null && MessageDigest.isEqual(
                given.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {@code GET /status}: the latest status text once (204 if the scheduler has not exported yet).
     */
    private static void status(HttpExchange ex, String token, StatusBroadcaster bus) throws IOException {
        try {
            if (!"GET".equals(ex.getRequestMethod())) {
                DaemonCallLog.record("control", ex.getRequestMethod(), "/status", 405, 0, "", "", "", "GET only");
                reply(ex, 405, "GET only");
                return;
            }
            if (!authorized(ex, token)) {
                DaemonCallLog.record("control", "GET", "/status", 403, 0, "", "", "", "bad token");
                reply(ex, 403, "bad token");
                return;
            }
            String text = bus.latest();
            if (text == null) {
                DaemonCallLog.recordStatusPoll(204);
                ex.sendResponseHeaders(204, -1);
                return;
            }
            DaemonCallLog.recordStatusPoll(200);
            reply(ex, 200, text);
        } finally {
            ex.close();
        }
    }

    /**
     * {@code GET /status/stream}: Server-Sent Events. One {@code status} event per real state change
     * (latest-wins, so a slow reader just skips ahead) and a {@code : hb} comment every heartbeat so the
     * client can detect a dead daemon. The first event is the current state. The handler holds its thread until the
     * client disconnects or the server closes.
     */
    private static void stream(HttpExchange ex, String token, StatusBroadcaster bus, long heartbeatMs) throws IOException {
        StatusBroadcaster.Subscription sub = null;
        long opened = System.currentTimeMillis();
        int events = 0;
        boolean streaming = false;
        try {
            if (!"GET".equals(ex.getRequestMethod())) {
                reply(ex, 405, "GET only");
                return;
            }
            if (!authorized(ex, token)) {
                DaemonCallLog.record("control", "GET", "/status/stream", 403, 0, "", "", "", "bad token");
                reply(ex, 403, "bad token");
                return;
            }
            sub = bus.subscribe(MAX_STREAMS);
            if (sub == null) {
                DaemonCallLog.record("control", "GET", "/status/stream", 503, 0, "", "", "", "too many status streams");
                reply(ex, 503, "too many status streams");
                return;
            }
            streaming = true;
            DaemonCallLog.record("control", "GET", "/status/stream", 200, 0, "", "", "", "stream opened");

            ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, 0); // chunked: stays open
            OutputStream os = ex.getResponseBody();
            os.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            while (!sub.isClosed()) {
                String msg = sub.next(heartbeatMs);
                if (msg != null) events++;
                os.write((msg == null ? ": hb\n\n" : toSseEvent(msg)).getBytes(StandardCharsets.UTF_8));
                os.flush(); // throws once the client has gone away, which ends the loop
            }
        } catch (IOException | InterruptedException e) {
            // client disconnected or server closing: normal end of a stream
        } finally {
            if (sub != null) sub.close();
            if (streaming)
                DaemonCallLog.record("control", "GET", "/status/stream", 200, System.currentTimeMillis() - opened, "", "", "",
                        "stream closed after " + events + " status events");
            ex.close();
        }
    }

    /**
     * One SSE event: {@code event: status} then one {@code data:} line per status line, ended by a blank line.
     */
    static String toSseEvent(String statusText) {
        String body = statusText.endsWith("\n") ? statusText.substring(0, statusText.length() - 1) : statusText;
        StringBuilder sb = new StringBuilder(body.length() + 64).append("event: status\n");
        for (String line : body.split("\n", -1)) sb.append("data: ").append(line).append('\n');
        return sb.append('\n').toString();
    }

    static Map<String, String> parseForm(String s) {
        Map<String, String> m = new HashMap<>();
        for (String pair : s.split("&")) {
            if (pair.isEmpty()) continue;
            int i = pair.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            m.put(k, v);
        }
        return m;
    }

    private static void reply(HttpExchange ex, int code, String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String token() {
        return token;
    }

    @Override
    public void close() {
        bus.close(); // lets open status streams finish within one heartbeat
        server.stop(0);
        controlFile.delete();
    }

    /**
     * Runs a command; throws to report failure back to the caller.
     */
    public interface Executor {
        void execute(CommandQueueService.Action action, String taskId, String requestedBy) throws Exception;
    }
}
