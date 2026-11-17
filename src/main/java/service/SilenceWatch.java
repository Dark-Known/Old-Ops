package service;

/**
 * Decides whether a task that should be receiving files regularly has gone quiet for too long.
 * Pure arithmetic, so the rules can be tested without clocks or databases.
 */
final class SilenceWatch {

    private SilenceWatch() { }

    enum Verdict {
        /** Nothing recorded yet: start the clock now. */
        START,
        /** Files arrived recently enough (or the app has not been watching long enough to say). */
        OK,
        /** No file for longer than the threshold. */
        OVERDUE
    }

    /**
     * @param lastFilesAt    when files last arrived (0 = never)
     * @param watchingSince  when the clock was started for this task (0 = not started)
     * @param schedulerStart when this program's scheduler started: time the app was not running does not count
     * @param thresholdMs    the "no file for" limit
     */
    static Verdict judge(long lastFilesAt, long watchingSince, long schedulerStart, long now, long thresholdMs) {
        long ref = lastFilesAt > 0 ? lastFilesAt : watchingSince;
        if (ref <= 0) return Verdict.START;
        ref = Math.max(ref, schedulerStart);
        return now - ref >= thresholdMs ? Verdict.OVERDUE : Verdict.OK;
    }
}
