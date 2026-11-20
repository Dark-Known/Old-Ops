package service;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.After;
import org.junit.Test;

public class ThreadBudgetRunAllTest {
    @After public void clean() { ThreadBudget.reset(); }

    @Test public void firstTaskRunsOnTheCallerAndTheRestOnTheSharedPoolWithSlotsReturned() throws Exception {
        ThreadBudget.reset();
        Thread caller = Thread.currentThread();
        int extra = ThreadBudget.acquireUpTo(2, 10);
        assertEquals(2, extra);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) tasks.add(() -> Thread.currentThread() == caller ? "caller" : Thread.currentThread().getName());
        List<String> out = ThreadBudget.runAll(tasks);
        assertEquals("caller", out.get(0));
        assertTrue(out.get(1).startsWith("worker-"));
        assertEquals(0, ThreadBudget.extrasInUse());
    }

    @Test public void aFailureIsRethrownAfterAllTasksEnd() throws Exception {
        ThreadBudget.reset();
        ThreadBudget.acquireUpTo(1, 10);
        List<Callable<Integer>> tasks = new ArrayList<>();
        tasks.add(() -> 1);
        tasks.add(() -> { throw new IllegalStateException("boom"); });
        try { ThreadBudget.runAll(tasks); fail(); } catch (IllegalStateException e) { assertEquals("boom", e.getMessage()); }
        assertEquals(0, ThreadBudget.extrasInUse());
    }
}
