package service.watch;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * The remote processes this installation's listeners have reported, kept on disk so they are still known after the
 * daemon restarts. When a listener is armed again, the service ends exactly the processes listed here for that
 * listener (see {@link RemoteProcessJanitor}) and nothing else, so it never touches a process that belongs to another
 * installation, another task or another program.
 *
 * <p>Stored in {@code app.db} (table {@code remote_processes}, one row per process), next to the tasks and settings.
 * {@code start} is the process start time as a Windows FILETIME; with the pid it identifies the process even after the
 * pid number is reused. Entries older than {@value #MAX_AGE_DAYS} days are dropped (a pid that old has certainly been
 * reused). The older {@code remote-listeners.tsv} is imported once and renamed to {@code .migrated}. If the database
 * cannot be used the records stay in memory only; that is logged and never stops a listener.
 */
final class RemoteListenerRegistry {

    private static final Logger log = Logger.getLogger(RemoteListenerRegistry.class.getName());
    static final int MAX_AGE_DAYS = 30;
    static final String LEGACY_FILE = "remote-listeners.tsv";
    private static final String LEGACY_VERSION = "v1";

    /**
     * One remote process. {@code role} is "listener" (the PowerShell running the watcher) or "shell" (the cmd.exe it runs under).
     */
    static final class Proc {
        final String key;
        final String host;
        final String user;
        final String role;
        final long pid;
        final long start;
        final long recordedAt;

        Proc(String key, String host, String user, String role, long pid, long start, long recordedAt) {
            this.key = key;
            this.host = host;
            this.user = user;
            this.role = role;
            this.pid = pid;
            this.start = start;
            this.recordedAt = recordedAt;
        }

        boolean sameProcess(Proc o) {
            return key.equals(o.key) && pid == o.pid && start == o.start;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Proc p && sameProcess(p);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, pid, start);
        }

