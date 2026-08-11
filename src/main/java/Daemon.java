

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
    private static PrintWriter daemonLog;

    public static void main(String[] args) throws Exception {

        // ── Resolve data directory ───────────────────────────────────────────
        String dataDir = args.length > 0
                ? args[0]
                : loadDataDirFromConfig();

        new File(dataDir).mkdirs();

        // ── Set up file logging ──────────────────────────────────────────────
        String logPath = dataDir + File.separator + "daemon.log";
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
        
        // If poll interval not provided as argument, try to read from preferences
        if (args.length <= 1) {
            try {
                java.util.prefs.Preferences prefs = java.util.prefs.Preferences
                    .userRoot().node("com/opstool/ui/SettingsPanel");
                int saved = prefs.getInt("poll_interval_seconds", 60);
                if (saved >= 1 && saved <= 3600) {
                    pollInterval = saved;
                    log("Poll interval loaded from preferences: " + saved + " seconds");
                }
            } catch (Exception e) {
                log("Could not read poll interval preference, using default 60 seconds");
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
        scheduler.start();
        log("Scheduler started — polling every " + pollInterval + " seconds");

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

    // ── Data Directory  helpers ──────────────────────────────────────────────────────

    private static String loadDataDirFromConfig() {
        String defaultDir = "C:\\OpsTools\\Data";
        String val = util.AppConfig.readValue("dataDir");
        return val != null ? val : defaultDir;
    }

    // ── Logging helpers ──────────────────────────────────────────────────────

    private static void setupLogging(String logPath, String dataDir) {
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
                dataDir + File.separator + "daemon-%g.log", 5 * 1024 * 1024, 3, true);
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

    private static void log(String msg) {
        String line = LocalDateTime.now().format(DT) + "  " + msg;
        System.out.println(line);
        if (daemonLog != null) {
            daemonLog.println(line);
        }
    }

    // ── WinSCP preference loader ─────────────────────────────────────────────
    // Reads the path saved by the GUI's SettingsPanel so the daemon uses the
    // same WinSCP binary without any extra configuration.
    private static void loadWinScpPref(XmlStorageService storage,
                                        TransferService transferSvc,
                                        String dataDir) {
        // Java Preferences are user-scoped by class — read the key the
        // SettingsPanel stores under com.opstool.ui.SettingsPanel
        try {
            java.util.prefs.Preferences prefs = java.util.prefs.Preferences
                .userRoot().node("com/opstool/ui/SettingsPanel");
            String saved = prefs.get("winscp_path", null);
            if (saved != null && !saved.isEmpty()) {
                transferSvc.setWinScpPath(saved);
                log("WinSCP path loaded from preferences: " + saved);
            } else {
                log("WinSCP path: using auto-detected default (" + transferSvc.getWinScpPath() + ")");
            }
        } catch (Exception e) {
            log("Could not read WinSCP preference: " + e.getMessage() + " — using default");
        }
    }
}
