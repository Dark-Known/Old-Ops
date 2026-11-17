package persistence;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.function.Function;

/**
 * Where the application gets its {@link SettingsStore}. The storage implementation installs its own at
 * startup (see {@code SqliteStorage.installServices}); until then there is a store that holds nothing and
 * reports itself disconnected, so settings fall back to their defaults instead of failing.
 */
public final class SettingsStores {

    private static volatile Function<File, SettingsStore> factory = dir -> Unavailable.INSTANCE;

    private SettingsStores() { }

    /** Called by a storage implementation at startup. Safe to call more than once. */
    public static void install(Function<File, SettingsStore> storeForDataDir) {
        factory = storeForDataDir;
    }

    /** Back to "no store configured", as at JVM start. For tests that install a fake. */
    public static void reset() {
        factory = dir -> Unavailable.INSTANCE;
    }

    public static SettingsStore forDataDir(File dataDir) {
        return factory.apply(dataDir);
    }

    private static final class Unavailable implements SettingsStore {
        static final Unavailable INSTANCE = new Unavailable();
        @Override public Map<String, String> readAll() { return Collections.emptyMap(); }
        @Override public boolean replaceAll(Map<String, String> values) { return false; }
        @Override public boolean isConnected() { return false; }
        @Override public String connectionError() { return "no settings store is configured"; }
        @Override public String location() { return "(no settings store configured)"; }
    }
}
