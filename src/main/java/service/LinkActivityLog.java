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
 * A plain log of every request the link endpoint received and the answer it sent: one JSON object per line in
 * {@code socket-monitor-activity.log}, written by the background service and read by the window. It does not depend
 * on the Event Monitor or its filters. The file rolls over to {@code .1} at 2 MB, so it never grows without bound.
 * Tokens are never stored: only the first characters, enough to tell which one was used.
 */
public final class LinkActivityLog {

    public static final String FILE_NAME = "socket-monitor-activity.log";
    static final long MAX_BYTES = 2L * 1024 * 1024;
    static final int MAX_TEXT = 4000;

    public record Entry(long time, String remote, String sourceId, String label, String method, String path,
                        int status, String tokenHint, String request, String response) {
        public boolean ok() { return status >= 200 && status < 300; }
    }

    private final File dir;

    public LinkActivityLog(File dataDir) { this.dir = dataDir; }

    public synchronized void append(Entry e) {
        try {
            File f = new File(dir, FILE_NAME);
            if (f.length() > MAX_BYTES) {
                Files.move(f.toPath(), new File(dir, FILE_NAME + ".1").toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            String line = "{\"t\":" + e.time()
                    + ",\"remote\":\"" + esc(e.remote()) + "\",\"source\":\"" + esc(e.sourceId())
                    + "\",\"label\":\"" + esc(e.label()) + "\",\"method\":\"" + esc(e.method())
                    + "\",\"path\":\"" + esc(e.path()) + "\",\"status\":" + e.status()
                    + ",\"token\":\"" + esc(e.tokenHint()) + "\",\"request\":\"" + esc(clip(e.request()))
                    + "\",\"response\":\"" + esc(clip(e.response())) + "\"}\n";
            Files.writeString(f.toPath(), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ex) {
            // Best effort: logging must never break the endpoint.
        }
    }

    /** The newest {@code limit} entries, oldest first. */
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
                // A half-written line or a vanished file: show what was read.
            }
        }
        return new ArrayList<>(out);
    }

    static Entry parse(String line) {
        try {
            Map<String, Object> m = MiniJson.parseObject(line);
            Object t = m.get("t");
            if (!(t instanceof Number)) return null;
            return new Entry(((Number) t).longValue(), MiniJson.getString(m, "remote", ""), MiniJson.getString(m, "source", ""),
                    MiniJson.getString(m, "label", ""), MiniJson.getString(m, "method", ""), MiniJson.getString(m, "path", ""),
                    MiniJson.getInt(m, "status", 0), MiniJson.getString(m, "token", ""),
                    MiniJson.getString(m, "request", ""), MiniJson.getString(m, "response", ""));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** One line saying what a request carried, e.g. "2 messages: disconnected, heartbeat (connected)". */
    public static String summarize(Entry e) {
        if (e.path().endsWith("/health")) return "Health check";
        String body = e.request() == null ? "" : e.request().trim();
        if (body.isEmpty()) return e.method().equals("POST") ? "(no body)" : e.method() + " (no body)";
        try {
            Map<String, Object> m = MiniJson.parseObject(body);
            List<Object> arr = MiniJson.getArray(m, "events");
            List<Map<String, Object>> msgs = new ArrayList<>();
            if (arr != null) {
                for (Object o : arr) {
                    @SuppressWarnings("unchecked") Map<String, Object> mm = o instanceof Map ? (Map<String, Object>) o : null;
                    if (mm != null) msgs.add(mm);
                }
            } else {
                msgs.add(m);
            }
            StringBuilder sb = new StringBuilder();
            if (msgs.size() > 1) sb.append(msgs.size()).append(" messages: ");
            int shown = 0;
            for (Map<String, Object> mm : msgs) {
                if (shown++ == 3) { sb.append(", ..."); break; }
                if (shown > 1) sb.append(", ");
                String type = MiniJson.getString(mm, "type", "heartbeat");
                sb.append(type);
                String state = MiniJson.getString(mm, "state", null);
                if (state != null && type.equalsIgnoreCase("heartbeat")) sb.append(" (").append(state).append(')');
            }
            return sb.toString();
        } catch (RuntimeException ex) {
            return body.length() > 60 ? body.substring(0, 60) + "..." : body;
        }
    }

    /** A warning about the content of an accepted request, or "" when there is nothing to point out. */
    public static String hint(Entry e) {
        if (!e.ok() || e.path().endsWith("/health") || e.request() == null) return "";
        try {
            Map<String, Object> m = MiniJson.parseObject(e.request().trim());
            List<Object> arr = MiniJson.getArray(m, "events");
            List<Object> msgs = arr != null ? arr : List.of(m);
            for (Object o : msgs) {
                if (!(o instanceof Map)) continue;
                @SuppressWarnings("unchecked") Map<String, Object> mm = (Map<String, Object>) o;
                String type = MiniJson.getString(mm, "type", "heartbeat");
                if (type.equalsIgnoreCase("heartbeat") && MiniJson.getString(mm, "state", null) == null) {
                    return "this heartbeat has no \"state\", so it only proves the sender is alive and cannot change the link status. "
                            + "Add \"state\":\"connected\" (or disconnected, stalled) to the heartbeat.";
                }
            }
        } catch (RuntimeException ex) {
            // not JSON: nothing to point out
        }
        return "";
    }

    /** What an HTTP status means for the person reading the log. */
    public static String explain(int status) {
        switch (status) {
            case 200: return "Accepted.";
            case 400: return "The body is not valid JSON, or has an unknown event type. The sender should drop this message.";
            case 401: return "The token is missing, wrong, expired (a previous token past its grace period), or the source is disabled.";
            case 405: return "Only POST is accepted on /socketLink/events.";
            case 413: return "The request is larger than 64 KB. Send smaller batches (at most 200 messages).";
            case 500: return "The service failed while handling the request; see daemon.log.";
            default: return "";
        }
    }

    /** Safe to store: the first characters of a token, never the whole thing. */
    public static String tokenHint(String token) {
        if (token == null || token.isEmpty()) return "";
        return token.length() <= 8 ? "****" : token.substring(0, 8) + "...";
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT) + "... (cut)";
    }

    private static String esc(String s) { return MiniJson.escapeString(s == null ? "" : s); }
}
