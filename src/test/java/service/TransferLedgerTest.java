package service;

import static org.junit.Assert.*;

import model.FileSelection.SentPolicy;
import org.junit.Test;
import service.TransferLedger.Entry;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Needs the sqlite-jdbc driver on the classpath (it is a normal dependency of the project). */
public class TransferLedgerTest {

    private static File tempDir() throws Exception {
        File d = Files.createTempDirectory("opstool-ledger").toFile();
        d.deleteOnExit();
        return d;
    }

    private static final long T0 = 1_792_929_600_000L;

    @Test public void unchangedMeansSameNameSizeAndTime() throws Exception {
        TransferLedger l = new TransferLedger(tempDir());
        l.record("task1", Arrays.asList(new Entry("a.csv", 100, 5000)));
        TransferLedger.Snapshot s = l.snapshot("task1");
        assertTrue(s.wasSent("a.csv", 100, 5000, SentPolicy.UNCHANGED));
        assertFalse("edited (new time) is sent again", s.wasSent("a.csv", 100, 6000, SentPolicy.UNCHANGED));
        assertFalse("edited (new size) is sent again", s.wasSent("a.csv", 101, 5000, SentPolicy.UNCHANGED));
        assertFalse(s.wasSent("b.csv", 100, 5000, SentPolicy.UNCHANGED));
    }

    @Test public void anyNameMeansTheNameIsSentOnlyOnce() throws Exception {
        TransferLedger l = new TransferLedger(tempDir());
        l.record("task1", Arrays.asList(new Entry("a.csv", 100, 5000)));
        TransferLedger.Snapshot s = l.snapshot("task1");
        assertTrue(s.wasSent("a.csv", 999, 1, SentPolicy.ANY_NAME));
        assertFalse(s.wasSent("A.csv", 100, 5000, SentPolicy.ANY_NAME));  // names are compared exactly
        assertFalse(s.wasSent("other.csv", 100, 5000, SentPolicy.ANY_NAME));
    }

    @Test public void sendAgainEveryRunNeverSkips() throws Exception {
        TransferLedger l = new TransferLedger(tempDir());
        l.record("task1", Arrays.asList(new Entry("a.csv", 100, 5000)));
        assertFalse(l.snapshot("task1").wasSent("a.csv", 100, 5000, SentPolicy.ALWAYS));
    }

    @Test public void eachTaskHasItsOwnMemory() throws Exception {
        TransferLedger l = new TransferLedger(tempDir());
        l.record("task1", Arrays.asList(new Entry("a.csv", 1, 1)));
        assertTrue(l.snapshot("task1").wasSent("a.csv", 1, 1, SentPolicy.UNCHANGED));
        assertFalse(l.snapshot("task2").wasSent("a.csv", 1, 1, SentPolicy.UNCHANGED));
        l.record("task2", Arrays.asList(new Entry("b.csv", 2, 2)));
        assertEquals(1, l.count("task1"));
        assertEquals(1, l.count("task2"));
    }

    @Test public void recordingTheSameFileAgainDoesNotDuplicateIt() throws Exception {
        TransferLedger l = new TransferLedger(tempDir());
        List<Entry> e = Arrays.asList(new Entry("a.csv", 1, 1), new Entry("b.csv", 2, 2));
        l.record("task1", e);
        l.record("task1", e);
        assertEquals(2, l.count("task1"));
        assertEquals(2, l.snapshot("task1").size());
    }

    @Test public void forgetClearsOnlyThatTaskAndReportsHowMany() throws Exception {
        TransferLedger l = new TransferLedger(tempDir());
        l.record("task1", Arrays.asList(new Entry("a", 1, 1), new Entry("b", 2, 2), new Entry("c", 3, 3)));
        l.record("task2", Arrays.asList(new Entry("a", 1, 1)));
        assertEquals(3, l.clear("task1"));
        assertEquals(0, l.count("task1"));
        assertEquals(1, l.count("task2"));
        assertFalse(l.snapshot("task1").wasSent("a", 1, 1, SentPolicy.UNCHANGED));
        assertEquals(0, l.clear("task1"));
    }

    @Test public void entriesOlderThan180DaysAreDropped() throws Exception {
        File dir = tempDir();
        AtomicLong now = new AtomicLong(T0);
        TransferLedger l = new TransferLedger(dir, now::get);
        l.record("task1", Arrays.asList(new Entry("old.csv", 1, 1)));

        now.set(T0 + TimeUnit.DAYS.toMillis(179));
        l.record("task1", Arrays.asList(new Entry("newer.csv", 2, 2)));
        assertEquals("both still remembered after 179 days", 2, l.count("task1"));
        assertTrue(l.snapshot("task1").wasSent("old.csv", 1, 1, SentPolicy.UNCHANGED));

        now.set(T0 + TimeUnit.DAYS.toMillis(181));
        assertEquals("the 181-day-old entry is gone, the 2-day-old one stays", 1, l.count("task1"));
        TransferLedger.Snapshot s = l.snapshot("task1");
        assertFalse(s.wasSent("old.csv", 1, 1, SentPolicy.UNCHANGED));
        assertTrue(s.wasSent("newer.csv", 2, 2, SentPolicy.UNCHANGED));
    }

    @Test public void sendingAFileAgainRefreshesItsAge() throws Exception {
        AtomicLong now = new AtomicLong(T0);
        TransferLedger l = new TransferLedger(tempDir(), now::get);
        l.record("task1", Arrays.asList(new Entry("a", 1, 1)));
        now.set(T0 + TimeUnit.DAYS.toMillis(170));
        l.record("task1", Arrays.asList(new Entry("a", 1, 1)));
        now.set(T0 + TimeUnit.DAYS.toMillis(300));   // 130 days after the refresh
        assertEquals(1, l.count("task1"));
    }

    @Test public void awkwardNamesAndEmptyInputWork() throws Exception {
        TransferLedger l = new TransferLedger(tempDir());
        String odd = "résumé \"final\" 100%;x'y.txt";
        l.record("task'1", Arrays.asList(new Entry(odd, 0, 0)));
        l.record("task'1", null);
        l.record("task'1", Arrays.<Entry>asList());
        assertTrue(l.snapshot("task'1").wasSent(odd, 0, 0, SentPolicy.UNCHANGED));
    }

    @Test public void theMemoryLivesInItsOwnFileInTheDataFolder() throws Exception {
        File dir = tempDir();
        new TransferLedger(dir).record("t", Arrays.asList(new Entry("a", 1, 1)));
        assertTrue(new File(dir, "transfer_ledger.db").isFile());
        // a second instance (e.g. the Daemon next to the GUI) sees the same data
        assertEquals(1, new TransferLedger(dir).count("t"));
    }
}
