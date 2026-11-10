package service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Small settings and queue tables that live in {@code app.db} next to the tasks, so the data folder does not collect a loose
 * file per setting: {@code app_kv} (named text values) and {@code alert_queue} (alerts waiting for delivery).
 * Each call opens a short-lived connection (SQLite opens in microseconds, WAL lets the service and the GUI share the file).
 */
public final class AppDb {

    private AppDb() {}

    static Connection open(File dataDir) throws SQLException {
        try { Class.forName("org.sqlite.JDBC"); } catch (ClassNotFoundException e) { throw new SQLException(e); }
        dataDir.mkdirs();
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + new File(dataDir, "app.db").getAbsolutePath());
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("CREATE TABLE IF NOT EXISTS app_kv (name TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS alert_queue (id INTEGER PRIMARY KEY AUTOINCREMENT, queued_at INTEGER NOT NULL, json TEXT NOT NULL)");
        }
        return c;
    }

    /** The stored text, or null when there is none. */
    public static String get(File dataDir, String name) {
        try (Connection c = open(dataDir); PreparedStatement ps = c.prepareStatement("SELECT value FROM app_kv WHERE name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        } catch (SQLException e) { return null; }
    }

    public static void put(File dataDir, String name, String value) throws IOException {
        try (Connection c = open(dataDir);
             PreparedStatement ps = c.prepareStatement("INSERT INTO app_kv(name,value,updated_at) VALUES(?,?,?) "
                     + "ON CONFLICT(name) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at")) {
            ps.setString(1, name); ps.setString(2, value); ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) { throw new IOException("Could not save " + name + " to app.db: " + e.getMessage(), e); }
    }

    /** Changes whenever the value is rewritten; -1 when absent. Used to notice edits made by the other process. */
    static long stamp(File dataDir, String name) {
        try (Connection c = open(dataDir); PreparedStatement ps = c.prepareStatement("SELECT updated_at, length(value) FROM app_kv WHERE name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) * 31 + rs.getLong(2) : -1; }
        } catch (SQLException e) { return -2; }
    }

    /** Reads a legacy file's text and renames it {@code .migrated}, or returns null when there is no such file. */
    static String takeLegacyFile(File f) {
        if (!f.isFile()) return null;
        try {
            String s = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            Files.move(f.toPath(), new File(f.getParentFile(), f.getName() + ".migrated").toPath(), StandardCopyOption.REPLACE_EXISTING);
            return s;
        } catch (IOException e) { return null; }
    }
}
