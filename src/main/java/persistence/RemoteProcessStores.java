package persistence;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * Where the application gets its {@link RemoteProcessStore}. The storage implementation installs its own at startup (see
 * {@code SqliteStorage.installServices}); until then nothing is stored and writes fail with a clear message.
 */
public final class RemoteProcessStores {

    private static volatile Function<File, RemoteProcessStore> factory = dir -> Unavailable.INSTANCE;

    private RemoteProcessStores() { }

    public static void install(Function<File, RemoteProcessStore> forDataDir) { factory = forDataDir; }

    public static RemoteProcessStore forDataDir(File dataDir) { return factory.apply(dataDir); }

    private static final class Unavailable implements RemoteProcessStore {
        static final Unavailable INSTANCE = new Unavailable();
        @Override public List<Row> loadAll() { return List.of(); }
        @Override public void insert(Collection<Row> rows) throws IOException { throw new IOException("no storage is configured"); }
        @Override public void delete(Collection<Row> rows) throws IOException { throw new IOException("no storage is configured"); }
        @Override public void purgeOlderThan(long recordedAtMillis) { }
    }
}
