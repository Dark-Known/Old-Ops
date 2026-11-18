package service;

import util.MiniJson;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.net.URI;
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
 * A record of every call made to the Outlook mail API (Microsoft Graph) and to the Microsoft sign-in service for its
 * tokens: what was asked, the answer, how long it took. One JSON line per call in {@code graph-activity.log}, rolled
 * over at 2 MB. Written by whichever process makes the call; read by the API page.
 *
 * <p>Only metadata is kept: no access tokens, no refresh tokens, no message text or attachment content. For failed
 * calls the error code and message Microsoft returned are kept, because that is what explains the failure.
 */
public final class GraphCallLog {

    public static final String FILE_NAME = "graph-activity.log";
    static final long MAX_BYTES = 2L * 1024 * 1024;

    public record Entry(long time, String kind, String method, String target, String mailbox, int status,
                        long millis, long bytes, String requestId, int attempt, String note) {
        public boolean ok() { return status >= 200 && status < 300; }
        public boolean isToken() { return "token".equals(kind); }
    }

    private static volatile File dir;

    private GraphCallLog() { }

    /** Called once at start-up with the data folder; calls made before that are not recorded. */
    public static void configure(File dataDir) { dir = dataDir; }

    private static volatile java.util.function.Consumer<Entry> listener;

    /** Told about every recorded call (used to raise "Outlook mail API failing"); null to stop. */
    public static void setListener(java.util.function.Consumer<Entry> l) { listener = l; }

    /** Records one call. Never throws. */
    public static void record(String kind, String method, URI uri, String mailbox, int status, long millis,
                              long bytes, String requestId, int attempt, String note) {
        File d = dir;
        if (d == null) return;
        try {
            String target = uri.getHost() + (uri.getRawPath() == null ? "" : uri.getRawPath())
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            Entry entry = new Entry(System.currentTimeMillis(), kind, method, clip(target, 600), cleanMailbox(mailbox),
                    status, millis, bytes, requestId == null ? "" : requestId, attempt, clip(note, 600));
            write(d, entry);
            java.util.function.Consumer<Entry> l = listener;
            if (l != null) l.accept(entry);
        } catch (RuntimeException e) {
            // logging must never break a mail run
        }
    }

    /** "/users/ops%40corp.com" -> "ops@corp.com", "/me" -> "signed-in user". */
    static String cleanMailbox(String m) {
        if (m == null) return "";
        if (m.equals("/me")) return "signed-in user";
        if (m.startsWith("/users/")) {
            try { return java.net.URLDecoder.decode(m.substring(7), StandardCharsets.UTF_8); } catch (RuntimeException e) { return m.substring(7); }
        }
        return m;
    }

    /** A call that failed for good: throttling and brief outages are retried, so they only count when the retries ran out. */
    public static boolean failed(Entry e) {
        if (e.ok()) return false;
        return !((e.status() == 429 || e.status() == 503) && e.attempt() < 4);
    }

