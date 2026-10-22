package service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
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
    private static final Logger log = Logger.getLogger(DaemonControlServer.class.getName());

    /** Runs a command; throws to report failure back to the caller. */
    public interface Executor {
        void execute(CommandQueueService.Action action, String taskId, String requestedBy) throws Exception;
    }

    private final HttpServer server;
    private final File controlFile;
    private final String token;

    private DaemonControlServer(HttpServer server, File controlFile, String token) {
        this.server = server;
        this.controlFile = controlFile;
        this.token = token;
    }

    public static boolean enabledByConfig() {
        String v = util.AppConfig.readValue("daemonControlApi");
        return v == null || !v.trim().equalsIgnoreCase("false");
    }

    public static DaemonControlServer start(File dataDir, Executor executor) throws IOException {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        StringBuilder hex = new StringBuilder();
        for (byte b : raw) hex.append(String.format("%02x", b));
        String token = hex.toString();

        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        http.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "daemon-control");
            t.setDaemon(true);
            return t;
        }));
        http.createContext("/command", ex -> handle(ex, token, executor));

        File file = new File(dataDir, FILE_NAME);
        DaemonControlServer s = new DaemonControlServer(http, file, token);
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
        try {
            if (!"POST".equals(ex.getRequestMethod())) { reply(ex, 405, "POST only"); return; }
            String given = ex.getRequestHeaders().getFirst(TOKEN_HEADER);
            if (given == null || !MessageDigest.isEqual(
                    given.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
                reply(ex, 403, "bad token");
                return;
            }
            byte[] body = ex.getRequestBody().readNBytes(4096);
            Map<String, String> form = parseForm(new String(body, StandardCharsets.UTF_8));
            CommandQueueService.Action action;
            try {
                action = CommandQueueService.Action.valueOf(form.getOrDefault("action", ""));
            } catch (IllegalArgumentException e) {
                reply(ex, 400, "unknown action");
                return;
            }
            try {
                executor.execute(action, form.get("taskId"), form.getOrDefault("requestedBy", "gui"));
                reply(ex, 200, "OK");
            } catch (Exception e) {
                log.log(Level.WARNING, "Control command " + action + " failed", e);
                reply(ex, 500, String.valueOf(e.getMessage()));
            }
        } finally {
            ex.close();
        }
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

    public int port() { return server.getAddress().getPort(); }
    public String token() { return token; }

    @Override
    public void close() {
        server.stop(0);
        controlFile.delete();
    }
}
