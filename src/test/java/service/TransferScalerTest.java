package service;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TransferScalerTest {
    private ExecutorService pool;
    private ScheduledExecutorService timer;

    @Before public void up() { pool = Executors.newCachedThreadPool(); timer = Executors.newScheduledThreadPool(1); }
    @After public void down() { pool.shutdownNow(); timer.shutdownNow(); }

    private static List<Integer> units(int n) { List<Integer> l = new ArrayList<>(); for (int i = 0; i < n; i++) l.add(i); return l; }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    @Test public void quickWorkStaysOnOneSessionOnTheCallingThread() throws Exception {
        Thread caller = Thread.currentThread();
        AtomicInteger offThread = new AtomicInteger();
        TransferScaler.Result r = TransferScaler.run(units(3), 8, 300, pool, timer, () -> true, () -> { },
                u -> { if (Thread.currentThread() != caller) offThread.incrementAndGet(); return true; }, null);
        assertTrue(r.allOk);
        assertEquals(1, r.peakWorkers);
        assertEquals(0, offThread.get());
    }

    @Test public void slowWorkScalesUpToTheLimitButNotBeyondUnits() throws Exception {
        TransferScaler.Result r = TransferScaler.run(units(3), 8, 50, pool, timer, () -> true, () -> { }, u -> { sleep(600); return true; }, null);
        assertTrue(r.allOk);
        assertEquals(3, r.peakWorkers);
        r = TransferScaler.run(units(10), 4, 50, pool, timer, () -> true, () -> { }, u -> { sleep(300); return true; }, null);
        assertEquals(4, r.peakWorkers);
    }

    @Test public void aFailedUnitIsReportedAndTheRestStillRun() throws Exception {
        AtomicInteger done = new AtomicInteger();
        TransferScaler.Result r = TransferScaler.run(units(6), 2, 10, pool, timer, () -> true, () -> { },
                u -> { done.incrementAndGet(); return u != 2; }, null);
        assertFalse(r.allOk);
        assertEquals(6, done.get());
    }

    @Test public void noFreeSlotMeansNoExtraSessionAndNothingIsGivenBack() throws Exception {
        AtomicInteger returned = new AtomicInteger();
        TransferScaler.Result r = TransferScaler.run(units(4), 4, 20, pool, timer, () -> false, returned::incrementAndGet,
                u -> { sleep(150); return true; }, null);
        assertTrue(r.allOk);
        assertEquals(1, r.peakWorkers);
        assertEquals(0, returned.get());
    }

    @Test public void everyGrantedSlotIsGivenBack() throws Exception {
        AtomicInteger held = new AtomicInteger();
        TransferScaler.Result r = TransferScaler.run(units(8), 3, 20, pool, timer, () -> { held.incrementAndGet(); return true; },
                held::decrementAndGet, u -> { sleep(200); return true; }, null);
        assertEquals(3, r.peakWorkers);
        assertEquals(0, held.get());
    }
}
