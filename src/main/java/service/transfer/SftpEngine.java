package service.transfer;

import model.Credential;
import model.FailureClassifier;
import model.TaskRunRecord;
import service.SshSessionEvents;
import service.sftp.JschSftpClient;
import service.sftp.LocalSftpClient;
import service.sftp.RemoteEntry;
import service.sftp.SessionLog;
import service.sftp.SftpClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Runs transfers over an in-process SFTP connection (JSch) instead of launching WinSCP; the commands of a
 * {@link TransferJob} are executed by {@link SftpJobRunner}.
 */
public final class SftpEngine implements TransferEngine {

    private static final int CONNECT_TIMEOUT_MS = 30_000;

    /** The host of the built-in "Local (this computer)" credential: no connection is made, files are read and written directly. */
    static final String LOCAL_HOST = Credential.local().getHost();

    private final SessionRegistry sessions;
    private final Consumer<TaskRunRecord> failureSink;

    /**
     * @param failureSink told the specific classification of a failure, because {@link #run} can only
     *                    return a plain true/false
     */
    public SftpEngine(SessionRegistry sessions, Consumer<TaskRunRecord> failureSink) {
        this.sessions = sessions;
        this.failureSink = failureSink;
    }

    private SftpJobRunner.Connector connector() {
        return (host, port, user, pw) -> LOCAL_HOST.equals(host)
                ? new LocalSftpClient()
                : JschSftpClient.connect(host, port, user, pw, CONNECT_TIMEOUT_MS);
    }

    /** One line saying why a session was opened, from what the job does (shown as the session's purpose). */
    public static String describeJob(List<TransferCommand> commands, String taskId) {
        int get = 0, put = 0, rm = 0, mv = 0, mkdir = 0;
        for (TransferCommand c : commands) {
            if (c instanceof TransferCommand.Download) get++;
            else if (c instanceof TransferCommand.Upload) put++;
            else if (c instanceof TransferCommand.DeleteRemote) rm++;
            else if (c instanceof TransferCommand.MoveRemote) mv++;
            else if (c instanceof TransferCommand.MakeRemoteDir) mkdir++;
        }
        List<String> parts = new ArrayList<>();
        if (get > 0) parts.add("download " + get + " file(s)");
        if (put > 0) parts.add("upload " + put + " file(s)");
        if (rm > 0) parts.add("delete " + rm + " original(s)");
        if (mv > 0) parts.add("move " + mv + " original(s)");
        if (mkdir > 0) parts.add("create " + mkdir + " folder(s)");
        String what = parts.isEmpty() ? "SFTP commands" : String.join(", ", parts);
        boolean cleanup = taskId != null && taskId.equals("backup-cleanup");
        return (cleanup ? "SFTP cleanup after a successful transfer: " : "SFTP: ") + what;
    }

    @Override
    public boolean run(TransferJob job, Consumer<String> logLine, String taskId) throws Exception {
        String realId = sessions.realTaskId();
        String[] who = {job.target().getUsername(), job.target().getHost()};
        String evTask = taskId != null && !taskId.startsWith("backup-") ? taskId : null;
        final String purpose = describeJob(job.commands(), taskId);
        long openedAt = 0;
        String end = "finished";
        ScheduledFuture<?> watchdog = null;
        try {
            final AtomicBoolean timedOut = new AtomicBoolean();
            final List<SftpClient> mine = new CopyOnWriteArrayList<>();
            SftpJobRunner runner = new SftpJobRunner(connector(),
                    c -> { mine.add(c); sessions.addClient(taskId, c); if (realId != null && !realId.equals(taskId)) sessions.addClient(realId, c); },
                    c -> { mine.remove(c); sessions.removeClient(taskId, c); if (realId != null && !realId.equals(taskId)) sessions.removeClient(realId, c); });
            long maxMin = EngineSettings.maxRuntimeMinutes();
            if (maxMin > 0) {
                watchdog = EngineSettings.startWatchdog(() -> {
                    timedOut.set(true);
                    for (SftpClient c : mine) { try { c.cancel(); } catch (Exception ignored) {} }
                }, maxMin);
            }
            openedAt = System.currentTimeMillis();
            SshSessionEvents.opened(evTask, purpose, who[1], who[0]);
            boolean ok = runner.run(job, logLine);
            SessionLog.currentOrNew().add(ok ? "Script finished: all commands succeeded"
                    : "Script finished: a command failed");
            if (ok) {
                logLine.accept("[SUCCESS] Transfer completed successfully.");
                return true;
            }
            end = "failed";
            logLine.accept("[ERROR] SFTP transfer failed.");
            return false;
        } catch (IOException ex) {
            if (watchdog != null && watchdog.isDone()) {
                logLine.accept("[ERROR] Transfer stopped after the configured maximum runtime of "
                        + EngineSettings.maxRuntimeMinutes() + " minute(s) (winscpMaxRuntimeMinutes).");
                end = "timed out";
                return false;
            }
            end = "error: " + ex.getMessage();
            if (Thread.currentThread().isInterrupted() || String.valueOf(ex.getMessage()).contains("cancelled")) {
                logLine.accept("[INFO] Transfer interrupted: " + ex.getMessage());
                return false;
            }
            logLine.accept("[ERROR] SFTP transfer failed: " + ex.getMessage());
            failureSink.accept(FailureClassifier.classifyThrowable(ex));
            return false;
        } finally {
            if (watchdog != null) watchdog.cancel(false);
            if (openedAt != 0) SshSessionEvents.closed(evTask, purpose, who[1], who[0], end,
                    System.currentTimeMillis() - openedAt);
        }
    }

    @Override
    public Map<String, RemoteFileStat> listFiles(Credential target, String password, String dirPath,
                                                 Consumer<String> logLine) throws Exception {
        logLine.accept("[INFO] Listing remote directory (SFTP): " + dirPath);
        long openedAt = System.currentTimeMillis();
        final String listPurpose = "SFTP listing of " + dirPath + " (find the files to transfer)";
        SshSessionEvents.opened(null, listPurpose, target.getHost(), target.getUsername());
        String end = "finished";
        try (SftpClient c = target.isLocal() ? new LocalSftpClient() : JschSftpClient.connect(
                target.getHost(), 22, target.getUsername(), password, CONNECT_TIMEOUT_MS)) {
            Map<String, RemoteFileStat> stats = new LinkedHashMap<>();
            for (RemoteEntry e : c.list(dirPath)) {
                if (e.directory()) continue;
                stats.put(dirPath + e.name(), new RemoteFileStat(e.modifiedEpochSeconds() * 1000L, e.size()));
            }
            logLine.accept("[INFO] Received " + stats.size() + " file entries from remote listing.");
            return stats;
        } catch (IOException ex) {
            end = "error: " + ex.getMessage();
            throw ex;
        } finally {
            SshSessionEvents.closed(null, listPurpose, target.getHost(), target.getUsername(), end,
                    System.currentTimeMillis() - openedAt);
        }
    }
}
