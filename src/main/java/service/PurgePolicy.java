package service;

import util.AppSettings;

/**
 * How much history a database may keep: at most {@code keepDays} days and at most {@code maxMb}
 * megabytes. Zero means "no limit" for that metric. Both limits apply; whichever is hit first
 * removes the oldest rows.
 */
public record PurgePolicy(int keepDays, long maxMb) {

    public static final String KEY_BG_DAYS    = "retentionBackgroundLogDays";
    public static final String KEY_BG_MB      = "retentionBackgroundLogMaxMB";
    public static final String KEY_RUN_DAYS   = "retentionRunHistoryDays";
    public static final String KEY_RUN_MB     = "retentionRunHistoryMaxMB";
    public static final String KEY_SCHEDULED  = "purgeScheduleEnabled";
    public static final String KEY_INTERVAL_H = "purgeIntervalHours";

    public PurgePolicy {
        keepDays = Math.max(0, keepDays);
        maxMb = Math.max(0, maxMb);
    }

    public long maxBytes() { return maxMb * 1024L * 1024L; }

    public boolean unlimited() { return keepDays == 0 && maxMb == 0; }

    public static PurgePolicy backgroundLog() {
        return new PurgePolicy(intSetting(KEY_BG_DAYS, 30), intSetting(KEY_BG_MB, 50));
    }

    public static PurgePolicy runHistory() {
        return new PurgePolicy(intSetting(KEY_RUN_DAYS, 90), intSetting(KEY_RUN_MB, 500));
    }

    /** Whether the scheduler should purge on its own. */
    public static boolean scheduledEnabled() {
        String v = AppSettings.get(KEY_SCHEDULED);
        return v == null || v.isBlank() || Boolean.parseBoolean(v.trim());
    }

    public static int intervalHours() { return Math.max(1, intSetting(KEY_INTERVAL_H, 6)); }

    private static int intSetting(String key, int fallback) {
        try {
            String v = AppSettings.get(key);
            return v == null || v.isBlank() ? fallback : Math.max(0, Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override public String toString() {
        return (keepDays == 0 ? "any age" : keepDays + " days") + " / " + (maxMb == 0 ? "no size cap" : maxMb + " MB");
    }
}
