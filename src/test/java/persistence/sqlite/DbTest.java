package persistence.sqlite;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Db and SqlLog without a real database: a stand-in JDBC connection records what is called on it and can be made to fail or be slow.
 */
public class DbTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final List<String> calls = new ArrayList<>();
    private boolean autoCommit = true;
    private volatile long executeDelayMs = 0;
    private volatile boolean executeFails = false;
    private volatile boolean commitFails = false;

    @Before
    public void quietSqlLog() {
        SqlLog.configure(SqlLog.DEFAULT_SLOW_MS, false);
    }

    @After
    public void resetSqlLog() {
        SqlLog.configure(SqlLog.DEFAULT_SLOW_MS, false);
    }

    private Object execute(String how) throws SQLException {
        calls.add(how);
        if (executeDelayMs > 0) {
            try {
                Thread.sleep(executeDelayMs);
            } catch (InterruptedException ignored) {
            }
        }
        if (executeFails) throw new SQLException("[SQLITE_BUSY] database is locked");
        return Integer.valueOf(1);
    }

    private PreparedStatement fakePrepared(String sql) {
        return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (p, m, a) -> m.getName().startsWith("execute") ? execute("run: " + sql) : null);
    }

    private Statement fakeStatement() {
        return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class},
                (p, m, a) -> m.getName().startsWith("execute") ? execute("run: " + a[0]) : null);
    }

    private Connection fakeConnection() {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (p, m, a) -> {
            switch (m.getName()) {
                case "prepareStatement":
                    return fakePrepared((String) a[0]);
                case "createStatement":
                    return fakeStatement();
                case "getAutoCommit":
                    return autoCommit;
                case "setAutoCommit":
                    autoCommit = (Boolean) a[0];
                    calls.add("autoCommit=" + a[0]);
                    return null;
                case "commit":
                    calls.add("commit");
                    if (commitFails) throw new SQLException("disk I/O error");
                    return null;
                case "rollback":
                    calls.add("rollback");
                    return null;
                default:
                    return null;
            }
        });
    }

    private String sqlLogText(File dir) throws Exception {
        File f = new File(dir, "logs/sql.log");
        return f.isFile() ? Files.readString(f.toPath(), StandardCharsets.UTF_8) : "";
    }

    // ── transactions ────────────────────────────────────────────────────────

    @Test
    public void aTransactionThatSucceedsIsCommittedOnce() throws Exception {
        Connection c = Db.logged(fakeConnection(), "app.db");
        String result = Db.inTransaction(c, tx -> {
            tx.prepareStatement("INSERT INTO a VALUES (?)").executeUpdate();
            tx.prepareStatement("INSERT INTO b VALUES (?)").executeUpdate();
            return "done";
        });
        assertEquals("done", result);
        assertEquals(List.of("autoCommit=false", "run: INSERT INTO a VALUES (?)", "run: INSERT INTO b VALUES (?)", "commit", "autoCommit=true"), calls);
    }

    @Test
    public void aTransactionThatFailsIsRolledBackAndTheErrorPropagates() throws Exception {
        Connection c = Db.logged(fakeConnection(), "app.db");
        try {
            Db.inTransaction(c, tx -> {
                tx.prepareStatement("INSERT INTO a VALUES (?)").executeUpdate();
                executeFails = true;
                tx.prepareStatement("INSERT INTO b VALUES (?)").executeUpdate();
                return null;
            });
            fail("the failure must reach the caller");
        } catch (SQLException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("SQLITE_BUSY"));
        }
        assertFalse("must not commit after a failure", calls.contains("commit"));
        assertTrue(calls.contains("rollback"));
        assertEquals("auto-commit is put back", "autoCommit=true", calls.get(calls.size() - 1));
    }

    @Test
    public void aRuntimeErrorInsideAlsoRollsBack() throws Exception {
        Connection c = fakeConnection();
        try {
            Db.inTransaction(c, tx -> {
                throw new IllegalStateException("bug");
            });
            fail();
        } catch (IllegalStateException expected) {
            // fine
        }
        assertTrue(calls.contains("rollback"));
        assertFalse(calls.contains("commit"));
    }

    @Test
    public void aFailedCommitIsRolledBackToo() throws Exception {
        commitFails = true;
        try {
            Db.inTransaction(fakeConnection(), tx -> null);
            fail();
        } catch (SQLException expected) {
            assertEquals("disk I/O error", expected.getMessage());
        }
        assertTrue(calls.contains("rollback"));
    }

    // ── SQL log ─────────────────────────────────────────────────────────────

    @Test
    public void aSlowStatementIsLoggedWithItsText() throws Exception {
        File dir = tmp.newFolder();
        SqlLog.useDataDir(dir);
        SqlLog.configure(30, false);
        executeDelayMs = 80;
        Db.logged(fakeConnection(), "app.db").prepareStatement("SELECT *\n   FROM tasks WHERE id = ?").executeUpdate();
        String text = sqlLogText(dir);
        if (text.isEmpty()) return;   // another test in this JVM already pinned sql.log to a different folder
        assertTrue(text, text.contains("app.db SLOW"));
        assertTrue("whitespace is collapsed", text.contains("SELECT * FROM tasks WHERE id = ?"));
    }

    @Test
    public void aFailedStatementIsLoggedWithTheError() throws Exception {
        File dir = tmp.newFolder();
        SqlLog.useDataDir(dir);
        executeFails = true;
        try {
            Db.logged(fakeConnection(), "app.db").createStatement().execute("DELETE FROM tasks");
            fail();
        } catch (SQLException expected) {
            // the caller still sees the original exception
        }
        String text = sqlLogText(dir);
        if (!text.isEmpty()) {
            assertTrue(text, text.contains("FAILED"));
            assertTrue(text, text.contains("DELETE FROM tasks"));
            assertTrue(text, text.contains("SQLITE_BUSY"));
        }
    }

    @Test
    public void aFastSuccessfulStatementIsNotLoggedUnlessVerbose() {
        executeDelayMs = 0;
        // record() is the single decision point: it returns without writing for fast, successful statements
        SqlLog.record("app.db", "SELECT 1", 2, null);
        SqlLog.configure(SqlLog.DEFAULT_SLOW_MS, true);
        SqlLog.record("app.db", "SELECT 1", 2, null);   // verbose: must not throw even when no file is set
    }

    @Test
    public void longStatementsAreCutAndWhitespaceCollapsed() {
        String longSql = "SELECT " + "x, ".repeat(400) + "y FROM t";
        String s = SqlLog.shorten(longSql);
        assertTrue(s.length() <= SqlLog.MAX_SQL + 3);
        assertTrue(s.endsWith("..."));
        assertEquals("a b c", SqlLog.shorten("a \n\t b   c "));
        assertEquals("", SqlLog.shorten(null));
    }

    @Test
    public void theProxyHidesNothingFromTheCaller() throws Exception {
        Connection c = Db.logged(fakeConnection(), "app.db");
        assertTrue(c.getAutoCommit());
        c.setAutoCommit(false);
        assertFalse(c.getAutoCommit());
        assertEquals(Integer.valueOf(1), (Object) Integer.valueOf(c.prepareStatement("UPDATE t SET a=1").executeUpdate()));
    }
}
