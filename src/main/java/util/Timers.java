package util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Engine timers, read from the {@code <timers>} section of app-config.xml. One place to tune every delay, poll
 * interval and timeout; each has a built-in default used when the tag is missing or not a positive number.
 *
 * <p>Hot reload: callers read the value each time they use it, and {@link AppConfig} re-parses the file when it
 * changes, so an edit applies within about {@link #REFRESH_MS} without a restart. (A timer that is already
 * counting down finishes its current wait first.)
 */
public final class Timers {
    private Timers() {}

    static final long REFRESH_MS = 2000;

    private static final class Entry {
        final long value; final long at;
        Entry(long value, long at) { this.value = value; this.at = at; }
    }
    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    /** Milliseconds from app-config.xml tag {@code tag}, or {@code def} if absent or invalid. */
    public static long ms(String tag, long def) {
        long now = System.currentTimeMillis();
        Entry e = CACHE.get(tag);
        if (e == null || now - e.at >= REFRESH_MS) {
            long configured = -1;   // -1 = nothing usable in the file; the caller's default applies
            try {
                String raw = AppConfig.readValue(tag);
                if (raw != null) {
                    long n = Long.parseLong(raw.trim());
                    if (n > 0) configured = n;
                }
            } catch (Exception ignored) { }
            e = new Entry(configured, now);
            CACHE.put(tag, e);
        }
        return e.value > 0 ? e.value : def;
    }

    public static int msInt(String tag, int def) {
        return (int) Math.min(Integer.MAX_VALUE, ms(tag, def));
    }
}
