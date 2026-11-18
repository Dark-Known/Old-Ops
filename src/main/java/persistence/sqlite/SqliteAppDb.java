package persistence.sqlite;

import persistence.AppDb;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link AppDb} as two small tables in {@code app.db}: {@code app_kv} (named text values) and {@code alert_queue}.
 * Each call opens a short-lived connection (SQLite opens in microseconds, WAL lets the service and the window share the file).
 */
public final class SqliteAppDb implements AppDb {

    private final File dataDir;

    public SqliteAppDb(File dataDir) { this.dataDir = dataDir; }

    private Connection open() throws SQLException {
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

    @Override public String get(String name) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement("SELECT value FROM app_kv WHERE name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        } catch (SQLException e) { return null; }
    }

    @Override public void put(String name, String value) throws IOException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("INSERT INTO app_kv(name,value,updated_at) VALUES(?,?,?) "
                     + "ON CONFLICT(name) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at")) {
            ps.setString(1, name); ps.setString(2, value); ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) { throw new IOException("Could not save " + name + " to app.db: " + e.getMessage(), e); }
    }

    @Override public long stamp(String name) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement("SELECT updated_at, length(value) FROM app_kv WHERE name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) * 31 + rs.getLong(2) : -1; }
        } catch (SQLException e) { return -2; }
    }

    @Override public long enqueueAlert(long queuedAt, String json) throws IOException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("INSERT INTO alert_queue(queued_at,json) VALUES(?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, queuedAt); ps.setString(2, json); ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) { return k.next() ? k.getLong(1) : -1; }
        } catch (SQLException e) { throw new IOException(e.getMessage(), e); }
    }

    @Override public void deleteAlert(long id) {
        if (id < 0) return;
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement("DELETE FROM alert_queue WHERE id=?")) {
            ps.setLong(1, id); ps.executeUpdate();
        } catch (SQLException ignored) { }
    }

    @Override public List<QueuedAlert> pendingAlerts() {
        List<QueuedAlert> out = new ArrayList<>();
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, queued_at, json FROM alert_queue ORDER BY queued_at, id")) {
            while (rs.next()) out.add(new QueuedAlert(rs.getLong(1), rs.getLong(2), rs.getString(3)));
        } catch (SQLException e) { /* what was read */ }
        return out;
    }
}
