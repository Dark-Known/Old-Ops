package model;

/**
 * Correlates everything one task execution records. A run happens start-to-finish on one worker
 * thread, and everything it records (Started, Detected, retry notes, the final outcome) should carry
 * the same run key and trigger so the Event Monitor can fold them into one expandable run. Threading
 * these through every recording call would change signatures across the scheduler; a thread-local set
 * once at the start of a run and cleared at the end gets the same result with no call-site changes.
 *
 * <p>Pure in-memory state: nothing here depends on how or where history is stored.
 */
public final class RunTrace {

    private RunTrace() {}

    private static final ThreadLocal<String[]> RUN_CONTEXT = new ThreadLocal<>();

    /** Short, unique-enough id for one task execution. */
    public static String newRunKey() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    /** Marks the current thread as executing the run {@code runKey}, caused by {@code trigger} (SCHEDULE/WATCHER/MANUAL/RETRY). */
    public static void beginRunContext(String runKey, String trigger) {
        RUN_CONTEXT.set(new String[]{runKey, trigger});
    }

    /** Clears the current thread's run context. Always call from a finally block. */
    public static void endRunContext() {
        RUN_CONTEXT.remove();
    }

    /** Run key of the run executing on this thread, or null if none. */
    public static String currentRunKey() {
        String[] c = RUN_CONTEXT.get();
        return c != null ? c[0] : null;
    }

    /** Trigger of the run executing on this thread, or null if none. */
    public static String currentTrigger() {
        String[] c = RUN_CONTEXT.get();
        return c != null ? c[1] : null;
    }
}
