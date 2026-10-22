package service;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** GUI side of {@link DaemonControlServer}. Returns false whenever the caller should use the database queue instead. */
public final class DaemonControlClient {

    private static final Logger log = Logger.getLogger(DaemonControlClient.class.getName());
    private static final Pattern PORT = Pattern.compile("\"port\"\\s*:\\s*(\\d+)");
    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([0-9a-f]+)\"");

    private DaemonControlClient() {}

    /**
     * True if the daemon accepted and ran the command. False means "not delivered, fall back":
     * no control file, daemon not reachable, wrong token, or the daemon reported a failure.
     * A read timeout after the request was sent counts as delivered, so a slow daemon never
     * causes the same command to run twice (once here, once from the queue).
     */
    public static boolean send(File dataDir, CommandQueueService.Action action, String taskId, String requestedBy) {
        try {
            File f = new File(dataDir, DaemonControlServer.FILE_NAME);
            if (!f.isFile()) return false;
            String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Matcher p = PORT.matcher(json), t = TOKEN.matcher(json);
            if (!p.find() || !t.find()) return false;

            String body = "action=" + enc(action.name())
                    + "&taskId=" + enc(taskId == null ? "" : taskId)
                    + "&requestedBy=" + enc(requestedBy == null ? "gui" : requestedBy);
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + p.group(1) + "/command"))
                    .timeout(Duration.ofSeconds(5))
                    .header(DaemonControlServer.TOKEN_HEADER, t.group(1))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return true;
            log.fine("Daemon control endpoint answered " + resp.statusCode() + ": " + resp.body());
            return false;
        } catch (java.net.http.HttpTimeoutException e) {
            // Connect timeouts mean "not delivered"; a response timeout means the daemon has it.
            return !(e instanceof java.net.http.HttpConnectTimeoutException);
        } catch (Exception e) {
            log.log(Level.FINE, "Daemon control endpoint not usable, using the command queue", e);
            return false;
        }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
