package service;

import static org.junit.Assert.*;

import org.junit.Test;
import service.SilenceWatch.Verdict;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;

public class SilenceWatchTest {

    private static final long MIN = 60_000L;

    @Test public void nothingRecordedStartsTheClock() {
        assertEquals(Verdict.START, SilenceWatch.judge(0, 0, 0, 100, 10 * MIN));
    }

    @Test public void recentFilesAreFineAndOldOnesAreOverdue() {
        assertEquals(Verdict.OK, SilenceWatch.judge(100 * MIN, 0, 0, 105 * MIN, 10 * MIN));
        assertEquals(Verdict.OVERDUE, SilenceWatch.judge(100 * MIN, 0, 0, 110 * MIN, 10 * MIN));
        assertEquals(Verdict.OVERDUE, SilenceWatch.judge(100 * MIN, 0, 0, 500 * MIN, 10 * MIN));
    }

    @Test public void neverReceivedCountsFromWhenWatchingStarted() {
        assertEquals(Verdict.OK, SilenceWatch.judge(0, 100 * MIN, 0, 105 * MIN, 10 * MIN));
        assertEquals(Verdict.OVERDUE, SilenceWatch.judge(0, 100 * MIN, 0, 111 * MIN, 10 * MIN));
    }

    @Test public void timeWhenTheAppWasNotRunningDoesNotCount() {
        // last file at minute 100, the app was off, started at minute 400: quiet only since 400
        assertEquals(Verdict.OK, SilenceWatch.judge(100 * MIN, 0, 400 * MIN, 405 * MIN, 10 * MIN));
        assertEquals(Verdict.OVERDUE, SilenceWatch.judge(100 * MIN, 0, 400 * MIN, 410 * MIN, 10 * MIN));
    }

    // ── the shared state in the ledger database ──────────────────────

    private static File tmp() throws Exception { File d = Files.createTempDirectory("opstool-sil").toFile(); d.deleteOnExit(); return d; }

    @Test public void stateIsEmptyUntilWatchingStartsAndStartingIsIdempotent() throws Exception {
        AtomicLong now = new AtomicLong(1000);
        TransferLedger l = new TransferLedger(tmp(), now::get);
        assertEquals(new TransferLedger.State(0, 0, 0), l.state("t"));
        l.startWatching("t");
        now.set(2000);
        l.startWatching("t");
        assertEquals("the first start time is kept", 1000, l.state("t").watchingSince());
    }

    @Test public void onlyOneCallerWinsTheAlertAndItRepeatsAfterTheInterval() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000);
        File dir = tmp();
        TransferLedger gui = new TransferLedger(dir, now::get), daemon = new TransferLedger(dir, now::get);
        gui.startWatching("t");
        assertTrue(gui.claimSilenceAlert("t", 60 * MIN));
        assertFalse("the other process must not alert too", daemon.claimSilenceAlert("t", 60 * MIN));
        now.addAndGet(59 * MIN);
        assertFalse(daemon.claimSilenceAlert("t", 60 * MIN));
        now.addAndGet(2 * MIN);
        assertTrue("repeats after the interval", daemon.claimSilenceAlert("t", 60 * MIN));
        assertTrue("zero means every time", gui.claimSilenceAlert("t", 0));
    }

    @Test public void filesArrivingClearTheAlertAndReportThatOneWasOutstanding() throws Exception {
        AtomicLong now = new AtomicLong(5000);
        TransferLedger l = new TransferLedger(tmp(), now::get);
        assertFalse("first ever arrival: nothing to resume from", l.markFilesTransferred("t"));
        l.claimSilenceAlert("t", MIN);
        now.set(9000);
        assertTrue("an alert was outstanding", l.markFilesTransferred("t"));
        assertEquals(9000, l.state("t").lastFilesAt());
        assertEquals(0, l.state("t").lastAlertAt());
        assertFalse("and only once", l.markFilesTransferred("t"));
    }

    @Test public void claimingWithoutAStartedClockDoesNothing() throws Exception {
        assertFalse(new TransferLedger(tmp()).claimSilenceAlert("unknown", 0));
    }
}
