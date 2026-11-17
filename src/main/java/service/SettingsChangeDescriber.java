package service;

import util.AppSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Describes what a settings save actually changed, for the activity feed. Pure logic, no Swing. */
public final class SettingsChangeDescriber {

    private SettingsChangeDescriber() { }

    /** Setting key -> label shown in the feed ("Log level" instead of the raw key constant). */
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry(service.ActivityVisibility.KEY_SLOW_JOB_MS, "Show background work slower than (ms)"),
            Map.entry(service.ActivityVisibility.KEY_UI_STALL_MS, "Report UI stall longer than (ms)"),
            Map.entry(AppSettings.KEY_DEFAULT_STATION_ADDR, "Default station address"),
            Map.entry(AppSettings.KEY_ATTACHMENT_DOWNLOAD_DIR, "Attachment download directory"),
            Map.entry(AppSettings.KEY_LOG_LEVEL, "Log level"),
            Map.entry(AppSettings.KEY_JVM_MIN_HEAP, "JVM min heap"),
            Map.entry(AppSettings.KEY_JVM_MAX_HEAP, "JVM max heap"),
            Map.entry(AppSettings.KEY_TRANSFER_BATCH_TARGET_SECONDS, "Batch target duration (s)"),
            Map.entry(AppSettings.KEY_TRANSFER_ASSUMED_THROUGHPUT_MBPS, "Assumed throughput (Mbps)"),
            Map.entry(AppSettings.KEY_TRANSFER_BATCH_INTERVAL_SECONDS, "Batch interval (s)"),
            Map.entry(AppSettings.KEY_TRANSFER_BATCH_MAX_BYTES, "Batch max bytes override"),
            Map.entry(AppSettings.KEY_TRANSFER_BATCH_CONCURRENCY, "Batch concurrency"),
            Map.entry(AppSettings.KEY_WATCHER_FILES_PER_WORKER_THREAD, "Watcher files per worker thread"),
            Map.entry(AppSettings.KEY_STALE_RUNNING_THRESHOLD_MINUTES, "Stale running threshold (min)"),
            Map.entry(AppSettings.KEY_MAX_CONCURRENT_TASK_THREADS, "Max concurrent task threads"),
            Map.entry(AppSettings.KEY_POLL_INTERVAL_SECONDS, "Poll interval (s)"),
            Map.entry(service.RetentionSettings.KEY_BG_DAYS, "Background log: keep days"),
            Map.entry(service.RetentionSettings.KEY_BG_MB, "Background log: max MB"),
            Map.entry(service.RetentionSettings.KEY_RUN_DAYS, "Run history: keep days"),
            Map.entry(service.RetentionSettings.KEY_RUN_MB, "Run history: max MB"),
            Map.entry(service.RetentionSettings.KEY_SCHEDULED, "Automatic purge"),
            Map.entry(service.RetentionSettings.KEY_INTERVAL_H, "Purge every (hours)"));

    /**
     * Builds a field-level "what actually changed" description for the event
     * feed — the "need max information so it's easy for the end user" ask.
     * A generic "Application settings updated" told an operator nothing
     * about WHAT changed; this lists every key whose value actually differs,
     * as "Label: old → new", so the incident is legible from the feed alone
     * without having to go compare configs by hand.
     */
    public static String describe(Map<String, String> oldValues, Map<String, String> newValues) {
        List<String> changes = new ArrayList<>();
        for (String key : newValues.keySet()) {
            String oldVal = oldValues.get(key);
            String newVal = newValues.get(key);
            boolean oldBlank = oldVal == null || oldVal.isEmpty();
            boolean newBlank = newVal == null || newVal.isEmpty();
            if (oldBlank && newBlank) continue;
            if (!oldBlank && !newBlank && oldVal.equals(newVal)) continue;
            String label = LABELS.getOrDefault(key, key);
            changes.add(label + ": " + (oldBlank ? "(not set)" : oldVal) + " \u2192 "
                    + (newBlank ? "(not set)" : newVal));
        }
        if (changes.isEmpty()) {
            return "Application settings re-saved with no changes.";
        }
        return "Application settings updated — " + String.join("; ", changes) + ".";
    }
}
