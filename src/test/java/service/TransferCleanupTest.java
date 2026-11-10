package service;

import model.FileSelection.AfterAction;
import org.junit.Test;
import service.TransferCleanup.LocalOriginal;
import service.TransferCleanup.RemoteMove;
import service.TransferCleanup.Result;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.Assert.*;

public class TransferCleanupTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 26, 10, 15, 0);
    private static final List<String> LOG = new ArrayList<>();

    private static Path dir() throws Exception {
        Path d = Files.createTempDirectory("opstool-cleanup");
        d.toFile().deleteOnExit();
        return d;
    }

    private static Path file(Path dir, String name, String content) throws Exception {
        Path p = dir.resolve(name);
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    private static LocalOriginal listed(Path p) throws Exception {
        return new LocalOriginal(p, Files.size(p), Files.getLastModifiedTime(p).toMillis());
    }

    private static String text(Path p) throws Exception {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static void log(String s) {
        LOG.add(s);
    }

    // ── naming ───────────────────────────────────────────────────────

    @Test
    public void aFreeNameIsKeptAndATakenNameGetsATimestamp() {
        Set<String> taken = new HashSet<>(Arrays.asList("a.csv"));
        assertEquals("b.csv", TransferCleanup.archiveName("b.csv", taken, NOW));
        assertEquals("a_20261026_101500.csv", TransferCleanup.archiveName("a.csv", taken, NOW));
    }

    @Test
    public void ifEvenTheTimestampedNameIsTakenANumberIsAdded() {
        Set<String> taken = new HashSet<>(Arrays.asList("a.csv", "a_20261026_101500.csv", "a_20261026_101500_2.csv"));
        assertEquals("a_20261026_101500_3.csv", TransferCleanup.archiveName("a.csv", taken, NOW));
    }

    @Test
    public void onlyTheLastDotCountsAsTheExtension() {
        Set<String> taken = new HashSet<>(Arrays.asList("x.tar.gz", "noext", ".env"));
        assertEquals("x.tar_20261026_101500.gz", TransferCleanup.archiveName("x.tar.gz", taken, NOW));
        assertEquals("noext_20261026_101500", TransferCleanup.archiveName("noext", taken, NOW));
        assertEquals(".env_20261026_101500", TransferCleanup.archiveName(".env", taken, NOW));
    }

    @Test
    public void remotePlanAvoidsExistingNamesAndNamesChosenWithinTheSameBatch() {
        Set<String> existing = new HashSet<>(Arrays.asList("a.csv"));
        List<RemoteMove> plan = TransferCleanup.planRemoteMoves(
                Arrays.asList("/in/a.csv", "/in/b.csv", "/other/b.csv"), "/archive", existing, NOW);
        assertEquals(3, plan.size());
        assertEquals("/archive/a_20261026_101500.csv", plan.get(0).to());
        assertEquals("/archive/b.csv", plan.get(1).to());
        assertEquals("/archive/b_20261026_101500.csv", plan.get(2).to());
        assertEquals("/in/a.csv", plan.get(0).from());
        // a trailing slash on the folder makes no difference
        assertEquals("/archive/c.csv", TransferCleanup.planRemoteMoves(
                Arrays.asList("/in/c.csv"), "/archive/", new HashSet<String>(), NOW).get(0).to());
    }

    // ── safety check for downloads ───────────────────────────────────

    @Test
    public void aDownloadedCopyMustExistAtTheRightSize() throws Exception {
        Path d = dir();
        Path p = file(d, "a.txt", "12345");
        assertTrue(TransferCleanup.copyIsComplete(p, 5));
        assertFalse("wrong size", TransferCleanup.copyIsComplete(p, 6));
        assertFalse("missing", TransferCleanup.copyIsComplete(d.resolve("nope.txt"), 5));
        assertFalse("a folder is not a copy", TransferCleanup.copyIsComplete(d, -1));
        assertTrue("size unknown: existence is enough", TransferCleanup.copyIsComplete(p, -1));
        assertTrue("empty file of size 0", TransferCleanup.copyIsComplete(file(d, "e.txt", ""), 0));
    }

    // ── local delete ─────────────────────────────────────────────────

    @Test
    public void deleteRemovesTheOriginals() throws Exception {
        Path d = dir();
        Path a = file(d, "a.txt", "A"), b = file(d, "b.txt", "B");
        Result r = TransferCleanup.cleanupLocal(Arrays.asList(listed(a), listed(b)), AfterAction.DELETE, null, TransferCleanupTest::log, NOW);
        assertEquals(2, r.done());
        assertTrue(r.clean());
        assertFalse(Files.exists(a));
        assertFalse(Files.exists(b));
    }

    @Test
    public void keepTouchesNothing() throws Exception {
        Path d = dir();
        Path a = file(d, "a.txt", "A");
        Result r = TransferCleanup.cleanupLocal(Arrays.asList(listed(a)), AfterAction.KEEP, null, TransferCleanupTest::log, NOW);
        assertEquals(0, r.done());
        assertTrue(Files.exists(a));
    }

    @Test
    public void aFileThatChangedAfterItWasListedIsKept() throws Exception {
        Path d = dir();
        Path a = file(d, "a.txt", "first");
        LocalOriginal seen = listed(a);
        Files.write(a, "first and more, written while the upload was running".getBytes(StandardCharsets.UTF_8));
        LOG.clear();
        Result r = TransferCleanup.cleanupLocal(Arrays.asList(seen), AfterAction.DELETE, null, TransferCleanupTest::log, NOW);
        assertEquals(1, r.kept());
        assertEquals(0, r.done());
        assertTrue("the changed file must survive", Files.exists(a));
        assertTrue(LOG.toString(), LOG.stream().anyMatch(l -> l.contains("changed after it was listed")));
    }

    @Test
    public void aFileThatIsAlreadyGoneIsNotAFailure() throws Exception {
        Path d = dir();
        Path a = file(d, "a.txt", "A");
        LocalOriginal seen = listed(a);
        Files.delete(a);
        Result r = TransferCleanup.cleanupLocal(Arrays.asList(seen), AfterAction.DELETE, null, TransferCleanupTest::log, NOW);
        assertTrue(r.clean());
    }

    // ── local move ───────────────────────────────────────────────────

    @Test
    public void moveCreatesTheFolderAndMovesTheFile() throws Exception {
        Path d = dir();
        Path a = file(d, "a.txt", "A");
        Path archive = d.resolve("archive").resolve("2026");     // does not exist yet, nested
        Result r = TransferCleanup.cleanupLocal(Arrays.asList(listed(a)), AfterAction.MOVE, archive, TransferCleanupTest::log, NOW);
        assertEquals(1, r.done());
        assertFalse(Files.exists(a));
        assertEquals("A", text(archive.resolve("a.txt")));
    }

    @Test
    public void moveNeverOverwritesAnArchivedFile() throws Exception {
        Path d = dir();
        Path archive = d.resolve("archive");
        Files.createDirectories(archive);
        file(archive, "a.txt", "OLD ARCHIVED CONTENT");
        Path a = file(d, "a.txt", "NEW");
        Result r = TransferCleanup.cleanupLocal(Arrays.asList(listed(a)), AfterAction.MOVE, archive, TransferCleanupTest::log, NOW);
        assertEquals(1, r.done());
        assertEquals("the old archive file is untouched", "OLD ARCHIVED CONTENT", text(archive.resolve("a.txt")));
        assertEquals("NEW", text(archive.resolve("a_20261026_101500.txt")));
        assertFalse(Files.exists(a));
    }

    @Test
    public void twoOriginalsWithTheSameNameBothSurviveInTheArchive() throws Exception {
        Path d = dir();
        Path one = Files.createDirectories(d.resolve("one"));
        Path two = Files.createDirectories(d.resolve("two"));
        Path a1 = file(one, "a.txt", "ONE"), a2 = file(two, "a.txt", "TWO");
        Path archive = d.resolve("archive");
        Result r = TransferCleanup.cleanupLocal(Arrays.asList(listed(a1), listed(a2)), AfterAction.MOVE, archive, TransferCleanupTest::log, NOW);
        assertEquals(2, r.done());
        assertEquals("ONE", text(archive.resolve("a.txt")));
        assertEquals("TWO", text(archive.resolve("a_20261026_101500.txt")));
    }

    @Test
    public void ifTheArchiveFolderCannotBeUsedTheOriginalsStayPut() throws Exception {
        Path d = dir();
        Path a = file(d, "a.txt", "A");
        Path notAFolder = file(d, "archive", "I am a file, not a folder");
        LOG.clear();
        Result r = TransferCleanup.cleanupLocal(Arrays.asList(listed(a)), AfterAction.MOVE, notAFolder, TransferCleanupTest::log, NOW);
        assertEquals(1, r.failed());
        assertFalse(r.clean());
        assertTrue("original must still be there", Files.exists(a));
        assertTrue(LOG.toString(), LOG.stream().anyMatch(l -> l.contains("still in place")));
    }

    @Test
    public void oneFailureDoesNotStopTheRestOfTheCleanup() throws Exception {
        Path d = dir();
        Path a = file(d, "a.txt", "A");
        Path b = file(d, "b.txt", "B");
        Path stuck = Files.createDirectories(d.resolve("stuck"));   // a non-empty folder cannot be deleted as a file
        file(stuck, "inside.txt", "x");
        LOG.clear();
        Result r = TransferCleanup.cleanupLocal(Arrays.asList(listed(a), listed(stuck), listed(b)), AfterAction.DELETE, null, TransferCleanupTest::log, NOW);
        assertEquals(2, r.done());
        assertEquals(1, r.failed());
        assertFalse(Files.exists(a));
        assertFalse(Files.exists(b));
        assertTrue(Files.exists(stuck));
        assertTrue(LOG.toString(), LOG.stream().anyMatch(l -> l.contains("still in place") && l.contains("stuck")));
    }
}
