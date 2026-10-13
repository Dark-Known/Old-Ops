package service;

import model.EventKind;
import model.TaskRunRecord;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The app's own background-thread log (job started / finished / failed / slow, live feeds, UI
 * stalls). It lives in its <b>own database file</b> ({@code background_log.db}) so this
 * high-volume, disposable data can be purged, vacuumed or even deleted without touching the run
 * history that audits scheduled work.
 *
 * <p>Both the GUI and the Daemon write to it; SQLite's file locking (WAL mode, with a busy
 * timeout) serialises them, and {@code synchronized} serialises threads within one process.
 */
public class BackgroundLogService {
    private static final Logger log = Logger.getLogger(BackgroundLogService.class.getName());
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public static final String FILE_NAME = "background_log.db";
    static final String TABLE = "background_events";
    /** Pseudo task id shared by every row, so the monitor can tell these are not about a scheduled task. */
    public static final String TASK_ID = "BACKGROUND";

    private static volatile String processLabel = "GUI";
    /** Names this process in the log ("GUI" or "Daemon"); set once at start-up. */
    public static void setProcessLabel(String label) { processLabel = label; }

    private final File dbFile;
    private final Connection conn;
    private final List<Consumer<TaskRunRecord>> listeners = new CopyOnWriteArrayList<>();

