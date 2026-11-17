package service.transfer;

import model.Credential;
import service.SshSessionEvents;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Runs transfers by launching WinSCP.com, one process (one SSH session) per script. */
public final class WinScpEngine implements TransferEngine {

    private final Supplier<String> winScpPath;
    private final ScriptFiles scripts;
    private final SessionRegistry sessions;

    /** @param winScpPath where WinSCP.com is; asked on every call because the user can change it at run time */
    public WinScpEngine(Supplier<String> winScpPath, ScriptFiles scripts, SessionRegistry sessions) {
        this.winScpPath = winScpPath;
        this.scripts = scripts;
        this.sessions = sessions;
    }

    @Override
    public boolean run(TransferJob job, Consumer<String> logLine, String taskId) throws Exception {
        File scriptFile = WinScpScript.write(scripts, job);
        try {
            return runScript(scriptFile, new String[]{job.target().getUsername(), job.target().getHost()}, logLine, taskId);
        } finally {
            scriptFile.delete();
        }
    }

    private boolean runScript(File scriptFile, String[] who, Consumer<String> logLine, String taskId) throws Exception {
        // Unique per call (derived from the already-unique scriptFile name) rather than a fixed shared
        // path: batches can run concurrently, and two WinSCP processes writing the same log file at once
        // would collide/lock on Windows. Best-effort cleanup afterward.
        File logFile = new File(scriptFile.getAbsolutePath() + ".log");
        ProcessBuilder pb = new ProcessBuilder(
                winScpPath.get(),
                "/script=" + scriptFile.getAbsolutePath(),
                "/log=" + logFile.getAbsolutePath(),
                "/loglevel=1");
        scripts.applyPassword(pb, scriptFile);
        pb.redirectErrorStream(true);
        Process proc = null;
        // Each WinSCP.com process is its own SSH session: report it to the Event Monitor (pseudo task ids
        // such as "backup-mkdir" are not tasks, so those resolve through the calling thread's run).
        String evTask = taskId != null && !taskId.startsWith("backup-") ? taskId : null;
        long sshOpenedAt = 0;
        String sshEnd = "finished";
        String realTaskIdRegistered = null;
        ScheduledFuture<?> watchdog = null;
        final AtomicBoolean timedOut = new AtomicBoolean();
        try {
            proc = pb.start();
            sshOpenedAt = System.currentTimeMillis();
            SshSessionEvents.opened(evTask, "WinSCP transfer", who[1], who[0]);
            sessions.addProcess(taskId, proc);
            // Backup helper sessions use pseudo ids ("backup-mkdir"); also register them under the
            // real task so Cancel can stop them.
            realTaskIdRegistered = sessions.realTaskId();
            if (realTaskIdRegistered != null && !realTaskIdRegistered.equals(taskId)) {
                sessions.addProcess(realTaskIdRegistered, proc);
            }
            final Process watched = proc;
            long maxMin = EngineSettings.maxRuntimeMinutes();
            if (maxMin > 0) {
                watchdog = EngineSettings.startWatchdog(() -> {
                    timedOut.set(true);
                    try { watched.destroyForcibly(); } catch (Exception ignored) {}
                }, maxMin);
            }

            try (BufferedReader br =
                         new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    logLine.accept(ScriptFiles.maskPasswords(line));
                    if (Thread.currentThread().isInterrupted()) {
                        try { proc.destroyForcibly(); } catch (Exception ignored) {}
                        throw new InterruptedException("Transfer cancelled");
                    }
                }
            }
            int exitCode = proc.waitFor();
            if (timedOut.get()) {
                sshEnd = "timed out";
                logLine.accept("[ERROR] WinSCP was stopped after the configured maximum runtime of "
                        + EngineSettings.maxRuntimeMinutes() + " minute(s) (winscpMaxRuntimeMinutes).");
                return false;
            }
            if (exitCode == 0) {
                logLine.accept("[SUCCESS] Transfer completed successfully.");
                return true;
            } else {
                sshEnd = "WinSCP exit code " + exitCode;
                logLine.accept("[ERROR] WinSCP exited with code: " + exitCode);
                return false;
            }
        } catch (InterruptedException ie) {
            sshEnd = "cancelled";
            logLine.accept("[INFO] Transfer interrupted: " + ie.getMessage());
            if (proc != null) try { proc.destroyForcibly(); } catch (Exception ignored) {}
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ex) {
            sshEnd = "error: " + ex.getMessage();
            if (sshOpenedAt == 0) SshSessionEvents.failed(evTask, "WinSCP transfer", who[1], who[0], ex.getMessage());
            throw ex;
        } finally {
            if (sshOpenedAt != 0) SshSessionEvents.closed(evTask, "WinSCP transfer", who[1], who[0], sshEnd,
                    System.currentTimeMillis() - sshOpenedAt);
            sessions.removeProcess(taskId, proc);
            if (watchdog != null) watchdog.cancel(false);
            if (realTaskIdRegistered != null && !realTaskIdRegistered.equals(taskId)) {
                sessions.removeProcess(realTaskIdRegistered, proc);
            }
            try { logFile.delete(); } catch (Exception ignored) {}
        }
    }

    @Override
    public Map<String, RemoteFileStat> listFiles(Credential target, String password, String dirPath,
                                                 Consumer<String> logLine) throws Exception {
        logLine.accept("[INFO] Listing remote directory (WinSCP ls): " + dirPath);

        File listScript = File.createTempFile("opstool_ls_", ".txt");
        ScriptFiles.secureTemp(listScript);

        try (PrintWriter pw = new PrintWriter(new FileWriter(listScript))) {
            pw.println("option batch abort");
            pw.println("option confirm off");
            scripts.writeOpenLine(pw, target, password, listScript);
            pw.println("ls \"" + dirPath + "\"");
            pw.println("close");
            pw.println("exit");
        }

        List<String> rawLines = new ArrayList<>();
        File listLogFile = new File(listScript.getAbsolutePath() + ".log");
        long lsOpenedAt = 0;
        int lsExit = 0;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    winScpPath.get(), "/script=" + listScript.getAbsolutePath(),
                    "/log=" + listLogFile.getAbsolutePath(), "/loglevel=1");
            scripts.applyPassword(pb, listScript);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            lsOpenedAt = System.currentTimeMillis();
            SshSessionEvents.opened(null, "WinSCP listing", target.getHost(), target.getUsername());
            try (BufferedReader br =
                         new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    // NOTE: intentionally not logging every raw `ls` line here. For directories with many
                    // thousands of files that used to push one log line (and one UI update) per file, which
                    // drove a CPU/memory spike during large listings. Every line is still collected for
                    // parsing below; only a single summary count is logged.
                    rawLines.add(line);
                }
            }
            lsExit = proc.waitFor();
        } finally {
            if (lsOpenedAt != 0) SshSessionEvents.closed(null, "WinSCP listing", target.getHost(), target.getUsername(),
                    lsExit == 0 ? "finished" : "WinSCP exit code " + lsExit, System.currentTimeMillis() - lsOpenedAt);
            listScript.delete();
            try { listLogFile.delete(); } catch (Exception ignored) {}
        }
        logLine.accept("[INFO] Received " + rawLines.size() + " lines from remote listing.");
        return WinScpListing.parse(rawLines, dirPath, LocalDateTime.now());
    }
}
