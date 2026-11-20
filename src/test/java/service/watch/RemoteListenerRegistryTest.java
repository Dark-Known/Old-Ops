package service.watch;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import service.watch.RemoteListenerRegistry.Proc;
import service.watch.RemoteListenerRegistry.Store;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class RemoteListenerRegistryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final long NOW = 1_800_000_000_000L;

    private static Proc proc(String key, String role, long pid, long start, long at) {
        return new Proc(key, "10.0.0.5", "gv\\ops", role, pid, start, at);
    }

    /**
     * A database stand-in that keeps rows in a list and can be told to fail.
     */
    private static final class FakeStore implements Store {
        final List<Proc> rows = new ArrayList<>();
        boolean failing;

        private void check() throws Exception {
            if (failing) throw new Exception("disk full");
        }

        @Override
        public List<Proc> loadAll() throws Exception {
            check();
            return new ArrayList<>(rows);
        }

        @Override
        public void insert(Collection<Proc> procs) throws Exception {
            check();
            for (Proc p : procs) if (rows.stream().noneMatch(p::sameProcess)) rows.add(p);
        }

        @Override
        public void delete(Collection<Proc> procs) throws Exception {
            check();
            rows.removeIf(r -> procs.stream().anyMatch(r::sameProcess));
        }

        @Override
        public void purgeOlderThan(long t) throws Exception {
            check();
            rows.removeIf(r -> r.recordedAt < t);
        }
    }

    @Test
    public void recordedProcessesCanBeReadBackByListener() {
        RemoteListenerRegistry r = new RemoteListenerRegistry(new FakeStore(), () -> NOW);
        r.add(List.of(proc("k1", "listener", 4321, 1000, NOW), proc("k1", "shell", 5678, 900, NOW), proc("k2", "listener", 99, 5, NOW)));
        assertEquals(2, r.get("k1").size());
        assertEquals(1, r.get("k2").size());
        assertTrue(r.get("nothing").isEmpty());
        assertEquals(3, r.size());
    }

    @Test
    public void theSameProcessIsKeptOnce_butAReusedPidWithAnotherStartTimeIsADifferentProcess() {
        FakeStore db = new FakeStore();
        RemoteListenerRegistry r = new RemoteListenerRegistry(db, () -> NOW);
        r.add(List.of(proc("k", "listener", 4321, 1000, NOW)));
        r.add(List.of(proc("k", "listener", 4321, 1000, NOW + 5)));
        assertEquals(1, r.get("k").size());
        r.add(List.of(proc("k", "listener", 4321, 2000, NOW + 10)));
        assertEquals(2, r.get("k").size());
        assertEquals(2, db.rows.size());
    }

    @Test
    public void processesAreStillKnownAfterARestart() {
        FakeStore db = new FakeStore();
        new RemoteListenerRegistry(db, () -> NOW)
                .add(List.of(proc("k", "listener", 4321, 133_800_000_000_000_000L, NOW), proc("k", "shell", 5678, 133_800_000_000_000_001L, NOW)));
        List<Proc> got = new RemoteListenerRegistry(db, () -> NOW + 60_000).get("k");
        assertEquals(2, got.size());
        assertEquals(4321, got.get(0).pid);
        assertEquals(133_800_000_000_000_000L, got.get(0).start);
        assertEquals("gv\\ops", got.get(0).user);
    }

    @Test
    public void forgettingOneProcessKeepsTheOthersAndIsSaved() {
        FakeStore db = new FakeStore();
        RemoteListenerRegistry r = new RemoteListenerRegistry(db, () -> NOW);
        Proc a = proc("k", "listener", 1, 10, NOW), b = proc("k", "shell", 2, 20, NOW);
        r.add(List.of(a, b));
        r.remove("k", List.of(a));
        assertEquals(List.of(b), r.get("k"));
        assertEquals(List.of(b), db.rows);
        r.remove("k", List.of(b));
        assertTrue(db.rows.isEmpty());
        assertEquals(0, r.size());
    }

    @Test
    public void entriesOlderThanThirtyDaysAreDroppedOnStart() {
        AtomicLong clock = new AtomicLong(NOW);
        FakeStore db = new FakeStore();
        new RemoteListenerRegistry(db, clock::get).add(List.of(proc("k", "listener", 1, 10, NOW)));
        clock.addAndGet(TimeUnit.DAYS.toMillis(31));
        assertEquals("a pid that old has been reused", 0, new RemoteListenerRegistry(db, clock::get).size());
        assertTrue(db.rows.isEmpty());
    }

    @Test
    public void aBrokenDatabaseNeverStopsAnything() {
        FakeStore db = new FakeStore();
        db.failing = true;
        RemoteListenerRegistry r = new RemoteListenerRegistry(db, () -> NOW);   // load fails: starts empty
        Proc a = proc("k", "listener", 1, 10, NOW);
        r.add(List.of(a));                                                       // save fails: kept in memory
        assertEquals(List.of(a), r.get("k"));
        r.remove("k", List.of(a));
        assertTrue(r.get("k").isEmpty());
    }

    @Test
    public void withoutAStoreItWorksInMemory() {
        RemoteListenerRegistry r = new RemoteListenerRegistry(null, () -> NOW);
        r.add(List.of(proc("k", "listener", 1, 10, NOW)));
        assertEquals(1, r.get("k").size());
    }

    // ── the old tab-separated file ──────────────────────────────────────────

    private Path legacy(String... lines) throws Exception {
        Path f = new File(tmp.getRoot(), "remote-listeners.tsv").toPath();
        Files.write(f, List.of(lines), StandardCharsets.UTF_8);
        return f;
    }

    @Test
    public void theOldFileIsImportedOnceAndRenamed() throws Exception {
        String key = "WINDOWS|10.0.0.5|22|gv%5Cops|d%3A%5C\u00fcn";   // %-encoded as the old writer did
        Path f = legacy("v1\t" + key + "\th\tu\tlistener\t4321\t1000\t" + NOW,
                "v1\tk2\th\tu\tshell\t5678\t900\t" + NOW);
        FakeStore db = new FakeStore();
        RemoteListenerRegistry r = new RemoteListenerRegistry(db, () -> NOW);
        r.importLegacy(f);
        assertEquals(2, r.size());
        assertEquals(2, db.rows.size());
        assertEquals("WINDOWS|10.0.0.5|22|gv\\ops|d:\\\u00fcn", db.rows.get(0).key);
        assertFalse(Files.exists(f));
        assertTrue(Files.exists(f.resolveSibling("remote-listeners.tsv.migrated")));
        r.importLegacy(f);   // nothing left to import
        assertEquals(2, db.rows.size());
    }

    @Test
    public void unreadableOrOldLinesInTheOldFileAreSkipped() throws Exception {
        long old = NOW - TimeUnit.DAYS.toMillis(31);
        Path f = legacy("garbage", "v1\tonly\tthree", "v9\tk\th\tu\tlistener\t10\t20\t" + NOW,
                "v1\tk\th\tu\tlistener\tNaN\t20\t" + NOW, "v1\tk\th\tu\tlistener\t-5\t20\t" + NOW,
                "v1\tk\th\tu\tlistener\t10\t0\t" + NOW, "v1\t%zz\th\tu\tlistener\t10\t20\t" + NOW,
                "v1\tk\th\tu\tlistener\t11\t20\t" + old,
                "v1\tk\th\tu\tlistener\t10\t20\t" + NOW);
        RemoteListenerRegistry r = new RemoteListenerRegistry(new FakeStore(), () -> NOW);
        r.importLegacy(f);
        assertEquals(1, r.size());
        assertEquals(10, r.get("k").get(0).pid);
    }

    @Test
    public void ifTheImportCannotBeSavedTheOldFileStaysForNextTime() throws Exception {
        Path f = legacy("v1\tk\th\tu\tlistener\t10\t20\t" + NOW);
        FakeStore db = new FakeStore();
        RemoteListenerRegistry r = new RemoteListenerRegistry(db, () -> NOW);
        db.failing = true;
        r.importLegacy(f);
        assertTrue(Files.exists(f));
    }
}
