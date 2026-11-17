package service;

/**
 * What the task dialog holds, as plain values: the raw text of each field, untrimmed, exactly as typed.
 * {@link TaskFormApplier} turns it into a {@code ScheduledTask}. Keeps the form-to-task rules out of the Swing
 * dialog so they can be tested without a window.
 */
public final class TaskForm {
    // identity and type
    public String name;
    public String taskType;            // FILE_TRANSFER | OUTLOOK_MAIL | BACKUP | FOLDER_WATCH
    public String credentialName;      // the target credential's name (transfer tasks)
    public String sourceUser, sourceHost;

    // file transfer
    public String transferDirection, transferMode;
    public String sourcePath, targetFolder;
    public String fileSelectionRules;
    public java.util.List<String> additionalDestinations = new java.util.ArrayList<>();
    public boolean watcherEnabled;
    public boolean resetWatcherBaseline;

    // mail
    public String imapFolder, mailSearchCriteria, mailFetchMode, mailFetchScope, mailMaxResults;
    public String mailbox, tenantId, clientId, mailOutputFolder;
    public boolean mailWatcherEnabled, mailMarkAsRead, resetMailBaseline;

    // folder watch
    public String watchPath, watchCredential;
    public boolean watchRemote, watchNew, watchChanged;

    // backup
    public String backupSourcePath, backupDestinationPath, backupSourceUsername, backupDestinationUsername;
    public boolean backupSourceRemote, backupDestinationRemote;
    public int backupRetentionDays;

    // shared
    public int retryCount;

    // schedule
    public String scheduleType;        // ONCE | DAILY | WEEKLY | INTERVAL_MINUTES | INTERVAL_SECONDS | other
    public String scheduledAt;         // "yyyy-MM-dd HH:mm"
    public String time;                // "HH:mm"
    public String dayOfWeek;
    public String interval;
}
