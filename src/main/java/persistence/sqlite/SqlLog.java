package persistence.sqlite;

import service.LogFiles;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Where database statements that failed or ran slowly are written: {@code <data folder>/logs/sql.log}, one line each,
 * and also to the normal Java log. Only the SQL text is logged (statements are parameterised, so no values), cut to
 * {@value #MAX_SQL} characters.
 *
 * <p>By default a statement is logged only when it fails or takes at least {@value #DEFAULT_SLOW_MS} ms. Start the JVM with
 * {@code -Dops.sql.slowMs=100} to lower that, or {@code -Dops.sql.verbose=true} to log every statement. The file is
 * renamed to {@code sql.log.1} when it passes {@value #MAX_BYTES} bytes (one older file is kept). Each line has the
 * process id, so the GUI and the Daemon can be told apart.
 */
public final class SqlLog {

    static final long DEFAULT_SLOW_MS = 500;
    static final int MAX_SQL = 300;
    static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Logger log = Logger.getLogger("service.sql");
    private static final Object LOCK = new Object();
    private static final long PID = ProcessHandle.current().pid();

    private static volatile Path file;                 // null until a database in a data folder is opened
    private static volatile long slowMs = Long.getLong("ops.sql.slowMs", DEFAULT_SLOW_MS);
    private static volatile boolean verbose = Boolean.getBoolean("ops.sql.verbose");

    private SqlLog() {
    }

    /**
     * Sets the data folder whose logs folder receives sql.log (the first one wins; the GUI and Daemon each have one).
     */
    static void useDataDir(File dataDir) {
        if (file == null && dataDir != null) file = LogFiles.file(dataDir, "sql.log").toPath();
    }

    static void configure(long slowMillis, boolean logEverything) {
        slowMs = slowMillis;
        verbose = logEverything;
    }

    /**
     * Records one statement. Does nothing for a fast, successful one unless verbose logging is on. Never throws.
     */
    static void record(String db, String sql, long millis, Throwable failure) {
        String kind = failure != null ? "FAILED" : millis >= slowMs ? "SLOW" : verbose ? "ok" : null;
        if (kind == null) return;
        String text = shorten(sql);
        String line = LocalDateTime.now().withNano(0) + " pid=" + PID + " " + db + " " + kind + " " + millis + " ms  " + text
                + (failure != null ? "  -> " + failure.getClass().getSimpleName() + ": " + failure.getMessage() : "");
        if (!kind.equals("ok")) log.log(Level.WARNING, line);
        write(line);
    }

    /**
     * Writes an informational line (always), e.g. the result of the start-up health check, so the file exists and shows
     * the database layer is working even when nothing is slow or failing.
     */
    static void note(String db, String message) {
        write(LocalDateTime.now().withNano(0) + " pid=" + PID + " " + db + " INFO  " + shorten(message));
    }

    /**
     * Records a rolled-back transaction (always logged).
     */
    static void rolledBack(String db, Throwable cause) {
        record(db, "ROLLBACK", 0, cause == null ? new IllegalStateException("rolled back") : cause);
    }

    static String shorten(String sql) {
        if (sql == null) return "";
        String s = sql.replaceAll("\\s+", " ").trim();
        return s.length() <= MAX_SQL ? s : s.substring(0, MAX_SQL) + "...";
    }

    private static void write(String line) {
        Path f = file;
        if (f == null) return;
        synchronized (LOCK) {
            try {
                if (Files.isRegularFile(f) && Files.size(f) > MAX_BYTES) {
                    Files.move(f, f.resolveSibling(f.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
                }
                Files.writeString(f, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) { /* logging must never break a database call */ }
        }
    }
}
