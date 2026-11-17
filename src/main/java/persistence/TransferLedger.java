package persistence;

import model.FileSelection.SentPolicy;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * The memory of which files a task has already sent (what "Skip files already sent" consults and the
 * "Forget sent files" button clears) plus the "no new files" tracking per task.
 *
 * <p>An entry is the file's name, size and last-changed time <i>as seen on the source side</i> when it was
 * sent. Entries older than {@value #RETENTION_DAYS} days are dropped. Failures are {@link IOException}s:
 * callers decide whether that stops a run (reading) or only earns a warning (writing after a transfer
 * that already happened). Implementations must be safe to use from the GUI and the Daemon at once.
 */
public interface TransferLedger {

    int RETENTION_DAYS = 180;

    /** One sent file as seen on the source side. */
    record Entry(String name, long size, long mtimeMillis) {}

    /** When files last arrived for a task, when we started watching it, and when we last alerted (0 = never). */
    record State(long lastFilesAt, long watchingSince, long lastAlertAt) {}

    /** Everything remembered for one task, loaded once per run so checking a file costs nothing. */
    final class Snapshot {
        private final Set<String> names = new HashSet<>();
        private final Set<String> exact = new HashSet<>();

        private static String key(String name, long size, long mtime) { return name + '\u0000' + size + '\u0000' + mtime; }

        /** Used by implementations while loading. */
        public void add(String name, long size, long mtimeMillis) {
            names.add(name);
            exact.add(key(name, size, mtimeMillis));
        }

        /** Whether this file counts as already sent under {@code policy}. */
        public boolean wasSent(String name, long size, long mtimeMillis, SentPolicy policy) {
            if (policy == SentPolicy.ANY_NAME) return names.contains(name);
            if (policy == SentPolicy.UNCHANGED) return exact.contains(key(name, size, mtimeMillis));
            return false;
        }

        /** Number of remembered files (distinct name/size/time combinations). */
        public int size() { return exact.size(); }
    }

    /** What is remembered for {@code taskId} right now. */
    Snapshot snapshot(String taskId) throws IOException;

    /** Remembers these files as sent now. Safe to call again for the same file (it just refreshes the time). */
    void record(String taskId, Collection<Entry> entries) throws IOException;

    /** How many files are remembered for the task. */
    int count(String taskId) throws IOException;

    /** Forgets everything remembered for the task; returns how many entries were removed. */
    int clear(String taskId) throws IOException;

    State state(String taskId) throws IOException;

    /** Starts the "watching since" clock for a task, if it has not started yet. */
    void startWatching(String taskId) throws IOException;

    /** Files arrived now. Returns true when a "no new files" alert was outstanding (so a "files are back" note is due). */
    boolean markFilesTransferred(String taskId) throws IOException;

    /**
     * Claims the right to raise the "no new files" alert: true for exactly one caller (even across the GUI and the
     * Daemon) when no alert was raised yet or the last one is at least {@code everyMillis} old ({@code <= 0}: always).
     */
    boolean claimSilenceAlert(String taskId, long everyMillis) throws IOException;
}
