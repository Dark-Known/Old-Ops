package persistence.sqlite;

import model.TaskRunRecord;
import persistence.AppStorage;
import persistence.LogStores;
import persistence.RepositoryAppStorage;
import persistence.ChangeSignals;
import persistence.Changes;
import persistence.CommandQueues;
import persistence.SettingsStores;

import java.io.File;
import java.util.List;

/**
 * Composition point for the SQLite implementation of {@link AppStorage}. This is the only place
 * that names SQLite classes: the application calls {@link #open(String)} once at startup and
 * passes the result on as an {@link AppStorage}.
 *
 * <p>To move to another database, add a sibling package (e.g. {@code persistence.sqlserver}) with
 * its own two repositories and a factory like this one, and change the single call to
 * {@code SqliteStorage.open} in {@code MainWindow} and {@code Daemon}.
 */
public final class SqliteStorage {

    private SqliteStorage() {}

    /**
     * Tells the application to use SQLite for the services that are not part of {@link AppStorage}
     * (settings, command queue, change signals). Idempotent. Call it first thing in every entry point:
     * settings are read before storage is opened (the Daemon reads its log level while setting up logging).
     */
    public static void installServices() {
        Changes.install(SqliteChangeMonitor::forDataDir);
        CommandQueues.install(SqliteCommandQueue::new);
        SettingsStores.install(SqliteSettingsStore::forDataDir);
    }

    /** Opens (creating and migrating if needed) the SQLite-backed storage in {@code dataDirPath}. */
    public static AppStorage open(String dataDirPath) {
        File dataDir = new File(dataDirPath);
        dataDir.mkdirs();
        installServices();
        return new RepositoryAppStorage(
                dataDir,
                new SqliteTaskRepository(dataDir),
                new SqliteCredentialRepository(dataDir),
                new File(dataDir, "app.db").getAbsolutePath(),
                // SQLite triggers bump a per-table counter that every process can read, which is
                // what lets one process notice another's task edits.
                () -> SqliteChangeMonitor.forDataDir(dataDir).generation(ChangeSignals.Topic.TASKS),
                () -> new SqliteTransferLedger(dataDir));
    }

    /**
     * Opens the run-history and background-log stores. Older builds kept background events in run
     * history, so any left there are moved across (once) before the stores are handed out.
     */
    public static LogStores openLogs(String dataDirPath) {
        installServices();
        SqliteRunHistory runs = new SqliteRunHistory(dataDirPath);
        SqliteBackgroundLog background = new SqliteBackgroundLog(dataDirPath);
        migrateLegacyBackgroundRows(runs, background);
        return new LogStores(runs, background);
    }

    /** Names the process in rows the background log writes ("GUI" by default; the headless daemon sets "Daemon"). */
    public static void setBackgroundLogProcessLabel(String label) {
        SqliteBackgroundLog.setProcessLabel(label);
    }

    private static int migrateLegacyBackgroundRows(SqliteRunHistory history, SqliteBackgroundLog bg) {
        if (!bg.isOpen() || "1".equals(bg.meta("legacy_migrated"))) return 0;
        List<TaskRunRecord> rows = history.listLegacyBackgroundRows();
        int moved = 0;
        if (!rows.isEmpty()) {
            long maxId = rows.stream().mapToLong(TaskRunRecord::getId).max().orElse(0);
            moved = bg.importRows(rows);
            if (moved != rows.size()) return 0; // import failed: leave the rows where they are, retry next start
            history.deleteLegacyBackgroundRows(maxId);
        }
        bg.setMeta("legacy_migrated", "1");
        return moved;
    }
}