    public BackgroundLogService(String dataDir) {
        this.dbFile = new File(dataDir, FILE_NAME);
        Connection c = null;
        try {
            Class.forName("org.sqlite.JDBC");
            c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "kind TEXT NOT NULL,"
                        + "name TEXT NOT NULL,"
                        + "status TEXT NOT NULL,"
                        + "message TEXT,"
                        + "details TEXT,"
                        + "started_at TEXT NOT NULL,"
                        + "ended_at TEXT NOT NULL,"
                        + "duration_ms INTEGER,"
                        + "source TEXT)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_bgevents_started ON " + TABLE + "(started_at)");
                // Covering index for the monitor's list query: it never has to read the (large) details pages.
                st.execute("CREATE INDEX IF NOT EXISTS idx_bgevents_summary ON " + TABLE
                        + "(started_at, id, kind, name, status, message, ended_at, duration_ms, source)");
                st.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)");
            }
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to open/initialize background log database", e);
            c = null;
        }
        this.conn = c;
    }

    public boolean isOpen() { return conn != null; }

    public File getDbFile() { return dbFile; }

    public void addListener(Consumer<TaskRunRecord> l) { listeners.add(l); }

    public void removeListener(Consumer<TaskRunRecord> l) { listeners.remove(l); }

    // ── Writing ─────────────────────────────────────────────────────────────

    /** Records one background event. Returns its id, or -1 if it could not be stored. */
    public long record(EventKind kind, String name, TaskRunRecord.Status status, String message, String details,
                       LocalDateTime start, LocalDateTime end) {
        return record(kind, name, status, message, details, start, end, processLabel);
    }

    synchronized long record(EventKind kind, String name, TaskRunRecord.Status status, String message, String details,
                             LocalDateTime start, LocalDateTime end, String source) {
        if (conn == null) return -1;
        LocalDateTime e = end.isBefore(start) ? start : end;
        long ms = java.time.Duration.between(start, e).toMillis();
        long id;
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO " + TABLE
                + " (kind, name, status, message, details, started_at, ended_at, duration_ms, source) VALUES (?,?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, kind.name());
            ps.setString(2, name);
            ps.setString(3, status.name());
            ps.setString(4, message);
            ps.setString(5, details != null ? details : message);
            ps.setString(6, start.format(TS));
            ps.setString(7, e.format(TS));
            ps.setLong(8, ms);
            ps.setString(9, source);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) { id = keys.next() ? keys.getLong(1) : 0; }
        } catch (SQLException ex) {
            log.log(Level.WARNING, "Failed to record background event", ex);
            return -1;
        }
        if (!listeners.isEmpty()) {
            TaskRunRecord rec = toRecord(id, kind.name(), name, status, message, details != null ? details : message,
                    start, e, ms, source);
            for (Consumer<TaskRunRecord> l : listeners) {
                try { l.accept(rec); } catch (Exception ignored) { }
            }
        }
        return id;
    }

    /** Copies rows in from another store (used once to move older rows out of run history). Keeps their timestamps. */
    public synchronized int importRows(List<TaskRunRecord> rows) {
        if (conn == null || rows.isEmpty()) return 0;
        int n = 0;
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO " + TABLE
                    + " (kind, name, status, message, details, started_at, ended_at, duration_ms, source) VALUES (?,?,?,?,?,?,?,?,?)")) {
                for (TaskRunRecord r : rows) {
                    ps.setString(1, r.getEventKind() != null ? r.getEventKind() : EventKind.BACKGROUND_COMPLETED.name());
                    ps.setString(2, r.getTaskName() != null ? r.getTaskName() : "Background task");
                    ps.setString(3, r.getStatus() != null ? r.getStatus().name() : TaskRunRecord.Status.SUCCESS.name());
                    ps.setString(4, r.getReason());
                    ps.setString(5, r.getDetails());
                    ps.setString(6, r.getStartedAt().format(TS));
                    ps.setString(7, r.getEndedAt().format(TS));
                    ps.setLong(8, r.getDurationMs());
                    ps.setString(9, r.getTrigger());
                    ps.addBatch();
                    n++;
                }
                ps.executeBatch();
            }
            conn.commit();
        } catch (SQLException ex) {
            try { conn.rollback(); } catch (SQLException ignored) { }
            log.log(Level.WARNING, "Failed to import background rows", ex);
            return -1;
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) { }
        }
        return n;
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    /** Newest-first rows without the {@code details} text (cheap; served from the covering index). */
    public synchronized List<TaskRunRecord> querySummaries(LocalDateTime from, int limit) {
        List<TaskRunRecord> out = new ArrayList<>();
        if (conn == null) return out;
        String sql = "SELECT id, kind, name, status, message, started_at, ended_at, duration_ms, source FROM " + TABLE
                + (from != null ? " WHERE started_at >= ?" : "") + " ORDER BY started_at DESC, id DESC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            if (from != null) ps.setString(i++, from.format(TS));
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(toRecord(rs.getLong(1), rs.getString(2), rs.getString(3), parseStatus(rs.getString(4)),
                            rs.getString(5), null, LocalDateTime.parse(rs.getString(6), TS),
                            LocalDateTime.parse(rs.getString(7), TS), rs.getLong(8), rs.getString(9)));
                }
            }
        } catch (SQLException ex) {
            log.log(Level.WARNING, "Failed to query background log", ex);
        }
        return out;
    }

    /** One row including its full details text. */
    public synchronized TaskRunRecord getById(long id) {
        if (conn == null) return null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, kind, name, status, message, details, started_at, "
                + "ended_at, duration_ms, source FROM " + TABLE + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return toRecord(rs.getLong(1), rs.getString(2), rs.getString(3), parseStatus(rs.getString(4)),
                        rs.getString(5), rs.getString(6), LocalDateTime.parse(rs.getString(7), TS),
                        LocalDateTime.parse(rs.getString(8), TS), rs.getLong(9), rs.getString(10));
            }
        } catch (SQLException ex) {
            log.log(Level.WARNING, "Failed to read background event " + id, ex);
            return null;
        }
    }

    public synchronized long rowCount() {
        if (conn == null) return 0;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + TABLE)) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException ex) { return 0; }
    }

    /** Changes whenever a row is added or a purge ran, so a poller can skip querying when nothing happened. */
    public synchronized String getChangeToken() {
        if (conn == null) return "closed";
        try (Statement st = conn.createStatement()) {
            long max = 0;
            try (ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(id),0) FROM " + TABLE)) { if (rs.next()) max = rs.getLong(1); }
            String gen = meta("purge_gen");
            return max + ":" + (gen == null ? "0" : gen);
        } catch (SQLException ex) {
            return "error";
        }
    }

    /** On-disk size of this database (file plus write-ahead log). */
    public long sizeBytes() { return SqlitePurge.fileBytes(dbFile); }

    // ── Small key/value store (scheduler bookkeeping such as "last purge time") ────

    public synchronized String meta(String key) {
        if (conn == null) return null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        } catch (SQLException ex) { return null; }
    }

    public synchronized void setMeta(String key, String value) {
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException ex) {
            log.log(Level.WARNING, "Failed to write meta " + key, ex);
        }
    }

    // ── Purging ─────────────────────────────────────────────────────────────

    /** Removes the oldest rows until the policy is met. Safe to call from any thread; see {@link SqlitePurge}. */
    public PurgeResult purge(PurgePolicy policy, BooleanSupplier cancelled) {
        PurgeResult r = SqlitePurge.run("Background log", this, conn, dbFile, List.of(TABLE), policy, cancelled);
        if (!r.failed() && r.deleted() > 0) {
            synchronized (this) {
                String g = meta("purge_gen");
                setMeta("purge_gen", String.valueOf((g == null ? 0 : Long.parseLong(g)) + 1));
            }
        }
        return r;
    }

    public synchronized void close() {
        try { if (conn != null) conn.close(); } catch (SQLException ignored) { }
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    private static TaskRunRecord.Status parseStatus(String s) {
        try { return TaskRunRecord.Status.valueOf(s); } catch (Exception e) { return TaskRunRecord.Status.SUCCESS; }
    }

    private static TaskRunRecord toRecord(long id, String kind, String name, TaskRunRecord.Status status, String message,
                                          String details, LocalDateTime start, LocalDateTime end, long ms, String source) {
        TaskRunRecord r = new TaskRunRecord();
        r.setId(id);
        r.setTaskId(TASK_ID);
        r.setTaskName(name);
        r.setTaskType("BACKGROUND");
        r.setStatus(status);
        r.setReason(message);
        r.setDetails(details);
        r.setStartedAt(start);
        r.setEndedAt(end);
        r.setDurationMs(ms);
        r.setEventKind(kind);
        r.setTrigger(source);
        return r;
    }
}
