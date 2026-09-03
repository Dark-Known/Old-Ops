package service.queue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A parsed, point-in-time snapshot of another process's scheduler state,
 * read back from the flat file written by {@link SchedulerStatusExporter}.
 *
 * This is the cross-process half of the monitoring story: the in-process
 * GUI scheduler is observed directly via {@link service.TaskSchedulerService}'s
 * live accessors, but the headless Daemon runs in a separate JVM with its
 * own in-memory {@link TaskEventQueue}/{@link TaskWorkerPool} that can't be
 * reached directly. Rather than adding sockets/RMI, each process
 * periodically drops a small snapshot of its own state to a shared file
 * (the same {@code dataDir} both processes already use for tasks.xml), and
 * any other process — here, the Event Monitor window — reads it back.
 */
public final class SchedulerStatusSnapshot {

    /**
     * How stale a status file can be before the exporting process is
     * considered offline/dead. The exporter (see {@link SchedulerStatusExporter})
     * writes every 2s (see {@code TaskSchedulerService#enableStatusExport}),
     * so anything past ~4x that interval most likely means the process died
     * mid-tick rather than just being between writes.
     *
     * Shared here so every consumer — {@code ui.MainWindow}'s scheduler
     * badge, {@code ui.EventMonitorWindow}'s worker chip, and
     * {@code ui.EventMonitorPanel}'s tabs — agrees on the same threshold
     * instead of each hard-coding its own copy.
     */
    public static final long DEFAULT_STALE_MS = 8_000L;

    /**
     * True if {@code file} holds a fresh (non-stale) snapshot right now —
     * i.e. the process exporting it is alive and actively scheduling.
     * Convenience wrapper around {@link #read} + {@link #isFresh} for
     * callers that only care about the yes/no "is it alive" question.
     */
    public static boolean isAlive(Path file, long maxAgeMillis) {
        SchedulerStatusSnapshot snap = read(file);
        return snap != null && snap.isFresh(maxAgeMillis);
    }

    /** One task's pending occurrence, as exported. */
    public record PendingEntry(String taskId, int attempt, LocalDateTime dueAt) {}

    /** One handled event, as exported. */
    public record ActivityEntry(String taskId, int attempt, LocalDateTime startedAt,
                                 LocalDateTime finishedAt, boolean errored, String errorMessage) {}

    /**
     * One watcher-enabled task's current trigger mode, as exported by
     * {@code TaskSchedulerService#getWatchStatus}. {@code mode} is the raw
     * {@code TaskSchedulerService.WatchMode} enum name (e.g.
     * {@code "NATIVE_WATCH"}, {@code "REMOTE_PUSH"}, {@code "POLLING_ONLY"}) —
     * kept as a plain string here rather than referencing that enum directly
     * so this package doesn't need a compile-time dependency on
     * {@code service.TaskSchedulerService}. {@code detail} is the matching
     * human-readable reason (e.g. "not yet attempted", "remote host has no
     * inotifywait installed"), so a reader never has to show a bare mode name
     * without being able to say why. Only tasks the exporting process
     * actually considers watcher-eligible are included; a task absent from
     * this list should be treated as "unknown to that process", not
     * necessarily "not applicable".
     */
    public record WatchEntry(String taskId, String mode, String detail) {}

    private final String processLabel;
    private final long pid;
    private final LocalDateTime writtenAt;
    private final int poolSize;
    private final int activeWorkers;
    private final List<PendingEntry> pending;
    private final List<ActivityEntry> activity;
    private final List<WatchEntry> watchEntries;

    private SchedulerStatusSnapshot(String processLabel, long pid, LocalDateTime writtenAt, int poolSize,
                                     int activeWorkers, List<PendingEntry> pending, List<ActivityEntry> activity,
                                     List<WatchEntry> watchEntries) {
        this.processLabel = processLabel;
        this.pid = pid;
        this.writtenAt = writtenAt;
        this.poolSize = poolSize;
        this.activeWorkers = activeWorkers;
        this.pending = pending;
        this.activity = activity;
        this.watchEntries = watchEntries;
    }

