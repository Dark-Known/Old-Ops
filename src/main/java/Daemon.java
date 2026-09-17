

import service.TaskSchedulerService;
import service.TransferService;
import service.XmlStorageService;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.*;

/**
 * Headless scheduler daemon — no GUI, no Swing.
 *
 * Registered as a Windows Scheduled Task by setup.ps1 so it fires
 * automatically at system startup (and every hour as a safety net),
 * independently of whether the main GUI application is open.
 *
 * Usage:  java -cp OpsTransferTool.jar com.opstool.Daemon [dataDir]
 *
 * If dataDir is omitted it defaults to %USERPROFILE%\.opstool  (same
 * directory the GUI uses), so both processes share the same tasks.xml
 * and creds_<username>.xml files without any extra configuration.
 *
 * The daemon writes its own rotating log to <dataDir>/daemon.log
 * (max 5 MB, 3 files) so you can inspect it independently of the GUI.
 *
 * Lifecycle:
 *   1. On startup it immediately checks all due tasks and runs them.
 *   2. It then polls every 60 seconds, exactly as the in-GUI scheduler does.
 *   3. It runs until the Windows Scheduled Task kills it (next trigger fires
 *      a new instance; the old one is stopped first via the /F flag in setup).
 *   4. It catches SIGTERM / shutdown hooks and flushes logs cleanly.
 */
public class Daemon {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");
    private static PrintWriter daemonLog;
    private static String daemonLogPath;
    private static long maxLogBytes;
    private static int keepArchiveCount;

