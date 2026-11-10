package service;

import model.EventKind;
import util.MiniJson;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Alerts waiting to be delivered, rows of the {@code alert_queue} table in app.db, so a restart of the service
 * does not lose them. A row is written when the alert is queued and deleted when it has been delivered or given up on.
 * The webhook address is not stored here; the current setting is read at delivery time.
 */
final class AlertQueueStore {

    static final String DIR = "alert-queue"; // pre-database location, imported once
    private final File dataDir;

    AlertQueueStore(File dataDir) {
        this.dataDir = dataDir;
        importLegacyFolder();
    }

    private static long insert(Connection c, String json, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO alert_queue(queued_at,json) VALUES(?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, now);
            ps.setString(2, json);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                return k.next() ? k.getLong(1) : -1;
            }
        }
    }

    private static String toJson(TeamsCard.Alert a) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("kind", a.kind() == null ? "" : a.kind().name());
        m.put("recovery", Boolean.toString(a.recovery()));
        m.put("taskId", nz(a.taskId()));
        m.put("taskName", nz(a.taskName()));
        m.put("taskType", nz(a.taskType()));
        m.put("status", nz(a.status()));
        m.put("message", nz(a.message()));
        m.put("details", nz(a.details()));
        m.put("suggestedAction", nz(a.suggestedAction()));
        m.put("startedAt", a.startedAt() == null ? "" : a.startedAt().toString());
        m.put("endedAt", a.endedAt() == null ? "" : a.endedAt().toString());
        m.put("durationMs", Long.toString(a.durationMs()));
        m.put("trigger", nz(a.trigger()));
        m.put("runKey", nz(a.runKey()));
        m.put("host", nz(a.host()));
        m.put("suppressed", Integer.toString(a.suppressed()));
        m.put("raisedAt", a.raisedAt() == null ? "" : a.raisedAt().toString());
        return MiniJson.writeObject(m);
    }

    private static LocalDateTime time(Map<String, Object> m, String key) {
        String s = MiniJson.getString(m, key, "");
        try {
            return s.isEmpty() ? null : LocalDateTime.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long longOf(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    long save(TeamsCard.Alert a, long now) throws IOException {
        try (Connection c = AppDb.open(dataDir)) {
            return insert(c, toJson(a), now);
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    void delete(long id) {
        if (id < 0) return;
        try (Connection c = AppDb.open(dataDir); PreparedStatement ps = c.prepareStatement("DELETE FROM alert_queue WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    /**
     * Everything waiting, oldest first. An unreadable row is removed.
     */
    List<Job> load() {
        List<Job> out = new ArrayList<>();
        List<Long> bad = new ArrayList<>();
        try (Connection c = AppDb.open(dataDir); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, queued_at, json FROM alert_queue ORDER BY queued_at, id")) {
            while (rs.next()) {
                long id = rs.getLong(1);
                try {
                    Map<String, Object> m = MiniJson.parseObject(rs.getString(3));
                    EventKind kind = EventKind.parse(MiniJson.getString(m, "kind", ""));
                    TeamsCard.Alert a = new TeamsCard.Alert(kind, MiniJson.getBoolean(m, "recovery", false),
                            MiniJson.getString(m, "taskId", ""), MiniJson.getString(m, "taskName", ""), MiniJson.getString(m, "taskType", ""),
                            MiniJson.getString(m, "status", ""), MiniJson.getString(m, "message", ""), MiniJson.getString(m, "details", ""),
                            MiniJson.getString(m, "suggestedAction", ""), time(m, "startedAt"), time(m, "endedAt"),
                            longOf(MiniJson.getString(m, "durationMs", "0")), MiniJson.getString(m, "trigger", ""),
                            MiniJson.getString(m, "runKey", ""), MiniJson.getString(m, "host", ""), null, null,
                            (int) longOf(MiniJson.getString(m, "suppressed", "0")), time(m, "raisedAt") == null ? LocalDateTime.now() : time(m, "raisedAt"));
                    out.add(new Job(id, a, rs.getLong(2)));
                } catch (Exception e) {
                    bad.add(id);
                }
            }
        } catch (SQLException e) {
            return out;
        }
        for (long id : bad) delete(id);
        return out;
    }

    /**
     * Alerts an older version left as one file each: copied into the table, then the folder is removed.
     */
    private void importLegacyFolder() {
        File dir = new File(dataDir, DIR);
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return;
        Arrays.sort(files);
        try (Connection c = AppDb.open(dataDir)) {
            for (File f : files) {
                try {
                    String json = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                    insert(c, json, longOf(MiniJson.getString(MiniJson.parseObject(json), "queuedAt", "0")));
                } catch (Exception ignored) { /* unreadable: dropped with the folder */ }
                f.delete();
            }
        } catch (SQLException e) {
            return;
        }
        File[] rest = dir.listFiles();
        if (rest != null) for (File f : rest) f.delete();
        dir.delete();
    }

    /**
     * One stored alert and its id in the queue table.
     */
    record Job(long id, TeamsCard.Alert alert, long queuedAt) {
    }
}
