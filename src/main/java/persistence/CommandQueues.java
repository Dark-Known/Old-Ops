package persistence;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.Function;

/**
 * Where the application gets its {@link CommandQueue}. The storage implementation installs its own when
 * it is opened (see {@code SqliteStorage}); before that, queueing fails and the daemon's poller sees nothing.
 */
public final class CommandQueues {

    private static volatile Function<File, CommandQueue> factory = dir -> Unavailable.INSTANCE;

    private CommandQueues() { }

    /** Called by a storage implementation when it is opened. Safe to call more than once. */
    public static void install(Function<File, CommandQueue> queueForDataDir) {
        factory = queueForDataDir;
    }

    public static CommandQueue forDataDir(File dataDir) {
        return factory.apply(dataDir);
    }

    private static final class Unavailable implements CommandQueue {
        static final Unavailable INSTANCE = new Unavailable();

        @Override public void enqueue(String taskId, Action action, String requestedBy) throws IOException {
            throw new IOException("no command queue is configured (storage has not been opened)");
        }

        @Override public Poller poller() {
            return new Poller() {
                @Override public List<Command> pollPending() { return List.of(); }
                @Override public void markDone(long id) { }
                @Override public void markFailed(long id, String detail) { }
                @Override public void close() { }
            };
        }
    }
}
