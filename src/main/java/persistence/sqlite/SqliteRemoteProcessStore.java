package persistence.sqlite;

import persistence.RemoteProcessStore;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** {@link RemoteProcessStore} as the table {@code remote_processes} in {@code app.db}; one row per process. */
public final class SqliteRemoteProcessStore implements RemoteProcessStore {

    static final String CREATE_SQL = "CREATE TABLE IF NOT EXISTS remote_processes ("
            + "listener_key TEXT NOT NULL, host TEXT NOT NULL, user TEXT NOT NULL, role TEXT NOT NULL, "
            + "pid INTEGER NOT NULL, start_filetime INTEGER NOT NULL, recorded_at INTEGER NOT NULL, "
            + "PRIMARY KEY (listener_key, pid, start_filetime))";
    static final String INSERT_SQL = "INSERT OR IGNORE INTO remote_processes "
            + "(listener_key, host, user, role, pid, start_filetime, recorded_at) VALUES (?,?,?,?,?,?,?)";
    static final String DELETE_SQL = "DELETE FROM remote_processes WHERE listener_key=? AND pid=? AND start_filetime=?";
    static final String PURGE_SQL = "DELETE FROM remote_processes WHERE recorded_at < ?";
    static final String SELECT_SQL = "SELECT listener_key, host, user, role, pid, start_filetime, recorded_at "
            + "FROM remote_processes ORDER BY recorded_at, rowid";

    private final File dbFile;

    public SqliteRemoteProcessStore(File dataDir) { this.dbFile = new File(dataDir, "app.db"); }

    private Connection open() throws SQLException {
        Connection c = Db.open(dbFile);
        try (Statement st = c.createStatement()) {
            st.execute(CREATE_SQL);
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        return c;
    }

    @Override public List<Row> loadAll() throws IOException {
        List<Row> out = new ArrayList<>();
        try (Connection c = open(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(SELECT_SQL)) {
            while (rs.next()) {
                out.add(new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getLong(5), rs.getLong(6), rs.getLong(7)));
            }
        } catch (SQLException e) { throw new IOException(e.getMessage(), e); }
        return out;
    }

    @Override public void insert(Collection<Row> rows) throws IOException {
        try (Connection c = open()) {
            Db.inTransaction(c, tx -> {
                try (PreparedStatement ps = tx.prepareStatement(INSERT_SQL)) {
                    for (Row r : rows) {
                        ps.setString(1, r.listenerKey());
                        ps.setString(2, r.host());
                        ps.setString(3, r.user());
                        ps.setString(4, r.role());
                        ps.setLong(5, r.pid());
                        ps.setLong(6, r.startFileTime());
                        ps.setLong(7, r.recordedAt());
                        ps.executeUpdate();
                    }
                }
                return null;
            });
        } catch (SQLException e) { throw new IOException(e.getMessage(), e); }
    }

    @Override public void delete(Collection<Row> rows) throws IOException {
        try (Connection c = open()) {
            Db.inTransaction(c, tx -> {
                try (PreparedStatement ps = tx.prepareStatement(DELETE_SQL)) {
                    for (Row r : rows) {
                        ps.setString(1, r.listenerKey());
                        ps.setLong(2, r.pid());
                        ps.setLong(3, r.startFileTime());
                        ps.executeUpdate();
                    }
                }
                return null;
            });
        } catch (SQLException e) { throw new IOException(e.getMessage(), e); }
    }

    @Override public void purgeOlderThan(long recordedAtMillis) throws IOException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(PURGE_SQL)) {
            ps.setLong(1, recordedAtMillis);
            ps.executeUpdate();
        } catch (SQLException e) { throw new IOException(e.getMessage(), e); }
    }
}
