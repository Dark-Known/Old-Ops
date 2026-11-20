package persistence.sqlite;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/** The SQL (run against a real SQLite by hand; no driver is available to the unit tests here). */
public class SqliteRemoteProcessStoreTest {

    @Test
    public void sqlStatementsUseTheSameTableTheyCreate() {
        for (String sql : new String[]{SqliteRemoteProcessStore.CREATE_SQL, SqliteRemoteProcessStore.INSERT_SQL,
                SqliteRemoteProcessStore.SELECT_SQL, SqliteRemoteProcessStore.DELETE_SQL, SqliteRemoteProcessStore.PURGE_SQL}) {
            assertTrue(sql, sql.contains("remote_processes"));
        }
    }
}
