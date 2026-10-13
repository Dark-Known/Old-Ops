package service;

import util.AppSettings;

/**
 * When does the app's own background activity become visible in the Event Monitor?
 * Both thresholds are user settings, read live (no restart):
 * <ul>
 *   <li>{@code bgSlowJobMs} — a routine background job (a screen load, a feed refresh…) that runs at
 *       least this long is recorded as a "slow background job". User-triggered actions are always
 *       recorded regardless.</li>
 *   <li>{@code uiStallMs} — if the window itself is unresponsive (the UI thread blocked) for at least
 *       this long, a "UI stall" event with the blocking stack appears.</li>
 * </ul>
 */
public final class ActivityVisibility {
    private ActivityVisibility() {}

    public static final String KEY_SLOW_JOB_MS = "bgSlowJobMs";
    public static final String KEY_UI_STALL_MS = "uiStallMs";
    public static final int DEFAULT_SLOW_JOB_MS = 2000;
    public static final int DEFAULT_UI_STALL_MS = 150;
    public static final int MIN_MS = 50, MAX_MS = 600_000;

    public static long slowJobMs() { return read(KEY_SLOW_JOB_MS, DEFAULT_SLOW_JOB_MS); }
    public static long uiStallMs() { return read(KEY_UI_STALL_MS, DEFAULT_UI_STALL_MS); }

    private static long read(String key, int fallback) {
        try {
            String v = AppSettings.get(key);
            if (v == null || v.isBlank()) return fallback;
            return Math.max(MIN_MS, Math.min(MAX_MS, Long.parseLong(v.trim())));
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
