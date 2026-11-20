package service;

import model.ScheduledTask.TaskStatus;

/**
 * The decisions that keep a task the user stopped from being run again by itself, split out of
 * {@link TaskSchedulerService} so they can be tested alone.
 */
final class RetryPolicy {

    private RetryPolicy() {
    }

    /**
     * Whether a failed run should be retried automatically. A run that ended because the user cancelled or disabled it
     * (the worker was interrupted) is not a failure to retry, and neither is a task that is disabled or gone from storage.
     *
     * @param storedStatus the task's status as saved right now, null if the task no longer exists
     */
    static boolean shouldRetry(boolean success, int retriesLeft, boolean cancelled, TaskStatus storedStatus) {
        return !success && retriesLeft > 0 && !cancelled && storedStatus != null && storedStatus != TaskStatus.DISABLED;
    }

    /**
     * The status to save when a run finishes: a task the user disabled while it was running stays disabled.
     */
    static TaskStatus statusAfterRun(TaskStatus storedStatus, TaskStatus computed) {
        return storedStatus == TaskStatus.DISABLED ? TaskStatus.DISABLED : computed;
    }
}
