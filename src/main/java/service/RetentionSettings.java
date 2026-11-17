package service;

import persistence.PurgePolicy;
import util.AppSettings;

/** Reads the retention (purge) settings the user configured and turns them into {@link PurgePolicy} values. */
public final class RetentionSettings {

    private RetentionSettings() {}

    public static final String KEY_BG_DAYS    = "retentionBackgroundLogDays";
    public static final String KEY_BG_MB      = "retentionBackgroundLogMaxMB";
    public static final String KEY_RUN_DAYS   = "retentionRunHistoryDays";
    public static final String KEY_RUN_MB     = "retentionRunHistoryMaxMB";
    public static final String KEY_SCHEDULED  = "purgeScheduleEnabled";
    public static final String KEY_INTERVAL_H = "purgeIntervalHours";

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
}
