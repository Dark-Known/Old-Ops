package service;

import util.MiniJson;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Every alert delivery attempt: what was sent about which task, to which webhook host, the answer, and the report file.
 * One JSON line per attempt in {@code alerts-activity.log} (rolls over at 2 MB). The webhook address itself, which
 * contains a secret, is never written: only its host name.
 */
public final class AlertCallLog {

    public static final String FILE_NAME = "alerts-activity.log";
    static final long MAX_BYTES = 2L * 1024 * 1024;

    private AlertCallLog() {
    }

    public static synchronized void write(File dir, Entry e) {
        try {
            File f = LogFiles.file(dir, FILE_NAME);
            if (f.length() > MAX_BYTES)
                Files.move(f.toPath(), LogFiles.file(dir, FILE_NAME + ".1").toPath(), StandardCopyOption.REPLACE_EXISTING);
            String line = "{\"t\":" + e.time() + ",\"task\":\"" + esc(e.taskId()) + "\",\"name\":\"" + esc(e.taskName())
                    + "\",\"kind\":\"" + esc(e.kind()) + "\",\"title\":\"" + esc(e.title()) + "\",\"host\":\"" + esc(e.host())
                    + "\",\"status\":" + e.status() + ",\"ms\":" + e.millis() + ",\"attempt\":" + e.attempt()
                    + ",\"response\":\"" + esc(clip(e.response(), 500)) + "\",\"report\":\"" + esc(e.reportPath())
                    + "\",\"payload\":\"" + esc(clip(e.payload(), 3000)) + "\"}\n";
            Files.writeString(f.toPath(), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ex) {
            // best effort
        }
    }

    public static List<Entry> readRecent(File dataDir, int limit) {
        Deque<Entry> out = new ArrayDeque<>();
        for (String name : new String[]{FILE_NAME + ".1", FILE_NAME}) {
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
            Object t = m.get("t"), ms = m.get("ms");
            if (!(t instanceof Number)) return null;
            return new Entry(((Number) t).longValue(), MiniJson.getString(m, "task", ""), MiniJson.getString(m, "name", ""),
                    MiniJson.getString(m, "kind", ""), MiniJson.getString(m, "title", ""), MiniJson.getString(m, "host", ""),
                    MiniJson.getInt(m, "status", 0), ms instanceof Number ? ((Number) ms).longValue() : 0, MiniJson.getInt(m, "attempt", 1),
                    MiniJson.getString(m, "response", ""), MiniJson.getString(m, "report", ""), MiniJson.getString(m, "payload", ""));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String clip(String s, int n) {
        return s == null ? "" : s.length() <= n ? s : s.substring(0, n) + "...";
    }

    private static String esc(String s) {
        return MiniJson.escapeString(s == null ? "" : s);
    }

    public record Entry(long time, String taskId, String taskName, String kind, String title, String host, int status,
                        long millis, int attempt, String response, String reportPath, String payload) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }
}