    /** The code and message of a Graph or sign-in error body, or a clipped copy of the text. */
    public static String errorText(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            Map<String, Object> m = MiniJson.parseObject(body);
            Map<String, Object> err = MiniJson.getObject(m, "error");
            if (err != null) {
                String code = MiniJson.getString(err, "code", "");
                String msg = MiniJson.getString(err, "message", "");
                return (code + (msg.isEmpty() ? "" : ": " + msg)).trim();
            }
            String e = MiniJson.getString(m, "error", "");
            String desc = MiniJson.getString(m, "error_description", "");
            if (!e.isEmpty() || !desc.isEmpty()) return (e + (desc.isEmpty() ? "" : ": " + desc.replaceAll("\\s+", " "))).trim();
        } catch (RuntimeException ignored) { }
        return body.replaceAll("\\s+", " ");
    }

    static synchronized void write(File d, Entry e) {
        try {
            File f = LogFiles.file(d, FILE_NAME);
            if (f.length() > MAX_BYTES) {
                Files.move(f.toPath(), LogFiles.file(d, FILE_NAME + ".1").toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            String line = "{\"t\":" + e.time() + ",\"kind\":\"" + esc(e.kind()) + "\",\"method\":\"" + esc(e.method())
                    + "\",\"target\":\"" + esc(e.target()) + "\",\"mailbox\":\"" + esc(e.mailbox())
                    + "\",\"status\":" + e.status() + ",\"ms\":" + e.millis() + ",\"bytes\":" + e.bytes()
                    + ",\"rid\":\"" + esc(e.requestId()) + "\",\"attempt\":" + e.attempt()
                    + ",\"note\":\"" + esc(e.note()) + "\"}\n";
            Files.writeString(f.toPath(), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ex) {
            // best effort
        }
    }

    /** The newest {@code limit} entries, oldest first. */
    public static List<Entry> readRecent(File dataDir, int limit) {
        Deque<Entry> out = new ArrayDeque<>();
        for (String name : new String[] { FILE_NAME + ".1", FILE_NAME }) {
            File f = LogFiles.file(dataDir, name);
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
            Object t = m.get("t");
            if (!(t instanceof Number)) return null;
            Object ms = m.get("ms"), by = m.get("bytes");
            return new Entry(((Number) t).longValue(), MiniJson.getString(m, "kind", "graph"), MiniJson.getString(m, "method", ""),
                    MiniJson.getString(m, "target", ""), MiniJson.getString(m, "mailbox", ""), MiniJson.getInt(m, "status", 0),
                    ms instanceof Number ? ((Number) ms).longValue() : 0, by instanceof Number ? ((Number) by).longValue() : -1,
                    MiniJson.getString(m, "rid", ""), MiniJson.getInt(m, "attempt", 1), MiniJson.getString(m, "note", ""));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A short human description of what a call did, e.g. "List messages in Inbox". */
    public static String describe(Entry e) {
        if (e.isToken()) return e.target.contains("/token") ? "Request access token" : "Sign-in";
        String p = e.target;
        int q = p.indexOf('?');
        String path = q < 0 ? p : p.substring(0, q);
        String m = e.method();
        if (path.contains("/attachments")) return path.endsWith("/$value") ? "Download attachment" : "List / get attachments";
        if (path.contains("/move")) return "Move message";
        if (path.contains("/mailFolders") && path.contains("/messages")) return "List messages in folder";
        if (path.contains("/mailFolders")) return m.equals("GET") ? "Look up mail folder" : "Mail folder " + m;
        if (path.matches(".*/messages/[^/]+$")) return m.equals("PATCH") ? "Update message (mark read / category)" : m.equals("DELETE") ? "Delete message" : "Get message";
        if (path.endsWith("/messages")) return m.equals("POST") ? "Create message" : "List messages";
        if (path.endsWith("/copy")) return "Copy message";
        return m + " " + path.substring(path.lastIndexOf('/') + 1);
    }

    /** What a status from Microsoft usually means, for the person reading the log. */
    public static String explain(Entry e) {
        switch (e.status()) {
            case 0: return "No answer: the request never completed (network, proxy, DNS or timeout). See the note.";
            case 400: return "Microsoft rejected the request as malformed (often a bad filter or folder name).";
            case 401: return e.isToken() ? "Sign-in refused: wrong or expired credentials, or the refresh token was revoked. Re-run Authorize Mailbox."
                    : "The access token was rejected (expired or for another tenant).";
            case 403: return e.isToken() ? "Sign-in not allowed for this app or account."
                    : "Not allowed: the app lacks the Mail permission, or an access policy blocks this mailbox.";
            case 404: return "The mailbox, folder or message does not exist (or was moved or deleted).";
            case 429: return "Throttled by Microsoft. The tool waits as instructed and retries.";
            case 503: return "Microsoft was temporarily unavailable. The tool retries.";
            default: return e.status() >= 500 ? "Microsoft-side error; retry later." : "";
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String esc(String s) { return MiniJson.escapeString(s == null ? "" : s); }
}
