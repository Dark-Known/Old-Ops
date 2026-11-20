package persistence.sqlite;

import service.LogFiles;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * The one place that opens SQLite connections for the app.
 *
 * <ul>
 *   <li>{@link #connect} loads the driver, opens the file and returns a connection whose statements are timed: failures and
 *       slow statements go to {@link SqlLog}. Write transactions start with BEGIN IMMEDIATE, so a writer that cannot get the
 *       lock fails at the start (after the busy timeout) instead of half way through.</li>
 *   <li>{@link #applyStandardPragmas} is the tuning every file shares: WAL (readers never block the writer), synchronous
 *       NORMAL, a 5 s busy timeout.</li>
 *   <li>{@link #inTransaction} runs several statements as one unit: all of them are saved, or none.</li>
 * </ul>
 */
public final class Db {

    private Db() {
    }

    /**
     * Several statements that are saved together.
     */
    @FunctionalInterface
    public interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    /**
     * Opens {@code dbFile} (created if missing) with timed statements. The caller closes it.
     */
    public static Connection connect(File dbFile) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite driver not available", e);
        }
        File dir = dbFile.getAbsoluteFile().getParentFile();
        if (dir != null) {
            dir.mkdirs();
            SqlLog.useDataDir(dir);
        }
        Properties p = new Properties();
        p.setProperty("transaction_mode", "IMMEDIATE");
        Connection raw = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath(), p);
        return logged(raw, dbFile.getName());
    }

    /**
     * {@link #connect} plus the standard pragmas.
     */
    public static Connection open(File dbFile) throws SQLException {
        Connection c = connect(dbFile);
        try {
            applyStandardPragmas(c);
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        return c;
    }

    public static void applyStandardPragmas(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA busy_timeout=5000");
        }
    }

    /**
     * Runs {@code work} as one transaction: committed if it returns, rolled back (and logged) if it throws. The connection's
     * auto-commit setting is put back afterwards.
     */
    public static <T> T inTransaction(Connection c, Work<T> work) throws SQLException {
        boolean previous = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            T result = work.run(c);
            c.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            try {
                c.rollback();
            } catch (SQLException rollbackFailed) {
                e.addSuppressed(rollbackFailed);
            }
            SqlLog.rolledBack(nameOf(c), e);
            throw e;
        } finally {
            try {
                c.setAutoCommit(previous);
            } catch (SQLException ignored) { /* connection is unusable anyway */ }
        }
    }

    private static String nameOf(Connection c) {
        return c instanceof Named n ? n.dbName() : "db";
    }

    /**
     * Implemented by the connections {@link #logged} returns, so a log line can say which file it is about.
     */
    interface Named {
        String dbName();
    }

    /**
     * Wraps a connection so its statements are timed and failures are logged. Package-visible for tests.
     */
    static Connection logged(Connection raw, String dbName) {
        InvocationHandler h = (proxy, m, args) -> {
            if (m.getName().equals("dbName") && m.getDeclaringClass() == Named.class) return dbName;
            long t0 = System.nanoTime();
            Object r;
            try {
                r = call(raw, m, args);
            } catch (Throwable t) {
                if (m.getName().equals("commit")) SqlLog.record(dbName, "COMMIT", ms(t0), t);
                throw t;
            }
            if (m.getName().equals("commit")) SqlLog.record(dbName, "COMMIT", ms(t0), null);
            if (r instanceof PreparedStatement ps && m.getName().equals("prepareStatement")) {
                return wrap(ps, dbName, args != null && args.length > 0 ? String.valueOf(args[0]) : "", PreparedStatement.class);
            }
            if (r instanceof Statement st && m.getName().equals("createStatement")) {
                return wrap(st, dbName, null, Statement.class);
            }
            return r;
        };
        return (Connection) Proxy.newProxyInstance(Db.class.getClassLoader(), new Class<?>[]{Connection.class, Named.class}, h);
    }

    private static Object wrap(Object target, String dbName, String preparedSql, Class<?> iface) {
        InvocationHandler h = (proxy, m, args) -> {
            if (!m.getName().startsWith("execute")) return call(target, m, args);
            String sql = preparedSql != null ? preparedSql
                    : args != null && args.length > 0 && args[0] instanceof String s ? s : "(batch)";
            long t0 = System.nanoTime();
            try {
                Object r = call(target, m, args);
                SqlLog.record(dbName, sql, ms(t0), null);
                return r;
            } catch (Throwable t) {
                SqlLog.record(dbName, sql, ms(t0), t);
                throw t;
            }
        };
        return Proxy.newProxyInstance(Db.class.getClassLoader(), new Class<?>[]{iface}, h);
    }

    private static Object call(Object target, Method m, Object[] args) throws Throwable {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
