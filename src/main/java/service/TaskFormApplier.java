package service;

import model.ScheduledTask;
import model.ScheduledTask.ScheduleType;
import model.ScheduledTask.TaskStatus;
import model.ScheduledTask.TaskType;
import model.ScheduledTask.TransferDirection;
import model.ScheduledTask.TransferMode;
import util.MailFetchMode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Copies a {@link TaskForm} onto a {@link ScheduledTask}: the rules for which fields belong to which task type,
 * what a Mail/Backup/Watch task leaves blank, when the watcher baseline resets, and how the schedule fields map
 * onto the cron / interval columns. No Swing, no storage.
 */
public final class TaskFormApplier {

    private static final DateTimeFormatter ONCE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private TaskFormApplier() { }

    /** The schedule fields could not be read (bad date, non-numeric interval). Only this is shown as "Invalid schedule values". */
    public static final class InvalidScheduleException extends IllegalArgumentException {
        InvalidScheduleException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * @param existing the task being edited, or {@code null} for a new one (which gets an id and starts PENDING)
     * @return the task, with every form value applied
     * @throws InvalidScheduleException if the schedule fields do not parse (message is shown to the operator)
     */
    public static ScheduledTask apply(ScheduledTask existing, TaskForm f) {
        ScheduledTask t = existing != null ? existing : new ScheduledTask();
        if (t.getId() == null) t.setId(UUID.randomUUID().toString());
        t.setName(f.name);
        t.setTaskType(TaskType.valueOf(f.taskType));
        if (existing == null) t.setStatus(TaskStatus.PENDING);

        boolean isTransfer = "FILE_TRANSFER".equals(f.taskType);
        boolean isMail     = "OUTLOOK_MAIL".equals(f.taskType);
        boolean isBackup   = "BACKUP".equals(f.taskType);
        boolean isWatch    = "FOLDER_WATCH".equals(f.taskType);

        // Mail tasks use OAuth2/Graph, so targetUsername is left blank for them
        // (and for Backup / Watch, which store their own source/destination usernames).
        t.setTargetUsername(!isMail && !isBackup && !isWatch ? f.credentialName : "");   // the credential's name (field name is historical)
        t.setSourceCredentialId(trim(f.sourceUser) + "@" + trim(f.sourceHost));

        if (isTransfer) {
            t.setTransferDirection(TransferDirection.valueOf(f.transferDirection));
            t.setTransferMode(TransferMode.valueOf(f.transferMode));
            t.setSourcePath(trim(f.sourcePath));
            t.setTargetPath(trim(f.targetFolder));
            t.setFileSelectionRules(f.fileSelectionRules);
            t.setAdditionalTargetPaths(joinNonBlank(f.additionalDestinations));
            t.setWatcherEnabled(f.watcherEnabled);
            // Reset BOTH parts of the composite fingerprint when the operator deliberately changed
            // path / direction / mode, or clicked Reset Baseline.
            if (f.resetWatcherBaseline) {
                t.setLastKnownRemoteFileEpoch(0L);
                t.setLastKnownRemoteFileSize(-1L);
            }
        } else if (isMail) {
            t.setImapFolder(trim(f.imapFolder));
            t.setMailSearchCriteria(f.mailSearchCriteria);
            t.setMailFetchMode(MailFetchMode.valueOf(f.mailFetchMode));
            t.setMailFetchScope(ScheduledTask.MailFetchScope.valueOf(f.mailFetchScope));
            t.setMailMaxResults(Integer.parseInt(trim(f.mailMaxResults)));
            t.setMailMailboxAddress(trim(f.mailbox));
            t.setMailTenantId(trim(f.tenantId));
            t.setMailClientId(trim(f.clientId));
            t.setWatcherEnabled(f.mailWatcherEnabled);
            t.setMailMarkAsRead(f.mailMarkAsRead);
            t.setMailOutputFolder(trim(f.mailOutputFolder));
            if (f.resetMailBaseline) t.setMailLastKnownEpoch(0L);
        } else if (isWatch) {
            t.setBackupSourcePath(trim(f.watchPath));
            t.setBackupSourceUsername(f.watchRemote ? trim(f.watchCredential) : "");
            t.setWatchOptions(new model.WatchOptions(f.watchNew, f.watchChanged).toText());
            t.setFileSelectionRules(f.fileSelectionRules);
            t.setWatcherEnabled(false);
        } else if (isBackup) {
            t.setBackupSourcePath(trim(f.backupSourcePath));
            t.setBackupDestinationPath(trim(f.backupDestinationPath));
            t.setBackupRetentionDays(f.backupRetentionDays);
            t.setBackupSourceUsername(f.backupSourceRemote ? trim(f.backupSourceUsername) : "");
            t.setBackupDestinationUsername(f.backupDestinationRemote ? trim(f.backupDestinationUsername) : "");
        }

        t.setRetryCount(f.retryCount);
        t.setTransferBatchMaxMb(f.transferBatchMaxMb);
        t.setTransferConcurrency(f.transferConcurrency);
        applySchedule(t, f);
        return t;
    }

    private static void applySchedule(ScheduledTask t, TaskForm f) {
        String stype = f.scheduleType;
        t.setScheduleType(ScheduleType.valueOf(stype));
        try {
            switch (stype) {
                case "ONCE":
                    t.setScheduledAt(LocalDateTime.parse(trim(f.scheduledAt), ONCE_FORMAT));
                    break;
                case "DAILY":
                    t.setCronExpression(trim(f.time));
                    break;
                case "WEEKLY":
                    t.setCronExpression(f.dayOfWeek + " " + trim(f.time));
                    t.setIntervalMinutes(0);
                    t.setIntervalSeconds(0);
                    break;
                case "INTERVAL_MINUTES":
                    t.setIntervalMinutes(Integer.parseInt(trim(f.interval)));
                    t.setIntervalSeconds(0);
                    break;
                case "INTERVAL_SECONDS":
                    t.setIntervalSeconds(Integer.parseInt(trim(f.interval)));
                    t.setIntervalMinutes(0);
                    break;
                default:
                    t.setIntervalMinutes(0);
                    t.setIntervalSeconds(0);
                    break;
            }
        } catch (Exception ex) {
            throw new InvalidScheduleException(ex.getMessage(), ex);
        }
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    private static String joinNonBlank(java.util.List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            String v = trim(p);
            if (v.isEmpty()) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(v);
        }
        return sb.toString();
    }
}
