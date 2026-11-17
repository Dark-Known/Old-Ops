package persistence;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Commands a thin GUI leaves for the daemon (the one process that owns the worker pool), for when the
 * daemon cannot be reached directly. The GUI {@link #enqueue}s; the daemon drains them with a
 * {@link Poller}. Stale commands expire rather than run hours late.
 */
public interface CommandQueue {

    enum Action { RUN_NOW, CANCEL, RESTART, RECONNECT_WATCH, REFRESH, PURGE_NOW }

    /** One waiting command, as read back by the daemon. */
    record Command(long id, String taskId, Action action, String requestedBy, LocalDateTime requestedAt) {}

    /** A pending command older than this is not run: the user asked minutes ago and has moved on. */
    long PENDING_TTL_MINUTES = 10;

    /** Leaves a command for the daemon. Throws when it could not be stored. */
    void enqueue(String taskId, Action action, String requestedBy) throws IOException;

    /** A poller for the daemon's loop. Single-threaded use; close it when done. */
    Poller poller();

    /** The daemon's side: pending commands, oldest first, and how each one ended. */
    interface Poller extends AutoCloseable {
        /** Pending commands, oldest first; housekeeping (expiring stale ones, pruning old ones) runs only when due. */
        List<Command> pollPending();

        void markDone(long id);

        void markFailed(long id, String detail);

        @Override void close();
    }
}
