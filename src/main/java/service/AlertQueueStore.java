package service;

import model.EventKind;
import util.MiniJson;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Alerts waiting to be delivered, one small JSON file each in {@code <data folder>/alert-queue}, so a restart of the service
 * does not lose them. A file is written when the alert is queued and deleted when it has been delivered or given up on.
 * The webhook address is not stored here; the current setting is read at delivery time.
 */
final class AlertQueueStore {

    static final String DIR = "alert-queue";
    private final File dir;
    private final AtomicLong seq = new AtomicLong();

    AlertQueueStore(File dataDir) { this.dir = new File(dataDir, DIR); }

    /** One stored alert and the file that holds it. */
    record Job(File file, TeamsCard.Alert alert, long queuedAt) {}

    File save(TeamsCard.Alert a, long now) throws IOException {
        Files.createDirectories(dir.toPath());
        Map<String, String> m = new LinkedHashMap<>();
        m.put("queuedAt", Long.toString(now));
        m.put("kind", a.kind() == null ? "" : a.kind().name());
        m.put("recovery", Boolean.toString(a.recovery()));
        m.put("taskId", nz(a.taskId())); m.put("taskName", nz(a.taskName())); m.put("taskType", nz(a.taskType()));
        m.put("status", nz(a.status())); m.put("message", nz(a.message())); m.put("details", nz(a.details()));
        m.put("suggestedAction", nz(a.suggestedAction()));
        m.put("startedAt", a.startedAt() == null ? "" : a.startedAt().toString());
        m.put("endedAt", a.endedAt() == null ? "" : a.endedAt().toString());
        m.put("durationMs", Long.toString(a.durationMs()));
        m.put("trigger", nz(a.trigger())); m.put("runKey", nz(a.runKey())); m.put("host", nz(a.host()));
        m.put("suppressed", Integer.toString(a.suppressed()));
        m.put("raisedAt", a.raisedAt() == null ? "" : a.raisedAt().toString());
        String name = String.format("%019d-%06d.json", now, seq.incrementAndGet() % 1_000_000);
        File tmp = new File(dir, name + ".tmp"), f = new File(dir, name);
        Files.writeString(tmp.toPath(), MiniJson.writeObject(m), StandardCharsets.UTF_8);
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return f;
    }

    void delete(File f) {
        if (f != null) f.delete();
    }

    /** Everything waiting, oldest first. An unreadable file is removed. */
    List<Job> load() {
        List<Job> out = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return out;
        Arrays.sort(files);
        for (File f : files) {
            try {
                Map<String, Object> m = MiniJson.parseObject(Files.readString(f.toPath(), StandardCharsets.UTF_8));
                EventKind kind = EventKind.parse(MiniJson.getString(m, "kind", ""));
                TeamsCard.Alert a = new TeamsCard.Alert(kind, MiniJson.getBoolean(m, "recovery", false),
                        MiniJson.getString(m, "taskId", ""), MiniJson.getString(m, "taskName", ""), MiniJson.getString(m, "taskType", ""),
                        MiniJson.getString(m, "status", ""), MiniJson.getString(m, "message", ""), MiniJson.getString(m, "details", ""),
                        MiniJson.getString(m, "suggestedAction", ""), time(m, "startedAt"), time(m, "endedAt"),
                        longOf(MiniJson.getString(m, "durationMs", "0")), MiniJson.getString(m, "trigger", ""),
                        MiniJson.getString(m, "runKey", ""), MiniJson.getString(m, "host", ""), null, null,
                        (int) longOf(MiniJson.getString(m, "suppressed", "0")), time(m, "raisedAt") == null ? LocalDateTime.now() : time(m, "raisedAt"));
                out.add(new Job(f, a, longOf(MiniJson.getString(m, "queuedAt", "0"))));
            } catch (Exception e) {
                f.delete();
            }
        }
        return out;
    }

    private static LocalDateTime time(Map<String, Object> m, String key) {
        String s = MiniJson.getString(m, key, "");
        try { return s.isEmpty() ? null : LocalDateTime.parse(s); } catch (RuntimeException e) { return null; }
    }

    private static long longOf(String s) {
        try { return Long.parseLong(s.trim()); } catch (RuntimeException e) { return 0; }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
