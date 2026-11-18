package persistence;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.Function;

/**
 * Where the application gets its {@link AppDb}. The storage implementation installs its own at startup (see
 * {@code SqliteStorage.installServices}); until then reads find nothing and writes fail with a clear message.
 */
public final class AppDbs {

    private static volatile Function<File, AppDb> factory = dir -> Unavailable.INSTANCE;

    private AppDbs() { }

    /** Called by a storage implementation at startup. Safe to call more than once. */
    public static void install(Function<File, AppDb> forDataDir) { factory = forDataDir; }

    public static AppDb forDataDir(File dataDir) { return factory.apply(dataDir); }

    private static final class Unavailable implements AppDb {
        static final Unavailable INSTANCE = new Unavailable();
        @Override public String get(String name) { return null; }
        @Override public void put(String name, String value) throws IOException { throw new IOException("no storage is configured"); }
        @Override public long stamp(String name) { return -2; }
        @Override public long enqueueAlert(long queuedAt, String json) throws IOException { throw new IOException("no storage is configured"); }
        @Override public void deleteAlert(long id) { }
        @Override public List<QueuedAlert> pendingAlerts() { return List.of(); }
    }
}
