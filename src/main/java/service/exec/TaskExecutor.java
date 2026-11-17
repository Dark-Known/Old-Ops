package service.exec;

import model.ScheduledTask;

/**
 * Runs one kind of task. The scheduler decides <em>when</em> a task runs and
 * records the result; an executor owns <em>how</em> that kind of task runs.
 *
 * <p>To add a task type: add it to {@link ScheduledTask.TaskType}, write an
 * executor for it and register it on the {@link TaskExecutorRegistry}. The
 * scheduler itself does not change.
 */
public interface TaskExecutor {

    /** The task type this executor runs. */
    ScheduledTask.TaskType type();

    /**
     * Runs the task once.
     *
     * <p>Expected outcomes, including "nothing to do", come back as a {@link TaskOutcome}.
     * An unexpected exception may be thrown; the scheduler logs it and records a failed run.
     */
    TaskOutcome execute(RunContext ctx) throws Exception;
}
