package service;

import model.ScheduledTask;
import org.junit.Test;

import static org.junit.Assert.*;

public class TransferTuningTest {

    private static final long GLOBAL_BYTES = 25L * 1024 * 1024;

    @Test
    public void aTaskWithoutItsOwnValuesUsesSettings() {
        ScheduledTask t = new ScheduledTask();
        TransferTuning x = TransferTuning.of(t, GLOBAL_BYTES, 2);
        assertEquals(GLOBAL_BYTES, x.maxBytes);
        assertEquals(2, x.concurrency);
        TransferTuning none = TransferTuning.of(null, GLOBAL_BYTES, 1);
        assertEquals(GLOBAL_BYTES, none.maxBytes);
        assertEquals(1, none.concurrency);
    }

    @Test
    public void aTaskCanSetItsOwnBatchSizeAndParallelSessions() {
        ScheduledTask t = new ScheduledTask();
        t.setTransferBatchMaxMb(100);
        t.setTransferConcurrency(4);
        TransferTuning x = TransferTuning.of(t, GLOBAL_BYTES, 1);
        assertEquals(100L * 1024 * 1024, x.maxBytes);
        assertEquals(4, x.concurrency);
    }

    @Test
    public void eachValueFallsBackOnItsOwn() {
        ScheduledTask t = new ScheduledTask();
        t.setTransferConcurrency(3);   // batch size left at 0
        TransferTuning x = TransferTuning.of(t, GLOBAL_BYTES, 1);
        assertEquals(GLOBAL_BYTES, x.maxBytes);
        assertEquals(3, x.concurrency);
    }

    @Test
    public void valuesAreCapped() {
        ScheduledTask t = new ScheduledTask();
        t.setTransferConcurrency(500);
        t.setTransferBatchMaxMb(Integer.MAX_VALUE);
        TransferTuning x = TransferTuning.of(t, GLOBAL_BYTES, 1);
        assertEquals(TransferTuning.MAX_CONCURRENCY, x.concurrency);
        assertEquals(TransferTuning.MAX_BATCH_MB * 1024L * 1024L, x.maxBytes);
        assertEquals("a bad Settings value cannot give zero sessions", 1, TransferTuning.of(null, GLOBAL_BYTES, 0).concurrency);
    }

    @Test
    public void negativeValuesMeanUseSettings() {
        ScheduledTask t = new ScheduledTask();
        t.setTransferConcurrency(-2);
        t.setTransferBatchMaxMb(-7);
        assertEquals(0, t.getTransferConcurrency());
        assertEquals(0, t.getTransferBatchMaxMb());
        assertEquals(2, TransferTuning.of(t, GLOBAL_BYTES, 2).concurrency);
    }
}
