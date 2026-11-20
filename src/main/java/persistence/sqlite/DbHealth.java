package persistence.sqlite;

import service.LogFiles;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Looks after {@code app.db} (tasks, credentials, settings, the sent-files memory): a quick integrity check at start, and one
 * backup copy per day in {@code <data folder>/backups} ({@value #KEEP} newest kept). A backup is only made from a file that
 * passed the check, so a damaged file never replaces a good copy. A damaged file is left exactly as it is and the problem is
 * logged with the newest good backup to restore from.
 *
 * <p>To restore: stop the service and the GUI, delete {@code app.db}, {@code app.db-wal} and {@code app.db-shm}, copy the
 * backup over as {@code app.db}, start again.
 */
public final class DbHealth {

    static final int KEEP = 7;
    private static final Logger log = Logger.getLogger(DbHealth.class.getName());
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern BACKUP_NAME = Pattern.compile("app-\\d{8}\\.db");

    private DbHealth() {
    }

    /**
     * What a run found.
     */
    public record Report(boolean healthy, String detail, File backup) {
    }

    /**
     * Runs the check and today's backup on a background thread; never throws.
     */
    public static void runAsync(File dataDir) {
        Thread t = new Thread(() -> run(dataDir), "db-health");
        t.setDaemon(true);
        t.start();
    }

    public static Report run(File dataDir) {
        File db = new File(dataDir, "app.db");
        if (!db.isFile()) {
            SqlLog.useDataDir(dataDir);
            SqlLog.note("app.db", "health check skipped: no app.db in " + dataDir);
            return new Report(true, "no app.db yet", null);
        }
        File backups = new File(dataDir, "backups");
        try (Connection c = Db.connect(db)) {
            String result = quickCheck(c);
            if (!"ok".equals(result)) {
                String msg = "app.db FAILED its integrity check (" + result + "). It has been left untouched. "
                        + (newestBackup(backups) != null ? "Newest good backup: " + newestBackup(backups) + ". " : "There is no backup yet. ")
                        + "To restore: stop the service and the GUI, delete app.db, app.db-wal and app.db-shm, copy the backup over as app.db.";
                log.severe(msg);
                SqlLog.record("app.db", "PRAGMA quick_check", 0, new SQLException(result));
                return new Report(false, msg, newestBackup(backups));
            }
            File today = new File(backups, backupName(LocalDate.now()));
            SqlLog.note("app.db", "health check ok (quick_check) in " + dataDir);
            if (!today.exists()) {
                backups.mkdirs();
                File tmp = new File(backups, today.getName() + ".tmp");
                Files.deleteIfExists(tmp.toPath());
                try (Statement st = c.createStatement()) {
                    st.execute("VACUUM INTO '" + tmp.getAbsolutePath().replace("'", "''") + "'");
                }
                Files.move(tmp.toPath(), today.toPath());
                prune(backups, KEEP);
                log.info("app.db backed up to " + today);
                SqlLog.note("app.db", "backup written: " + today);
            }
            if (today.exists()) SqlLog.note("app.db", "today's backup: " + today);
            return new Report(true, "ok", today);
        } catch (SQLException | IOException e) {
            log.log(Level.WARNING, "app.db health check / backup did not complete: " + e.getMessage(), e);
            SqlLog.note("app.db", "health check / backup did not complete: " + e.getMessage());
            return new Report(true, "could not check: " + e.getMessage(), null);
        }
    }

    private static String quickCheck(Connection c) throws SQLException {
        StringBuilder out = new StringBuilder();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA quick_check")) {
            while (rs.next() && out.length() < 300) out.append(out.length() > 0 ? "; " : "").append(rs.getString(1));
        }
        return out.length() == 0 ? "no answer" : out.toString();
    }

    static String backupName(LocalDate day) {
        return "app-" + DAY.format(day) + ".db";
    }

    /**
     * Deletes all but the {@code keep} newest {@code app-YYYYMMDD.db} files (the date in the name decides, not the file time).
     */
    static void prune(File dir, int keep) {
        List<File> files = backupsNewestFirst(dir);
        for (int i = keep; i < files.size(); i++) {
            try {
                Files.deleteIfExists(files.get(i).toPath());
            } catch (IOException ignored) { /* stays until next time */ }
        }
    }

    static File newestBackup(File dir) {
        List<File> files = backupsNewestFirst(dir);
        return files.isEmpty() ? null : files.get(0);
    }

    private static List<File> backupsNewestFirst(File dir) {
        List<File> out = new ArrayList<>();
        if (!dir.isDirectory()) return out;
        try (Stream<Path> s = Files.list(dir.toPath())) {
            s.map(Path::toFile).filter(f -> BACKUP_NAME.matcher(f.getName()).matches()).forEach(out::add);
        } catch (IOException ignored) {
        }
        out.sort((a, b) -> b.getName().compareTo(a.getName()));
        return out;
    }
}
