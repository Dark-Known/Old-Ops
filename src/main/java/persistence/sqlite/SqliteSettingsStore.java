package persistence.sqlite;

import persistence.SettingsStore;
import util.MiniJson;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Settings as a small {@code settings} table in {@code app.db}, the same shared per-machine SQLite file the
 * tasks and credentials live in. Holds its own connection (one per data folder, shared by every caller in
 * the process); older {@code app-settings.db} / {@code app-settings.json} files are imported once.
 */
public final class SqliteSettingsStore implements SettingsStore {

    private static final Logger log = Logger.getLogger(SqliteSettingsStore.class.getName());
    private static final String DB_FILE_NAME = "app.db";
    private static final String LEGACY_STANDALONE_DB_FILE_NAME = "app-settings.db";
    private static final String LEGACY_JSON_FILE_NAME = "app-settings.json";

    private static final ConcurrentHashMap<String, SqliteSettingsStore> INSTANCES = new ConcurrentHashMap<>();

    /** The shared store for a data folder (one connection per folder, created on first use). */
    public static SqliteSettingsStore forDataDir(File dataDir) {
        String key = dataDir.getAbsoluteFile().toPath().normalize().toString();
        return INSTANCES.computeIfAbsent(key, k -> new SqliteSettingsStore(dataDir));
    }

    private final Object lock = new Object();
    private final File dataDir;
    private volatile Connection conn;
    // Set once, in connection()'s catch block, if the connection attempt fails, so the UI can say why.
    private volatile String lastConnectionError;

    public SqliteSettingsStore(File dataDir) { this.dataDir = dataDir; }

