package service;

import static org.junit.Assert.*;
import org.junit.After;
import org.junit.Test;

public class ThreadBudgetTest {
    @After public void clean() { ThreadBudget.reset(); }

    @Test public void tasksAndExtraSessionsShareOneLimit() {
        ThreadBudget.reset();
        for (int i = 0; i < 6; i++) ThreadBudget.taskStarted();
        assertEquals(2, ThreadBudget.acquireUpTo(5, 8));      // 8 - 6 tasks = 2 free
        assertEquals(0, ThreadBudget.acquireUpTo(1, 8));      // full
        ThreadBudget.release(1);
        assertEquals(1, ThreadBudget.acquireUpTo(3, 8));
    }

    @Test public void aFinishedTaskFreesItsSlotForSessions() {
        ThreadBudget.reset();
        for (int i = 0; i < 4; i++) ThreadBudget.taskStarted();
        assertEquals(0, ThreadBudget.acquireUpTo(2, 4));
        ThreadBudget.taskFinished();
        assertEquals(1, ThreadBudget.acquireUpTo(2, 4));
    }
}
