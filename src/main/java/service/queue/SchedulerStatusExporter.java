package service.queue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.logging.Logger;

/**
 * Writes a small, human-readable snapshot of this process's scheduler state
 * (worker pool occupancy, pending events, recent activity) to a shared file
 * so another process — typically the GUI's Event Monitor window reading the
 * headless Daemon's file — can observe it without any IPC beyond the
 * filesystem both processes already share.
 *
 * Writes are atomic (temp file + {@link StandardCopyOption#ATOMIC_MOVE})
 * so a concurrent reader (see {@link SchedulerStatusSnapshot#read}) never
 * sees a half-written file.
 */
public final class SchedulerStatusExporter {

    private static final Logger log = Logger.getLogger(SchedulerStatusExporter.class.getName());

    private final Path targetFile;
    private final String processLabel;
    private final long pid;

    public SchedulerStatusExporter(Path targetFile, String processLabel) {
        this.targetFile = targetFile;
        this.processLabel = processLabel;
        long resolvedPid;
        try {
            resolvedPid = ProcessHandle.current().pid();
        } catch (Exception e) {
            resolvedPid = -1;
        }
        this.pid = resolvedPid;
    }

    /** Writes the current snapshot. Safe to call from any thread; never throws. */
    public void export(int poolSize, int activeWorkers, List<TaskDueEvent> pending,
                        List<TaskWorkerPool.ActivityEntry> activity,
                        List<SchedulerStatusSnapshot.WatchEntry> watchEntries) {
        try {
            StringBuilder sb = new StringBuilder(512);
            sb.append("PROC|").append(SchedulerStatusSnapshot.escape(processLabel)).append('|')
                    .append(pid).append('|')
                    .append(toEpochMillis(LocalDateTime.now())).append('|')
                    .append(poolSize).append('|')
                    .append(activeWorkers).append('\n');

            for (TaskDueEvent e : pending) {
                sb.append("P|").append(SchedulerStatusSnapshot.escape(e.getTaskId())).append('|')
                        .append(e.getAttempt()).append('|')
                        .append(toEpochMillis(e.getDueAt())).append('\n');
            }
            for (TaskWorkerPool.ActivityEntry a : activity) {
                sb.append("A|").append(SchedulerStatusSnapshot.escape(a.getTaskId())).append('|')
                        .append(a.getAttempt()).append('|')
                        .append(toEpochMillis(a.getStartedAt())).append('|')
                        .append(toEpochMillis(a.getFinishedAt())).append('|')
                        .append(a.isErrored() ? '1' : '0').append('|')
                        .append(SchedulerStatusSnapshot.escape(a.getErrorMessage())).append('\n');
            }
            for (SchedulerStatusSnapshot.WatchEntry w : watchEntries) {
                sb.append("W|").append(SchedulerStatusSnapshot.escape(w.taskId())).append('|')
                        .append(SchedulerStatusSnapshot.escape(w.mode())).append('|')
                        .append(SchedulerStatusSnapshot.escape(w.detail())).append('\n');
            }

            Path dir = targetFile.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "scheduler-status-", ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException amnse) {
                // Some filesystems (notably certain network shares) don't support
                // atomic same-directory moves; falling back to a plain replace is
                // still far better than not exporting status at all, and the
                // reader's escape/parse guards handle the rare torn read.
                Files.move(tmp, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.fine("Scheduler status export skipped: " + e.getMessage());
        }
    }

    private static long toEpochMillis(LocalDateTime t) {
        return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