    /**
     * Tunes this connection for a desktop app with three independent writers
     * sharing one app.db file — see {@code SqliteTaskRepository}'s
     * {@code applyPragmas} javadoc for the full reasoning (WAL mode,
     * synchronous=NORMAL, and a busy_timeout). This is the third and last of
     * the three connections onto the same file that previously ran with none
     * of these set at all.
     */
    private static void applyPragmas(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA busy_timeout=5000");
        }
    }

    private Connection connection() {
        if (conn != null) return conn;
        synchronized (lock) {
            if (conn != null) return conn;
            File dir = dataDir;
            dir.mkdirs();
            File dbFile = new File(dir, DB_FILE_NAME);
            try {
                Class.forName("org.sqlite.JDBC");
                Connection c = Db.connect(dbFile);
                applyPragmas(c);
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT)");
                }
                conn = c;
                migrateLegacyStandaloneDbIfPresent(dir, c);
                migrateLegacyJsonIfPresent(dir, c);
            } catch (Exception e) {
                log.log(Level.SEVERE, "Failed to open/initialize app-settings database", e);
                lastConnectionError = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            return conn;
        }
    }

    /**
     * One-time import from a legacy standalone {@code app-settings.db} file
     * (from before app settings and credentials were merged into one
     * {@code app.db}), run only if the {@code settings} table in the new
     * file is currently empty. Uses {@code ATTACH DATABASE} to copy rows
     * directly. The old file is left on disk afterward (renamed with a
     * {@code .migrated} suffix) purely as a safety net.
     */
    private static void migrateLegacyStandaloneDbIfPresent(File dir, Connection c) {
        File legacy = new File(dir, LEGACY_STANDALONE_DB_FILE_NAME);
        if (!legacy.exists()) return;

        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM settings")) {
            if (rs.next() && rs.getInt(1) > 0) return; // already has data — don't overwrite
        } catch (SQLException e) {
            log.log(Level.WARNING, "Could not check settings table before legacy DB migration", e);
            return;
        }

        try (Statement st = c.createStatement()) {
            st.execute("ATTACH DATABASE '" + legacy.getAbsolutePath().replace("'", "''") + "' AS legacy_settings");
            int migrated;
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM legacy_settings.settings")) {
                migrated = rs.next() ? rs.getInt(1) : 0;
            }
            st.execute("INSERT INTO settings SELECT * FROM legacy_settings.settings");
            st.execute("DETACH DATABASE legacy_settings");
            if (migrated > 0) {
                log.info("Migrated " + migrated + " setting(s) from legacy app-settings.db into app.db");
            }
            File renamed = new File(legacy.getParentFile(), legacy.getName() + ".migrated");
            legacy.renameTo(renamed);
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to migrate legacy app-settings.db into app.db", e);
        }
    }

    /**
     * One-time import of the legacy app-settings.json (used by versions
     * prior to the move to SQLite) into the settings table, so upgrading in
     * place doesn't silently reset every saved setting back to defaults.
     * Runs only if the table is currently empty; the JSON file itself is
     * left in place afterward (renamed with a .migrated suffix) rather than
     * deleted, purely as a safety net.
     */
    private static void migrateLegacyJsonIfPresent(File dir, Connection c) {
        File legacy = new File(dir, LEGACY_JSON_FILE_NAME);
        if (!legacy.exists()) return;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM settings")) {
            if (rs.next() && rs.getInt(1) > 0) return; // already has data — don't overwrite
        } catch (SQLException e) {
            log.log(Level.WARNING, "Could not check settings table before JSON migration", e);
            return;
        }
        try {
            String json = Files.readString(legacy.toPath(), StandardCharsets.UTF_8);
            Map<String, Object> parsed = MiniJson.parseObject(json);
            if (!parsed.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO settings (key, value) VALUES (?, ?) " +
                        "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
                    for (Map.Entry<String, Object> e : parsed.entrySet()) {
                        if (e.getValue() == null) continue;
                        ps.setString(1, e.getKey());
                        ps.setString(2, String.valueOf(e.getValue()));
                        ps.executeUpdate();
                    }
                }
                log.info("Migrated " + parsed.size() + " setting(s) from legacy app-settings.json into " + DB_FILE_NAME);
            }
            File renamed = new File(dir, LEGACY_JSON_FILE_NAME + ".migrated");
            legacy.renameTo(renamed);
        } catch (Exception e) {
            log.log(Level.WARNING, "Failed to migrate legacy app-settings.json", e);
        }
    }

    // ─── SettingsStore ───────────────────────────────────────────────────────

    @Override
    public Map<String, String> readAll() {
        Map<String, String> result = new LinkedHashMap<>();
        Connection c = connection();
        if (c == null) return result;
        synchronized (lock) {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT key, value FROM settings")) {
                while (rs.next()) {
                    String v = rs.getString("value");
                    if (v != null) result.put(rs.getString("key"), v);
                }
            } catch (SQLException e) {
                log.log(Level.WARNING, "Failed to read app settings from database", e);
            }
        }
        return result;
    }

    @Override
    public boolean replaceAll(Map<String, String> values) {
        Connection c = connection();
        if (c == null) return false;
        synchronized (lock) {
            writeThrough(c, values);
        }
        return true;
    }

    @Override
    public boolean isConnected() {
        Connection c = connection();
        if (c == null) return false;
        try {
            return c.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public String connectionError() {
        if (isConnected()) return null;
        return lastConnectionError;
    }

    @Override
    public String location() {
        return new File(dataDir, DB_FILE_NAME).getAbsolutePath();
    }

    /** Replaces the entire settings table contents with {@code values}, in one transaction. */
    private static void writeThrough(Connection c, Map<String, String> values) {
        try {
            boolean priorAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try (Statement del = c.createStatement()) {
                del.execute("DELETE FROM settings");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO settings (key, value) VALUES (?, ?)")) {
                for (Map.Entry<String, String> e : values.entrySet()) {
                    ps.setString(1, e.getKey());
                    ps.setString(2, e.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
            c.setAutoCommit(priorAutoCommit);
        } catch (SQLException ex) {
            throw new RuntimeException("Could not save settings to " + DB_FILE_NAME + ": " + ex.getMessage(), ex);
        }
    }
}
