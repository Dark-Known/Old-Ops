package service;

import util.MiniJson;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * The internal conversation between the window and the background service: commands sent over the local control
 * endpoint ("Run now", "Cancel", ...), commands left in the database queue when the service could not be reached,
 * status polls and status streams, and the answers. One JSON line per event in {@code daemon-activity.log} (rolls
 * over at 2 MB), written by whichever process handled it, read by the API page. The control token is never logged.
 */
public final class DaemonCallLog {

    public static final String FILE_NAME = "daemon-activity.log";
    static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final long STATUS_EVERY_MS = 30_000;

    /** {@code channel}: "control" (local HTTP endpoint) or "queue" (database fallback). */
    public record Entry(long time, String channel, String method, String path, int status, long millis,
                        String action, String taskId, String requestedBy, String response) {
        public boolean ok() { return status >= 200 && status < 300; }
    }

    private static volatile File dir;
    private static long lastStatusAt;
    private static int statusPolls;

    private DaemonCallLog() { }

    public static void configure(File dataDir) { dir = dataDir; }

    private static volatile java.util.function.Consumer<Entry> listener;

    /** Told about every recorded call (used to raise "Service command failed"); null to stop. */
    public static void setListener(java.util.function.Consumer<Entry> l) { listener = l; }

    public static void record(String channel, String method, String path, int status, long millis,
                              String action, String taskId, String requestedBy, String response) {
        File d = dir;
        if (d == null) return;
        Entry entry = new Entry(System.currentTimeMillis(), channel, method, path, status, millis,
                action == null ? "" : action, taskId == null ? "" : taskId, requestedBy == null ? "" : requestedBy,
                clip(response, 500));
        write(d, entry);
        java.util.function.Consumer<Entry> l = listener;
        try { if (l != null) l.accept(entry); } catch (RuntimeException ignored) { }
    }

    /** Status polls are frequent: only one line per half minute, saying how many there were. */
    public static void recordStatusPoll(int status) {
        long now = System.currentTimeMillis();
        synchronized (DaemonCallLog.class) {
            statusPolls++;
            if (now - lastStatusAt < STATUS_EVERY_MS) return;
            lastStatusAt = now;
            int n = statusPolls;
            statusPolls = 0;
            record("control", "GET", "/status", status, 0, "", "", "", n + (n == 1 ? " poll" : " polls") + " in the last interval");
        }
    }

    static synchronized void write(File d, Entry e) {
        try {
            File f = new File(d, FILE_NAME);
            if (f.length() > MAX_BYTES) {
                Files.move(f.toPath(), new File(d, FILE_NAME + ".1").toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            String line = "{\"t\":" + e.time() + ",\"channel\":\"" + esc(e.channel()) + "\",\"method\":\"" + esc(e.method())
                    + "\",\"path\":\"" + esc(e.path()) + "\",\"status\":" + e.status() + ",\"ms\":" + e.millis()
                    + ",\"action\":\"" + esc(e.action()) + "\",\"task\":\"" + esc(e.taskId())
                    + "\",\"by\":\"" + esc(e.requestedBy()) + "\",\"response\":\"" + esc(e.response()) + "\"}\n";
            Files.writeString(f.toPath(), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ex) {
            // best effort
        }
    }

    public static List<Entry> readRecent(File dataDir, int limit) {
        Deque<Entry> out = new ArrayDeque<>();
        for (String name : new String[] { FILE_NAME + ".1", FILE_NAME }) {
            File f = new File(dataDir, name);
            if (!f.isFile()) continue;
            try (BufferedReader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    Entry e = parse(line);
                    if (e == null) continue;
                    out.addLast(e);
                    if (out.size() > limit) out.removeFirst();
                }
            } catch (IOException | RuntimeException ex) {
                // show what was read
            }
        }
        return new ArrayList<>(out);
    }

    static Entry parse(String line) {
        try {
            Map<String, Object> m = MiniJson.parseObject(line);
            Object t = m.get("t"), ms = m.get("ms");
            if (!(t instanceof Number)) return null;
            return new Entry(((Number) t).longValue(), MiniJson.getString(m, "channel", "control"), MiniJson.getString(m, "method", ""),
                    MiniJson.getString(m, "path", ""), MiniJson.getInt(m, "status", 0), ms instanceof Number ? ((Number) ms).longValue() : 0,
                    MiniJson.getString(m, "action", ""), MiniJson.getString(m, "task", ""), MiniJson.getString(m, "by", ""),
                    MiniJson.getString(m, "response", ""));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "Run now for task 3f2a...", "Status stream", ... */
    public static String describe(Entry e) {
        if (!e.action().isEmpty()) {
            String verb;
            switch (e.action()) {
                case "RUN_NOW": verb = "Run now"; break;
                case "CANCEL": verb = "Cancel"; break;
                case "RESTART": verb = "Restart"; break;
                case "RECONNECT_WATCH": verb = "Reconnect watch"; break;
                case "REFRESH": verb = "Refresh schedules"; break;
                case "PURGE_NOW": verb = "Purge now"; break;
                default: verb = e.action();
            }
            String task = e.taskId().isEmpty() || e.taskId().equals("null") ? "" : " (task " + shortId(e.taskId()) + ")";
            return verb + task;
        }
        if (e.path().startsWith("/status/stream")) return "Status stream";
        if (e.path().startsWith("/status")) return "Status poll";
        return e.method() + " " + e.path();
    }

    public static String explain(Entry e) {
        switch (e.status()) {
            case 403: return "The control token was wrong. The window re-reads daemon-control.json after the service restarts.";
            case 400: return "The command name was not recognised.";
            case 405: return "Wrong HTTP method for this path.";
            case 500: return "The service accepted the command but it failed while running; see the response and daemon.log.";
            case 503: return "Too many status streams are open.";
            case 0: return "The command could not be delivered.";
            default: return "";
        }
    }

    private static String shortId(String id) { return id.length() > 8 ? id.substring(0, 8) : id; }
    private static String clip(String s, int n) { return s == null ? "" : s.length() <= n ? s : s.substring(0, n) + "..."; }
    private static String esc(String s) { return MiniJson.escapeString(s == null ? "" : s); }
}
