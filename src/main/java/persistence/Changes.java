package persistence;

import java.io.File;
import java.util.function.Function;

/**
 * Where the application gets its {@link ChangeSignals}. The storage implementation installs its own when
 * it is opened (see {@code SqliteStorage}); until then, and for storage that cannot tell, consumers get a
 * signal that never fires, so they fall back on their periodic refresh.
 */
public final class Changes {

    private static volatile Function<File, ChangeSignals> factory = dir -> Never.INSTANCE;

    private Changes() { }

    /** Called by a storage implementation when it is opened. Safe to call more than once. */
    public static void install(Function<File, ChangeSignals> signalsForDataDir) {
        factory = signalsForDataDir;
    }

    /** The change signals for the data folder. */
    public static ChangeSignals forDataDir(File dataDir) {
        return factory.apply(dataDir);
    }

    /** A signal that never fires: waiting simply lasts until the timeout. */
    private static final class Never implements ChangeSignals {
        static final Never INSTANCE = new Never();
        @Override public long generation(Topic topic) { return 0; }
        @Override public long lastPollMillis() { return 0; }
        @Override public String describe() { return "no change signal"; }
        @Override public long signalCount(Topic topic) { return 0; }
        @Override public Runnable subscribe(Topic topic, Runnable onChange) { return () -> { }; }
        @Override public long awaitChange(Topic topic, long seen, long timeoutMs) throws InterruptedException {
            Thread.sleep(Math.max(0, timeoutMs));
            return 0;
        }
    }
}
