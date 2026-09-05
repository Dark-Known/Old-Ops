package service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live, thread-safe accumulator of the operational detail behind a single
 * FILE_TRANSFER run — how many bytes actually moved, how many WinSCP
 * sessions (processes) it took, how many worker threads launched them, and
 * how many size-based batches the transfer was split into. See
 * {@link TransferService#executeTransfer} for where one of these is created
 * per run, {@link TransferService#runWinScpScript} for where session/thread
 * counts are recorded, and {@link TransferService#runBatchedWinScpCommands}
 * for where batch count and the file list are recorded.
 *
 * <p>Deliberately only covers the FILE_TRANSFER path — {@code executeBackup}
 * and {@code executeImapMailTask} never register one, so {@link
 * TransferService#getCompletedStats} returns {@code null} for those, and
 * callers (the Event Monitor's click-to-view popup) fall back to a generic
 * summary for anything that isn't a plain file transfer.
 *
 * <p>Mutators are package-private (only {@link TransferService} itself
 * writes to a run's stats); everything else, including the {@code ui}
 * package, only ever reads through the public getters.
 */
public final class TransferRunStats {

    private final String taskId;
    private final String sourceFolder;
    private final String destFolder;
    private final LocalDateTime startedAt;
    private volatile LocalDateTime endedAt;

    private final AtomicLong totalBytes = new AtomicLong();
    private final AtomicInteger batchCount = new AtomicInteger();
    private final AtomicInteger winScpSessionCount = new AtomicInteger();
    private final Set<Long> workerThreadIds = ConcurrentHashMap.newKeySet();

    // Bounded so a huge folder-of-thousands transfer doesn't balloon memory —
    // this is for a "here's roughly what moved" glance, not a full manifest.
    private static final int MAX_TRACKED_FILE_NAMES = 25;
    private final List<String> fileNames = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger fileCount = new AtomicInteger();

    TransferRunStats(String taskId, String sourceFolder, String destFolder, LocalDateTime startedAt) {
        this.taskId = taskId;
        this.sourceFolder = sourceFolder;
        this.destFolder = destFolder;
        this.startedAt = startedAt;
    }

    void recordWinScpSession(long threadId) {
        winScpSessionCount.incrementAndGet();
        workerThreadIds.add(threadId);
    }

    void setBatchCount(int n) { batchCount.set(n); }

    void addFile(String name, long size) {
        fileCount.incrementAndGet();
        if (size > 0) totalBytes.addAndGet(size);
        if (name != null) {
            synchronized (fileNames) {
                if (fileNames.size() < MAX_TRACKED_FILE_NAMES) fileNames.add(name);
            }
        }
    }

    void markEnded() { endedAt = LocalDateTime.now(); }

    public String getTaskId()             { return taskId; }
    public String getSourceFolder()       { return sourceFolder; }
    public String getDestFolder()         { return destFolder; }
    public LocalDateTime getStartedAt()   { return startedAt; }
    public LocalDateTime getEndedAt()     { return endedAt; }
    public long getTotalBytes()           { return totalBytes.get(); }
    public int getBatchCount()            { return Math.max(batchCount.get(), 1); }
    public int getWinScpSessionCount()    { return winScpSessionCount.get(); }
    public int getWorkerThreadsConsumed() { return Math.max(workerThreadIds.size(), 1); }
    public int getFileCount()             { return fileCount.get(); }

    /** Up to {@link #MAX_TRACKED_FILE_NAMES} names actually transferred, plus
     *  a "and N more" marker via {@link #getFileCount()} when truncated. */
    public List<String> getFileNames() {
        synchronized (fileNames) { return List.copyOf(fileNames); }
    }

    public Duration getDuration() {
        if (startedAt == null || endedAt == null) return Duration.ZERO;
        return Duration.between(startedAt, endedAt);
    }
}
