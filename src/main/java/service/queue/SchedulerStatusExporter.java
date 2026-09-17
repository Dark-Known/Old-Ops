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
                        List<SchedulerStatusSnapshot.WatchEntry> watchEntries,
                        List<SchedulerStatusSnapshot.FireEntry> fireEntries,
                        List<String> runningTaskIds) {
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
            for (SchedulerStatusSnapshot.FireEntry f : fireEntries) {
                sb.append("F|").append(SchedulerStatusSnapshot.escape(f.taskId())).append('|')
                        .append(toEpochMillis(f.firedAt())).append('\n');
            }
            if (runningTaskIds != null) {
                for (String taskId : runningTaskIds) {
                    sb.append("R|").append(SchedulerStatusSnapshot.escape(taskId)).append('\n');
                }
            }

            Path dir = targetFile.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            // BUG: previously created a fresh temp file every export() call
            // (every 2s — see TaskSchedulerService#exportStatus) and never
            // cleaned it up if the subsequent move failed. On Windows, that
            // move can fail with a sharing violation whenever the GUI's own
            // status-file *reader* (SchedulerStatusSnapshot#read, polled on
            // its own timer) happens to have the target file open for
            // reading at the exact instant this writer tries to atomically
            // replace it — Windows won't let you replace a file another
            // process has open without that process having requested
            // FILE_SHARE_DELETE, which a plain read doesn't. That failure
            // was swallowed by the outer catch below, leaving the orphaned
            // "scheduler-status-*.tmp" file behind forever — one more every
            // time the race lost, indefinitely, which is exactly the slow
            // pile-up. Fixed with a short retry (the reader's read is brief,
            // so the lock is normally gone within a few ms) and a
            // finally-block cleanup of the temp file if every attempt fails.
            Path tmp = Files.createTempFile(dir, "scheduler-status-", ".tmp");
            boolean moved = false;
            try {
                IOException lastFailure = null;
                for (int attempt = 0; attempt < 3 && !moved; attempt++) {
                    if (attempt > 0) {
                        try { Thread.sleep(15); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    }
                    try {
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
                        moved = true;
                    } catch (IOException e) {
                        lastFailure = e;
                    }
                }
                if (!moved && lastFailure != null) throw lastFailure;
            } finally {
                if (!moved) {
                    try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* best effort */ }
                }
            }
        } catch (IOException e) {
            log.fine("Scheduler status export skipped: " + e.getMessage());
        }
    }

    /**
     * Deletes any leftover {@code scheduler-status-*.tmp} files in the
     * status file's own directory from before this fix — every export()
     * call that ever lost the write race described above left one behind
     * with no automatic cleanup, so long-running installs can have
     * accumulated a lot of them. Safe to call once at startup; matches only
     * this exporter's own naming pattern, so it won't touch anything else
     * in the data directory.
     */
    public void cleanupOrphanedTempFiles() {
        try {
            Path dir = targetFile.toAbsolutePath().getParent();
            if (dir == null || !Files.isDirectory(dir)) return;
            try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                files.filter(p -> {
                    String name = p.getFileName().toString();
                    return name.startsWith("scheduler-status-") && name.endsWith(".tmp");
                }).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { /* best effort */ }
                });
            }
        } catch (IOException e) {
            log.fine("Orphaned status temp-file cleanup skipped: " + e.getMessage());
        }
    }

    private static long toEpochMillis(LocalDateTime t) {
        return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
