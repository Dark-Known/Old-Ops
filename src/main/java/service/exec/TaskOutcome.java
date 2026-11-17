package service.exec;

/**
 * What one run of a task came to, in the terms the scheduler needs to record it.
 *
 * <p>Executors return this instead of the scheduler reading loose
 * {@code success / skipped / skipReason / failureReason} locals out of every
 * branch of a switch.
 *
 * @param success       false means the run failed (and may be retried)
 * @param skipped       true when the run was fine but had nothing to do; implies {@code success}
 * @param skipReason    why the run was skipped, or null
 * @param failureReason a short reason for a failure, or null to let the scheduler derive one from the log
 */
public record TaskOutcome(boolean success, boolean skipped, String skipReason, String failureReason) {

    /** The run did its work. */
    public static TaskOutcome ok() {
        return new TaskOutcome(true, false, null, null);
    }

    /** The run failed; {@code reason} may be null if the log already says why. */
    public static TaskOutcome failure(String reason) {
        return new TaskOutcome(false, false, null, reason);
    }

    /** The run was fine but had nothing to do; shown as skipped rather than as a success. */
    public static TaskOutcome skip(String reason) {
        return new TaskOutcome(true, true, reason, null);
    }
}
