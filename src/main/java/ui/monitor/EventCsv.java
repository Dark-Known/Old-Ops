package ui.monitor;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Writes events to RFC 4180 CSV — one row per event, regardless of how they are grouped on screen. */
public final class EventCsv {

    private EventCsv() {}

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String[] HEADER = {
            "time", "task", "task_type", "category", "event", "level", "status", "trigger", "run_id",
            "duration_ms", "summary", "failure_category", "retryable", "suggested_action"};

    public static void write(Path file, List<MonitorEvent> events) throws IOException {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write('\uFEFF'); // BOM so Excel opens it as UTF-8
            w.write(String.join(",", HEADER));
            w.write("\r\n");
            for (MonitorEvent e : events) {
                String[] f = {
                        e.time().format(ISO), e.taskName(), e.taskType(), e.kind().category().label(),
                        e.kind().label(), e.severity().label(), e.status().name(), e.trigger(), e.runKey(),
                        e.durationMs() >= 0 ? String.valueOf(e.durationMs()) : "", e.summary(),
                        e.failureCategory() != null ? e.failureCategory().name() : "",
                        e.failureCategory() != null ? String.valueOf(e.retryable()) : "", e.suggestedAction()};
                for (int i = 0; i < f.length; i++) {
                    if (i > 0) w.write(',');
                    w.write(quote(f[i]));
                }
                w.write("\r\n");
            }
        }
    }

    static String quote(String s) {
        if (s == null) return "";
        // Guard against spreadsheet formula injection from task names / messages.
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