    public static void main(String[] args) throws Exception {

        // ── Resolve data directory ───────────────────────────────────────────
        String dataDir = args.length > 0
                ? args[0]
                : loadDataDirFromConfig();

        new File(dataDir).mkdirs();

        // ── Set up file logging ──────────────────────────────────────────────
        String logPath = dataDir + File.separator + daemonLogFileName();
        setupLogging(logPath, dataDir);

        log("Ops Transfer Tool Daemon starting");
        log("Data directory : " + dataDir);
        log("Log file       : " + logPath);
        log("JVM            : " + System.getProperty("java.version"));

        // ── Wire services (same classes the GUI uses) ────────────────────────
        XmlStorageService storage      = new XmlStorageService(dataDir);
        TransferService   transferSvc  = new TransferService(storage);
        loadWinScpPref(storage, transferSvc, dataDir);

        int pollInterval = 60;
        if (args.length > 1) {
            try { pollInterval = Integer.parseInt(args[1]); } catch (Exception ignored) {}
        }
        
        // If poll interval not provided as argument, read it from app.db via
        // AppSettings — the same shared store SettingsPanel now saves to.
        // This replaces a previous java.util.prefs.Preferences read that
        // pointed at a node ("com/opstool/ui/SettingsPanel") SettingsPanel
        // never actually wrote to (it writes under Preferences
        // .userNodeForPackage(SettingsPanel.class), i.e. node "/ui") — so
        // this value was silently never picked up from the GUI even before
        // any service migration. AppSettings is a SQLite file in the shared
        // data directory, not a per-user registry hive, so it also resolves
        // correctly once the daemon runs as a service account.
        if (args.length <= 1) {
            int saved = util.AppSettings.getPollIntervalSeconds();
            if (saved >= 1 && saved <= 3600) {
                pollInterval = saved;
                log("Poll interval loaded from app.db: " + saved + " seconds");
            }
        }
        
        TaskSchedulerService scheduler = new TaskSchedulerService(storage, transferSvc, pollInterval);

        // Mirror every scheduler log line to our daemon.log
        scheduler.setLogCallback((taskId, line) -> log("[task:" + taskId + "] " + line));

        // ── Shutdown hook — flush logs on SIGTERM ────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log("Daemon shutting down — flushing logs");
            scheduler.stop();
            if (daemonLog != null) daemonLog.flush();
        }));

        // ── Start background poll loop ───────────────────────────────────────
        scheduler.enableStatusExport(dataDir, "daemon");
        scheduler.start();
        log("Scheduler started — polling every " + pollInterval + " seconds");

        // ── Start the GUI command poller ─────────────────────────────────────
        // Separate from the scheduling tick above (and deliberately much
        // faster — 3s vs. the scheduler's own 60s-ish poll interval) so a
        // "Run now" click from a thin GUI that owns no scheduler of its own
        // feels responsive, without coupling the two poll loops together.
        Thread commandPoller = new Thread(() -> runCommandPollerLoop(dataDir, scheduler), "gui-command-poller");
        commandPoller.setDaemon(true);
        commandPoller.start();
        log("Command poller started — checking for GUI-requested actions every 3 seconds");

        // ── Keep alive ───────────────────────────────────────────────────────
        // The daemon stays alive until the OS kills it.
        // We sleep in a tight loop so the JVM does not exit.
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(60_000);
                log("Daemon heartbeat — still running");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ── GUI command poller ───────────────────────────────────────────────────
    // Executes actions a thin GUI queued via service.CommandQueueService
    // (Run now / Cancel / Restart / Reconnect watch / Refresh) on THIS
    // process's scheduler — the only one actually holding the worker pool —
    // instead of the GUI ever running these itself.
    private static void runCommandPollerLoop(String dataDir, TaskSchedulerService scheduler) {
        File dbDir = new File(dataDir);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                for (service.CommandQueueService.Command cmd : service.CommandQueueService.pollPending(dbDir)) {
                    handleCommand(dbDir, scheduler, cmd);
                }
                service.CommandQueueService.pruneOldResolved(dbDir);
            } catch (Exception e) {
                log("Command poller error: " + e.getMessage());
            }
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void handleCommand(File dbDir, TaskSchedulerService scheduler,
                                       service.CommandQueueService.Command cmd) {
        try {
            switch (cmd.action()) {
                case RUN_NOW -> scheduler.runNow(cmd.taskId());
                case CANCEL -> scheduler.cancelTask(cmd.taskId());
                case RESTART -> scheduler.restartTask(cmd.taskId());
                case RECONNECT_WATCH -> scheduler.reconnectWatch(cmd.taskId());
                case REFRESH -> scheduler.refresh();
            }
            log("Executed GUI command " + cmd.action() + " for task " + cmd.taskId()
                    + " (requested by " + cmd.requestedBy() + ")");
            service.CommandQueueService.markDone(dbDir, cmd.id());
        } catch (Exception e) {
            log("GUI command " + cmd.action() + " for task " + cmd.taskId() + " failed: " + e.getMessage());
            service.CommandQueueService.markFailed(dbDir, cmd.id(), e.getMessage());
        }
    }

    // ── Data Directory  helpers ──────────────────────────────────────────────────────

    private static String loadDataDirFromConfig() {
        String defaultDir = "C:\\OpsTools\\Data";
        String val = util.AppConfig.readValue("dataDir");
        return val != null ? val : defaultDir;
    }

    /**
     * Daemon log file name, sourced from app-config.xml's
     * &lt;logging&gt;&lt;daemonLogFile&gt; tag so the running daemon and the
     * Settings panel's "View Daemon Log" viewer always agree on where the
     * real log lives. Falls back to "daemon.log" if unset/unreadable.
     */
    public static String daemonLogFileName() {
        String name = util.AppConfig.readValue("daemonLogFile");
        return (name != null && !name.trim().isEmpty()) ? name.trim() : "daemon.log";
    }

    // ── Logging helpers ──────────────────────────────────────────────────────

    private static void setupLogging(String logPath, String dataDir) {
        daemonLogPath = logPath;
        maxLogBytes = readConfiguredMaxBytes();
        keepArchiveCount = readConfiguredKeepCount();
        try {
            daemonLog = new PrintWriter(new FileWriter(logPath, true), true);
        } catch (Exception e) {
            System.err.println("Could not open daemon log: " + e.getMessage());
        }

        // Also configure java.util.logging to go to the same file
        try {
            Logger rootLogger = Logger.getLogger("");
            // Remove default console handler
            for (Handler h : rootLogger.getHandlers()) rootLogger.removeHandler(h);

            FileHandler fh = new FileHandler(
                dataDir + File.separator + "daemon-%g.log", maxLogBytes, keepArchiveCount, true);
            fh.setFormatter(new SimpleFormatter() {
                @Override public String format(LogRecord r) {
                    return LocalDateTime.now().format(DT) + "  " + r.getMessage() + "\n";
                }
            });
            rootLogger.addHandler(fh);
            rootLogger.setLevel(parseLevel(util.AppSettings.getLogLevel()));
        } catch (Exception e) {
            System.err.println("Could not configure file logger: " + e.getMessage());
        }
    }

    /**
     * Rotation size/keep-count for both this class's own {@code daemonLog}
     * writer and the {@code java.util.logging} {@link FileHandler} above —
     * read from app-config.xml's {@code <logging><logRotateMaxBytes>} /
     * {@code <logRotateKeepFiles>}, same values {@link service.TaskLogService}
     * reads for per-task logs. Previously these two log outputs each had
     * their own hardcoded 5&nbsp;MB figure baked in — and the one actually
     * used for the daemon's operational log (the plain {@code daemonLog}
     * {@link PrintWriter} that every {@link #log(String)} call writes
     * through) had no rotation logic at all despite this class's own javadoc
     * claiming "rotating log... max 5 MB, 3 files"; that description only
     * ever matched the separate, mostly-empty {@code daemon-%g.log} written
     * by the {@code java.util.logging} root handler below, which only
     * captures the small number of calls made directly through
     * {@code java.util.logging.Logger} elsewhere in the app (scheduler/DB
     * warnings) — not the day-to-day "task started/finished" lines mirrored
     * via {@link TaskSchedulerService#setLogCallback}, which only ever went
     * to the non-rotating file.
     */
    private static long readConfiguredMaxBytes() {
        String raw = util.AppConfig.readValue("logRotateMaxBytes");
        if (raw != null) {
            try {
                long v = Long.parseLong(raw.trim());
                if (v > 0) return v;
            } catch (NumberFormatException ignored) { /* fall through to default */ }
        }
        return 10 * 1024; // 10 KB default, matching app-config.xml's shipped value
    }

    private static int readConfiguredKeepCount() {
        String raw = util.AppConfig.readValue("logRotateKeepFiles");
        if (raw != null) {
            try {
                int v = Integer.parseInt(raw.trim());
                if (v > 0) return v;
            } catch (NumberFormatException ignored) { /* fall through to default */ }
        }
        return 5;
    }

    /**
     * Maps the app's DEBUG/INFO/WARN/ERROR log level (as configured in the
     * Settings panel / app-settings.json — see {@link util.AppSettings}) to
     * a {@link Level}. Each daemon run re-reads this at startup (via
     * {@link #setupLogging}), so a level change made in the Settings panel
     * takes effect from the daemon's next scheduled run onward — this
     * process itself doesn't have a live-updating logger, but nothing here
     * requires a full reinstall/relaunch either.
     */
    private static Level parseLevel(String configured) {
        if (configured == null) return Level.INFO;
        switch (configured.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "DEBUG": return Level.FINE;
            case "WARN":  return Level.WARNING;
            case "ERROR": return Level.SEVERE;
            case "INFO":
            default:      return Level.INFO;
        }
    }

    private static synchronized void log(String msg) {
        String line = LocalDateTime.now().format(DT) + "  " + msg;
        System.out.println(line);
        rotateIfNeeded();
        if (daemonLog != null) {
            daemonLog.println(line);
        }
    }

    /**
     * Checked before every line. Closes and reopens {@link #daemonLog}
     * around the rename — same reasoning as
     * {@link service.TaskLogService#log}: renaming a file that's still open
     * for writing silently fails on Windows, which is exactly why this
     * class's log never actually rotated before despite the class javadoc
     * claiming it did.
     */
    private static void rotateIfNeeded() {
        if (daemonLogPath == null) return;
        try {
            File logFile = new File(daemonLogPath);
            if (!logFile.exists() || logFile.length() <= maxLogBytes) return;

            if (daemonLog != null) {
                daemonLog.close();
                daemonLog = null;
            }

            File dir = logFile.getParentFile();
            String base = logFile.getName();
            int dot = base.lastIndexOf('.');
            String stem = dot > 0 ? base.substring(0, dot) : base;
            String ext = dot > 0 ? base.substring(dot) : "";
            File rotated = new File(dir, stem + "-" + LocalDateTime.now().format(FILE_DT) + ext);

            if (logFile.renameTo(rotated)) {
                cleanupOldDaemonLogs(dir, stem, ext);
            } else {
                System.err.println("Failed to rotate daemon log (rename returned false) — "
                        + "continuing to append to the current file.");
            }

            daemonLog = new PrintWriter(new FileWriter(daemonLogPath, true), true);
        } catch (Exception e) {
            System.err.println("Daemon log rotation failed: " + e.getMessage());
            try {
                if (daemonLog == null) daemonLog = new PrintWriter(new FileWriter(daemonLogPath, true), true);
            } catch (Exception reopenFailure) {
                System.err.println("Could not reopen daemon log after failed rotation: " + reopenFailure.getMessage());
            }
        }
    }

    private static void cleanupOldDaemonLogs(File dir, String stem, String ext) {
        File[] archives = dir.listFiles((d, name) ->
                name.startsWith(stem + "-") && name.endsWith(ext) && !name.equals(stem + ext));
        if (archives == null || archives.length <= keepArchiveCount) return;
        java.util.Arrays.sort(archives, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = keepArchiveCount; i < archives.length; i++) {
            archives[i].delete();
        }
    }

    // ── WinSCP config loader ─────────────────────────────────────────────
    // Reads the path saved by the GUI's SettingsPanel from app.db via
    // AppSettings — shared with the GUI, so it resolves correctly whether
    // the daemon runs as the interactive user or, once wrapped as a
    // Windows Service, as a service account with its own separate registry
    // hive that per-user Preferences would never have been visible from.
    private static void loadWinScpPref(XmlStorageService storage,
                                        TransferService transferSvc,
                                        String dataDir) {
        try {
            String saved = util.AppSettings.getWinScpPath();
            if (saved != null && !saved.trim().isEmpty()) {
                transferSvc.setWinScpPath(saved.trim());
                log("WinSCP path loaded from app.db: " + saved.trim());
            } else {
                log("WinSCP path: using auto-detected default (" + transferSvc.getWinScpPath() + ")");
            }
        } catch (Exception e) {
            log("Could not read WinSCP setting: " + e.getMessage() + " — using default");
        }
    }
}