        @Override
        public String toString() {
            return role + " pid " + pid;
        }
    }

    /**
     * Where the records are kept. Methods may throw; the registry logs the failure and carries on from memory.
     */
    interface Store {
        List<Proc> loadAll() throws Exception;

        void insert(Collection<Proc> procs) throws Exception;

        void delete(Collection<Proc> procs) throws Exception;

        void purgeOlderThan(long recordedAtMillis) throws Exception;
    }

    private final Store store;         // null: kept in memory only
    private final LongSupplier clock;
    private final Map<String, List<Proc>> byKey = new LinkedHashMap<>();

    /**
     * The registry stored in {@code dataDir} (null: memory only); imports the old tab-separated file once.
     */
    static RemoteListenerRegistry inDataDir(File dataDir) {
        if (dataDir == null) return new RemoteListenerRegistry(null);
        RemoteListenerRegistry r = new RemoteListenerRegistry(new PortStore(persistence.RemoteProcessStores.forDataDir(dataDir)));
        r.importLegacy(new File(dataDir, LEGACY_FILE).toPath());
        return r;
    }

    RemoteListenerRegistry(Store store) {
        this(store, System::currentTimeMillis);
    }

    RemoteListenerRegistry(Store store, LongSupplier clock) {
        this.store = store;
        this.clock = clock;
        load();
    }

    /**
     * The recorded processes of one listener, oldest first.
     */
    synchronized List<Proc> get(String key) {
        return new ArrayList<>(byKey.getOrDefault(key, List.of()));
    }

    synchronized int size() {
        return byKey.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Records processes (a process already recorded with the same pid and start time is kept once).
     */
    synchronized void add(Collection<Proc> procs) {
        List<Proc> fresh = new ArrayList<>();
        for (Proc p : procs) {
            List<Proc> list = byKey.computeIfAbsent(p.key, k -> new ArrayList<>());
            if (list.stream().noneMatch(p::sameProcess)) {
                list.add(p);
                fresh.add(p);
            }
        }
        if (fresh.isEmpty() || store == null) return;
        try {
            store.insert(fresh);
        } catch (Exception e) {
            log.warning("Could not save the recorded remote processes: " + e.getMessage() + " (kept in memory only)");
        }
    }

    /**
     * Forgets processes that have been dealt with (ended, gone, or no longer the same process).
     */
    synchronized void remove(String key, Collection<Proc> procs) {
        List<Proc> list = byKey.get(key);
        if (list == null) return;
        List<Proc> gone = new ArrayList<>();
        for (Proc x : list) if (procs.stream().anyMatch(x::sameProcess)) gone.add(x);
        list.removeAll(gone);
        if (list.isEmpty()) byKey.remove(key);
        if (gone.isEmpty() || store == null) return;
        try {
            store.delete(gone);
        } catch (Exception e) {
            log.warning("Could not update the recorded remote processes: " + e.getMessage());
        }
    }

    // ── persistence ─────────────────────────────────────────────────────────

    private void load() {
        if (store == null) return;
        long oldest = clock.getAsLong() - TimeUnit.DAYS.toMillis(MAX_AGE_DAYS);
        try {
            store.purgeOlderThan(oldest);
            for (Proc p : store.loadAll()) {
                if (p.recordedAt < oldest) continue;
                List<Proc> list = byKey.computeIfAbsent(p.key, k -> new ArrayList<>());
                if (list.stream().noneMatch(p::sameProcess)) list.add(p);
            }
        } catch (Exception e) {
            log.warning("Could not read the recorded remote processes: " + e.getMessage() + " (starting with none)");
        }
    }

    /**
     * Imports the old {@code remote-listeners.tsv} and renames it so it is only done once. Unreadable lines are skipped.
     * If the import cannot be saved the file is left where it is and tried again next start.
     */
    synchronized void importLegacy(Path legacy) {
        if (!Files.isRegularFile(legacy)) return;
        long oldest = clock.getAsLong() - TimeUnit.DAYS.toMillis(MAX_AGE_DAYS);
        List<Proc> found = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(legacy, StandardCharsets.UTF_8)) {
                Proc p = parseLegacy(line);
                if (p != null && p.recordedAt >= oldest) found.add(p);
            }
        } catch (IOException e) {
            log.warning("Could not read " + legacy + ": " + e.getMessage());
            return;
        }
        if (store != null && !found.isEmpty()) {
            List<Proc> fresh = new ArrayList<>();
            for (Proc p : found) {
                List<Proc> list = byKey.computeIfAbsent(p.key, k -> new ArrayList<>());
                if (list.stream().noneMatch(p::sameProcess)) {
                    list.add(p);
                    fresh.add(p);
                }
            }
            try {
                store.insert(fresh);
            } catch (Exception e) {
                log.warning("Could not import " + legacy + ": " + e.getMessage() + " (will try again next start)");
                return;
            }
        }
        try {
            Files.move(legacy, legacy.resolveSibling(legacy.getFileName() + ".migrated"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warning("Imported " + legacy + " but could not rename it: " + e.getMessage());
        }
    }

    static Proc parseLegacy(String line) {
        String[] f = line.split("\t", -1);
        if (f.length != 8 || !f[0].equals(LEGACY_VERSION)) return null;
        try {
            long pid = Long.parseLong(f[5]), start = Long.parseLong(f[6]), at = Long.parseLong(f[7]);
            if (pid <= 0 || start <= 0) return null;
            return new Proc(dec(f[1]), dec(f[2]), dec(f[3]), dec(f[4]), pid, start, at);
        } catch (IllegalArgumentException e) {   // NumberFormatException and bad %-escapes
            return null;
        }
    }

    private static String dec(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    /**
     * Adapts the storage port ({@link persistence.RemoteProcessStore}) to the registry's {@link Store}.
     */
    static final class PortStore implements Store {
        private final persistence.RemoteProcessStore port;

        PortStore(persistence.RemoteProcessStore port) {
            this.port = port;
        }

        private static persistence.RemoteProcessStore.Row row(Proc p) {
            return new persistence.RemoteProcessStore.Row(p.key, p.host, p.user, p.role, p.pid, p.start, p.recordedAt);
        }

        private static List<persistence.RemoteProcessStore.Row> rows(Collection<Proc> procs) {
            List<persistence.RemoteProcessStore.Row> out = new ArrayList<>();
            for (Proc p : procs) out.add(row(p));
            return out;
        }

        @Override
        public List<Proc> loadAll() throws IOException {
            List<Proc> out = new ArrayList<>();
            for (persistence.RemoteProcessStore.Row r : port.loadAll()) {
                out.add(new Proc(r.listenerKey(), r.host(), r.user(), r.role(), r.pid(), r.startFileTime(), r.recordedAt()));
            }
            return out;
        }

        @Override
        public void insert(Collection<Proc> procs) throws IOException {
            port.insert(rows(procs));
        }

        @Override
        public void delete(Collection<Proc> procs) throws IOException {
            port.delete(rows(procs));
        }

        @Override
        public void purgeOlderThan(long recordedAtMillis) throws IOException {
            port.purgeOlderThan(recordedAtMillis);
        }
    }
}
