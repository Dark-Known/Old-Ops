package service;

import model.ScheduledTask;

import java.util.*;

/**
 * Turns a task (or a before/after pair) into the operator-readable text used by the
 * "Task created / edited / deleted" activity events. Pure logic: no Swing, no storage.
 *
 * <p>Only user-configurable fields are captured; runtime bookkeeping (last run, created-at, last-known
 * remote file) is left out so a diff shows what the operator changed, not what the task did.
 * Credential ids are resolved to "user@host" through a map the caller supplies, so the caller reads
 * the credential table once per action rather than once per snapshot.
 */
public final class TaskChangeDescriber {

    private TaskChangeDescriber() { }

    /** Field key -> label used in the audit text, in the order they read in an event. */
    public static final Map<String, String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put("taskType", "Type");
        LABELS.put("transferDirection", "Direction");
        LABELS.put("transferMode", "Transfer mode");
        LABELS.put("sourceCredential", "Source credential");
        LABELS.put("targetCredential", "Target credential");
        LABELS.put("targetUsername", "Target credential");
        LABELS.put("sourcePath", "Source path");
        LABELS.put("targetPath", "Target path");
        LABELS.put("additionalTargetPaths", "Additional target paths");
        LABELS.put("fileSelectionRules", "File selection rules");
        LABELS.put("watchOptions", "What is reported");
        LABELS.put("backupSourcePath", "Backup source path");
        LABELS.put("backupDestinationPath", "Backup destination path");
        LABELS.put("backupRetentionDays", "Backup retention (days)");
        LABELS.put("backupSourceUsername", "Backup source credential");
        LABELS.put("backupDestinationUsername", "Backup destination credential");
        LABELS.put("imapFolder", "IMAP folder");
        LABELS.put("mailSearchCriteria", "Mail search criteria");
        LABELS.put("mailFetchMode", "Mail fetch mode");
        LABELS.put("mailMailboxAddress", "Mailbox address");
        LABELS.put("mailTenantId", "Mail tenant ID");
        LABELS.put("mailClientId", "Mail client ID");
        LABELS.put("mailFetchScope", "Mail fetch scope");
        LABELS.put("mailMaxResults", "Mail max results");
        LABELS.put("mailMarkAsRead", "Mark mail as read");
        LABELS.put("mailMoveToFolderEnabled", "Move mail to folder");
        LABELS.put("mailMoveToFolderName", "Move-to folder name");
        LABELS.put("mailOutputFolder", "Mail output folder");
        LABELS.put("scheduleType", "Schedule type");
        LABELS.put("scheduledAt", "Scheduled at");
        LABELS.put("intervalMinutes", "Interval (minutes)");
        LABELS.put("intervalSeconds", "Interval (seconds)");
        LABELS.put("cronExpression", "Cron expression");
        LABELS.put("watcherEnabled", "Watcher enabled");
        LABELS.put("inboundWatcherPollIntervalMinutes", "Watcher fallback poll interval (min)");
        LABELS.put("inboundWatcherMaxAgeMinutes", "Watcher max file age (min)");
        LABELS.put("retryCount", "Retry count");
    }

    public static Map<String, String> snapshot(ScheduledTask t, Map<String, String> credNameById) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("taskType", str(t.getTaskType()));
        f.put("transferDirection", str(t.getTransferDirection()));
        f.put("transferMode", str(t.getTransferMode()));
        f.put("sourceCredential", credNameById.getOrDefault(t.getSourceCredentialId(), t.getSourceCredentialId()));
        f.put("targetCredential", credNameById.getOrDefault(t.getTargetCredentialId(), t.getTargetCredentialId()));
        f.put("targetUsername", t.getTargetUsername());
        f.put("sourcePath", t.getSourcePath());
        f.put("targetPath", t.getTargetPath());
        f.put("additionalTargetPaths", t.getAdditionalTargetPaths());
        f.put("fileSelectionRules", t.getFileSelection().describe());
        f.put("watchOptions", t.getWatchOptions());
        f.put("backupSourcePath", t.getBackupSourcePath());
        f.put("backupDestinationPath", t.getBackupDestinationPath());
        f.put("backupRetentionDays", String.valueOf(t.getBackupRetentionDays()));
        f.put("backupSourceUsername", t.getBackupSourceUsername());
        f.put("backupDestinationUsername", t.getBackupDestinationUsername());
        f.put("imapFolder", t.getImapFolder());
        f.put("mailSearchCriteria", t.getMailSearchCriteria());
        f.put("mailFetchMode", str(t.getMailFetchMode()));
        f.put("mailMailboxAddress", t.getMailMailboxAddress());
        f.put("mailTenantId", t.getMailTenantId());
        f.put("mailClientId", t.getMailClientId());
        f.put("mailFetchScope", str(t.getMailFetchScope()));
        f.put("mailMaxResults", String.valueOf(t.getMailMaxResults()));
        f.put("mailMarkAsRead", yesNo(t.isMailMarkAsRead()));
        f.put("mailMoveToFolderEnabled", yesNo(t.isMailMoveToFolderEnabled()));
        f.put("mailMoveToFolderName", t.getMailMoveToFolderName());
        f.put("mailOutputFolder", t.getMailOutputFolder());
        f.put("scheduleType", str(t.getScheduleType()));
        f.put("scheduledAt", str(t.getScheduledAt()));
        f.put("intervalMinutes", String.valueOf(t.getIntervalMinutes()));
        f.put("intervalSeconds", String.valueOf(t.getIntervalSeconds()));
        f.put("cronExpression", t.getCronExpression());
        f.put("watcherEnabled", yesNo(t.isWatcherEnabled()));
        f.put("inboundWatcherPollIntervalMinutes", String.valueOf(t.getInboundWatcherPollIntervalMinutes()));
        f.put("inboundWatcherMaxAgeMinutes", String.valueOf(t.getInboundWatcherMaxAgeMinutes()));
        f.put("retryCount", String.valueOf(t.getRetryCount()));
        return f;
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static String yesNo(boolean b) { return b ? "Yes" : "No"; }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty() || "0".equals(s) || "null".equals(s);
    }

    /** Lists every non-blank field from a fresh task's snapshot — used for the
     *  "Task created" event, since there's no "before" state to diff against. */
    public static String describeNonBlank(Map<String, String> fields) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (isBlank(e.getValue())) continue;
            parts.add(LABELS.getOrDefault(e.getKey(), e.getKey()) + ": " + e.getValue());
        }
        return parts.isEmpty() ? "no fields set" : String.join("; ", parts);
    }

    /**
     * Builds the "what actually changed" description for a "Task edited"
     * event — same approach as the settings/credential diffs: only fields
     * whose value genuinely differs are listed, as "Label: old → new", so an
     * operator can tell exactly what happened without comparing configs by
     * hand. A field blank on both sides is skipped entirely (e.g. editing a
     * FILE_TRANSFER task leaves every mail_* field blank before and after —
     * listing "Mail search criteria: (not set) \u2192 (not set)" for every
     * inapplicable field would bury the real changes in noise).
     */
    public static String diff(Map<String, String> before, Map<String, String> after) {
        List<String> changes = new ArrayList<>();
        for (String key : after.keySet()) {
            String oldVal = before.get(key);
            String newVal = after.get(key);
            boolean oldBlank = isBlank(oldVal);
            boolean newBlank = isBlank(newVal);
            if (oldBlank && newBlank) continue;
            if (!oldBlank && !newBlank && oldVal.equals(newVal)) continue;
            String label = LABELS.getOrDefault(key, key);
            changes.add(label + ": " + (oldBlank ? "(not set)" : oldVal) + " \u2192 "
                    + (newBlank ? "(not set)" : newVal));
        }
        return changes.isEmpty() ? "no fields actually changed (re-saved as-is)"
                : String.join("; ", changes);
    }
}
