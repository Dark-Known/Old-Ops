package service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Keeps an alert from repeating: one per key (a task) until its repeat time has passed, or until
 * {@link #reset} says the problem went away (e.g. files arrived again). In memory only — a restart starts fresh.
 */
final class AlertThrottle {

    private final ConcurrentMap<String, Long> last = new ConcurrentHashMap<>();

    /** True (and remembered) when an alert for {@code key} may be raised at {@code nowMillis}; {@code everyMillis <= 0} = always. */
    boolean tryRaise(String key, long everyMillis, long nowMillis) {
        if (everyMillis <= 0) { last.put(key, nowMillis); return true; }
        boolean[] raised = {false};
        last.compute(key, (k, prev) -> {
            if (prev == null || nowMillis - prev >= everyMillis) { raised[0] = true; return nowMillis; }
            return prev;
        });
        return raised[0];
    }

    /** Minutes since the last alert for {@code key}, or -1 if none is remembered. */
    long minutesSince(String key, long nowMillis) {
        Long p = last.get(key);
        return p == null ? -1 : (nowMillis - p) / 60_000;
    }

    /** The problem is gone: the next one alerts at once. */
    void reset(String key) { last.remove(key); }
}
