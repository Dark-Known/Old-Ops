package persistence;

/**
 * A cheap "did anything change?" signal over the stores the GUI and the Daemon (two separate processes)
 * share. One signal per topic; consumers compare generations or subscribe, and keep a slow safety-net
 * refresh of their own, so a missed signal can only ever delay an update, never lose one.
 *
 * <p>Listeners run on the signal's own thread and must be cheap (flip a flag, hand off to the UI thread).
 */
public interface ChangeSignals {

    /** What can change: settings/tasks/commands, the tasks alone, run history, the background log. */
    enum Topic { APP, TASKS, RUNS, BGLOG }

    /** Monotonic counter for a topic; compare against a remembered value to detect change. */
    long generation(Topic topic);

    /** Epoch-millis of the last completed check (0 = never). Lets callers judge whether the signal is alive. */
    long lastPollMillis();

    /** One-line summary for logs. */
    String describe();

    /** Total signals fired for a topic since start (for tests and diagnostics). */
    long signalCount(Topic topic);

    /** Runs {@code onChange} (on the signal's thread) whenever the topic changes. Returns an unsubscribe action. */
    Runnable subscribe(Topic topic, Runnable onChange);

    /**
     * Blocks until {@code topic}'s generation differs from {@code seen}, or {@code timeoutMs} elapses.
     *
     * @return the generation at wake-up
     */
    long awaitChange(Topic topic, long seen, long timeoutMs) throws InterruptedException;
}
