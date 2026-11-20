package service;

import org.junit.Test;

import static model.ScheduledTask.TaskStatus.*;
import static org.junit.Assert.*;

public class RetryPolicyTest {

    @Test
    public void aRealFailureWithRetriesLeftIsRetried() {
        assertTrue(RetryPolicy.shouldRetry(false, 2, false, RUNNING));
    }

    @Test
    public void aRunTheUserCancelledOrDisabledIsNeverRetried() {
        assertFalse("cancelled", RetryPolicy.shouldRetry(false, 2, true, RUNNING));
        assertFalse("disabled while running", RetryPolicy.shouldRetry(false, 2, false, DISABLED));
        assertFalse("deleted while running", RetryPolicy.shouldRetry(false, 2, false, null));
    }

    @Test
    public void successOrNoRetriesLeftIsNotRetried() {
        assertFalse(RetryPolicy.shouldRetry(true, 2, false, RUNNING));
        assertFalse(RetryPolicy.shouldRetry(false, 0, false, RUNNING));
    }

    @Test
    public void aTaskDisabledDuringTheRunStaysDisabled() {
        assertEquals(DISABLED, RetryPolicy.statusAfterRun(DISABLED, FAILED));
        assertEquals(DISABLED, RetryPolicy.statusAfterRun(DISABLED, SUCCESS));
        assertEquals(DISABLED, RetryPolicy.statusAfterRun(DISABLED, PENDING));
    }

    @Test
    public void otherTasksGetTheStatusTheRunComputed() {
        assertEquals(FAILED, RetryPolicy.statusAfterRun(RUNNING, FAILED));
        assertEquals(SUCCESS, RetryPolicy.statusAfterRun(RUNNING, SUCCESS));
        assertEquals(PENDING, RetryPolicy.statusAfterRun(RUNNING, PENDING));
    }
}