    public String getProcessLabel() { return processLabel; }
    public long getPid() { return pid; }
    public LocalDateTime getWrittenAt() { return writtenAt; }
    public int getPoolSize() { return poolSize; }
    public int getActiveWorkers() { return activeWorkers; }
    public List<PendingEntry> getPending() { return pending; }
    public List<ActivityEntry> getActivity() { return activity; }
    public List<WatchEntry> getWatchEntries() { return watchEntries; }

    /** Whether this snapshot is fresh enough to trust — i.e. the exporting process is still alive and running. */
    public boolean isFresh(long maxAgeMillis) {
        return java.time.Duration.between(writtenAt, LocalDateTime.now()).toMillis() <= maxAgeMillis;
    }

    /**
     * Reads and parses a status file. Returns {@code null} (not an
     * exception) if the file doesn't exist, is empty, or is mid-write/
     * corrupt — all of those simply mean "no reliable status available
     * right now", which the caller treats as "process offline or unknown".
     */
    public static SchedulerStatusSnapshot read(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty()) return null;

            String label = "unknown";
            long pid = -1;
            LocalDateTime writtenAt = null;
            int poolSize = 0, active = 0;
            List<PendingEntry> pending = new ArrayList<>();
            List<ActivityEntry> activity = new ArrayList<>();
            List<WatchEntry> watchEntries = new ArrayList<>();

            for (String line : lines) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\\|", -1);
                switch (parts[0]) {
                    case "PROC" -> {
                        if (parts.length < 6) return null;
                        label = unescape(parts[1]);
                        pid = Long.parseLong(parts[2]);
                        writtenAt = epochMillisToLocalDateTime(Long.parseLong(parts[3]));
                        poolSize = Integer.parseInt(parts[4]);
                        active = Integer.parseInt(parts[5]);
                    }
                    case "P" -> {
                        if (parts.length < 4) continue;
                        pending.add(new PendingEntry(unescape(parts[1]), Integer.parseInt(parts[2]),
                                epochMillisToLocalDateTime(Long.parseLong(parts[3]))));
                    }
                    case "A" -> {
                        if (parts.length < 7) continue;
                        activity.add(new ActivityEntry(unescape(parts[1]), Integer.parseInt(parts[2]),
                                epochMillisToLocalDateTime(Long.parseLong(parts[3])),
                                epochMillisToLocalDateTime(Long.parseLong(parts[4])),
                                "1".equals(parts[5]), unescape(parts[6])));
                    }
                    case "W" -> {
                        if (parts.length < 4) continue;
                        watchEntries.add(new WatchEntry(unescape(parts[1]), unescape(parts[2]), unescape(parts[3])));
                    }
                    default -> { /* forward-compatible: ignore unknown record types */ }
                }
            }
            if (writtenAt == null) return null; // no PROC header line — treat as unusable
            return new SchedulerStatusSnapshot(label, pid, writtenAt, poolSize, active,
                    Collections.unmodifiableList(pending), Collections.unmodifiableList(activity),
                    Collections.unmodifiableList(watchEntries));
        } catch (IOException | NumberFormatException | ArrayIndexOutOfBoundsException e) {
            // Most commonly: caught mid-write by the exporter on the other
            // process. The exporter writes atomically (temp file + move) to
            // make this rare, but a defensive caller should still just treat
            // any parse failure as "no snapshot available right now".
            return null;
        }
    }

    private static LocalDateTime epochMillisToLocalDateTime(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }

    static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("|", "\\p").replace("\n", " ").replace("\r", " ");
    }

    private static String unescape(String s) {
        if (s == null) return "";
        return s.replace("\\p", "|").replace("\\\\", "\\");
    }
}
