package model;

import java.time.LocalDateTime;

import util.MailFetchMode;
import util.*;

public class ScheduledTask {
    //TODO: consider splitting enums into separate files if they grow too large or need additional methods/fields in the future
    public enum TaskType   { FILE_TRANSFER, OUTLOOK_MAIL, BACKUP }
    public enum TaskStatus  { PENDING, RUNNING, RETRYING, SUCCESS, FAILED, DISABLED }
    public enum ScheduleType { RUN_NOW, ONCE, DAILY, WEEKLY, INTERVAL_MINUTES, INTERVAL_SECONDS }
    public enum TransferDirection { OUTBOUND, INBOUND }
    public enum TransferMode { LATEST_ONLY, ENTIRE_FOLDER, SPECIFIC_FILE }
    public enum MailFetchScope { LATEST_ONLY, ALL_MATCHING }
    


    
    private String id;
    private String name;
    private TaskType taskType;
    private TaskStatus status;
    private TransferDirection transferDirection;
    private TransferMode transferMode;

    // Credentials
    private String sourceCredentialId;   // optional – identifies source server creds
    private String targetCredentialId;   // kept for backward-compat; may be null in new flow
    private String targetUsername;       // username entered by ops; used to look up creds_<u>.xml

    // File transfer fields
    private String sourcePath;
    private String targetPath;

    // Backup fields ("D" = today; retention keeps D, D-1 ... D-(retentionDays-1) untouched
    // in the source, and everything older than that is eligible for backup).
    // Every eligible file is archived in a single run (no more day-bucket
    // batching) — large runs are instead worked through in size-based
    // batches internally, the same batching used for file transfers (see
    // AppSettings.getTransferBatchMaxBytes()/getTransferBatchIntervalSeconds()).
    private String backupSourcePath;
    private String backupDestinationPath;
    private int backupRetentionDays;   // how many most-recent days to KEEP in place (D..D-(n-1))

    // Remote backup support: when set (non-empty), that side of the backup
    // is a REMOTE path reached over SFTP using the credential stored in
    // creds_<username>.xml for that username — same mechanism File Transfer
    // tasks use for targetUsername. When blank, that side is a plain local
    // filesystem path. At most one side may be remote at a time (backing up
    // between two different remote servers isn't supported).
    private String backupSourceUsername;
    private String backupDestinationUsername;

    // Outlook mail fields — read via Microsoft Graph (see GraphMailService).
    // NOTE: Graph is a cloud REST API regardless of where the task runs, so the
    // old "REMOTE vs LOCAL Outlook" distinction from the IMAP implementation
    // no longer applies; there is only ever one mailbox + one auth path.
    private String imapFolder;            // mail folder to read (kept name for storage/back-compat)
    private String mailSearchCriteria;    // IMAP-style criteria string, best-effort mapped to Graph $filter
    private MailFetchMode mailFetchMode;
    private String mailMailboxAddress;    // UPN / email address of the mailbox to read
    private String mailTenantId;          // Azure AD tenant ID, or "common"
    private String mailClientId;          // Application (client) ID of the self-registered Azure AD app
    private MailFetchScope mailFetchScope; // LATEST_ONLY (newest matching message) or ALL_MATCHING (paginated)
    private int mailMaxResults;            // safety cap for ALL_MATCHING / watcher mode

    // Mail watcher — mirrors the file-transfer watcher's epoch baseline, but keyed
    // on message receivedDateTime instead of file lastModified. Reuses the shared
    // watcherEnabled flag above (a task is only ever one TaskType, so no collision).
    private long mailLastKnownEpoch;       // epoch ms of newest processed message at last successful run
    private boolean mailMarkAsRead;        // mark fetched messages as read via Graph after processing
    private boolean mailMoveToFolderEnabled; // move fetched messages to another folder after processing
    private String mailMoveToFolderName;     // destination folder display name (or well-known name)
    private String mailOutputFolder;         // local directory where each fetched message is written as a .RCV file

    // Schedule fields
    private ScheduleType scheduleType;
    private LocalDateTime scheduledAt;
    private int intervalMinutes;
    private int intervalSeconds;
    private String cronExpression;

    // Watcher fields (previously named inboundWatcherEnabled)
    private boolean watcherEnabled;
    private int inboundWatcherPollIntervalMinutes;
    private int inboundWatcherMaxAgeMinutes;
    private long lastKnownRemoteFileEpoch;  // epoch ms of latest remote file at last successful run
    private long lastKnownRemoteFileSize;   // size of latest remote file at last successful run

    // Audit
    private LocalDateTime lastRunAt;
    private LocalDateTime lastStartedAt;
    private String lastRunResult;
    private LocalDateTime createdAt;
    private int retryCount;

    public ScheduledTask() {
        this.createdAt = LocalDateTime.now();
        this.status    = TaskStatus.PENDING;
        this.transferDirection = TransferDirection.OUTBOUND;
        this.transferMode = TransferMode.ENTIRE_FOLDER;
        this.intervalMinutes = 0;
        this.intervalSeconds = 0;
        this.imapFolder = "INBOX";
        this.mailSearchCriteria = "UNSEEN";
        this.mailFetchMode = MailFetchMode.BODY_ONLY;
        this.mailMailboxAddress = "";
        this.mailTenantId = "common";
        this.mailClientId = "";
        this.mailFetchScope = MailFetchScope.LATEST_ONLY;
        this.mailMaxResults = 50;
        this.mailLastKnownEpoch = 0L;
        this.mailMarkAsRead = false;
        this.mailMoveToFolderEnabled = false;
        this.mailMoveToFolderName = "";
        this.mailOutputFolder = "";
        this.retryCount = 0;
        this.watcherEnabled = false;
        this.inboundWatcherPollIntervalMinutes = 0;
        this.inboundWatcherMaxAgeMinutes = 0;
        this.backupRetentionDays = 3;
        this.backupSourceUsername = "";
        this.backupDestinationUsername = "";
    }

    // ── Getters / Setters ────────────────────────────────────────────────────

    public String getId()                        { return id; }
    public void   setId(String id)               { this.id = id; }

    public String getName()                      { return name; }
    public void   setName(String name)           { this.name = name; }

    public TaskType getTaskType()                { return taskType; }
    public void     setTaskType(TaskType t)      { this.taskType = t; }

    public TaskStatus getStatus()                { return status; }
    public void       setStatus(TaskStatus s)    { this.status = s; }

    public String getSourceCredentialId()        { return sourceCredentialId; }
    public void   setSourceCredentialId(String s){ this.sourceCredentialId = s; }

    public String getTargetCredentialId()        { return targetCredentialId; }
    public void   setTargetCredentialId(String s){ this.targetCredentialId = s; }

    /** Username for the target server; used to load creds_<username>.xml at runtime. */
    public String getTargetUsername()            { return targetUsername; }
    public void   setTargetUsername(String u)    { this.targetUsername = u; }

    public TransferDirection getTransferDirection() { return transferDirection; }
    public void setTransferDirection(TransferDirection d) { this.transferDirection = d; }

    public TransferMode getTransferMode() { return transferMode; }
    public void setTransferMode(TransferMode m) { this.transferMode = m; }

    public String getSourcePath()                { return sourcePath; }
    public void   setSourcePath(String s)        { this.sourcePath = s; }

    public String getTargetPath()                { return targetPath; }
    public void   setTargetPath(String s)        { this.targetPath = s; }

    public String getBackupSourcePath()          { return backupSourcePath; }
    public void   setBackupSourcePath(String s)  { this.backupSourcePath = s; }

    public String getBackupDestinationPath()     { return backupDestinationPath; }
    public void   setBackupDestinationPath(String s) { this.backupDestinationPath = s; }

    public int  getBackupRetentionDays()         { return backupRetentionDays; }
    public void setBackupRetentionDays(int d)    { this.backupRetentionDays = d; }

    public String getBackupSourceUsername()      { return backupSourceUsername; }
    public void   setBackupSourceUsername(String u) { this.backupSourceUsername = u; }

    public String getBackupDestinationUsername() { return backupDestinationUsername; }
    public void   setBackupDestinationUsername(String u) { this.backupDestinationUsername = u; }

    public String getImapFolder()                { return imapFolder; }
    public void   setImapFolder(String s)        { this.imapFolder = s; }

    public String getMailSearchCriteria()        { return mailSearchCriteria; }
    public void   setMailSearchCriteria(String s) { this.mailSearchCriteria = s; }

    public MailFetchMode getMailFetchMode()       { return mailFetchMode; }
    public void setMailFetchMode(MailFetchMode m) { this.mailFetchMode = m; }

    public String getMailMailboxAddress()         { return mailMailboxAddress; }
    public void   setMailMailboxAddress(String a) { this.mailMailboxAddress = a; }

    public String getMailTenantId()               { return mailTenantId; }
    public void   setMailTenantId(String t)       { this.mailTenantId = t; }

    public String getMailClientId()               { return mailClientId; }
    public void   setMailClientId(String c)       { this.mailClientId = c; }

    public MailFetchScope getMailFetchScope()      { return mailFetchScope; }
    public void   setMailFetchScope(MailFetchScope s) { this.mailFetchScope = s; }

    public int  getMailMaxResults()               { return mailMaxResults; }
    public void setMailMaxResults(int m)           { this.mailMaxResults = m; }

    public long getMailLastKnownEpoch()            { return mailLastKnownEpoch; }
    public void setMailLastKnownEpoch(long e)      { this.mailLastKnownEpoch = e; }

    public boolean isMailMarkAsRead()              { return mailMarkAsRead; }
    public void    setMailMarkAsRead(boolean b)    { this.mailMarkAsRead = b; }

    public boolean isMailMoveToFolderEnabled()     { return mailMoveToFolderEnabled; }
    public void    setMailMoveToFolderEnabled(boolean b) { this.mailMoveToFolderEnabled = b; }

    public String getMailMoveToFolderName()        { return mailMoveToFolderName; }
    public void   setMailMoveToFolderName(String f){ this.mailMoveToFolderName = f; }

    public String getMailOutputFolder()            { return mailOutputFolder; }
    public void   setMailOutputFolder(String f)    { this.mailOutputFolder = f; }

    public ScheduleType getScheduleType()        { return scheduleType; }
    public void         setScheduleType(ScheduleType s){ this.scheduleType = s; }

    public LocalDateTime getScheduledAt()        { return scheduledAt; }
    public void          setScheduledAt(LocalDateTime d){ this.scheduledAt = d; }

    public int  getIntervalMinutes()             { return intervalMinutes; }
    public void setIntervalMinutes(int m)        { this.intervalMinutes = m; }

      public long getLastKnownRemoteFileEpoch() { return lastKnownRemoteFileEpoch; }
      public void setLastKnownRemoteFileEpoch(long v) { this.lastKnownRemoteFileEpoch = v; }  
    public int  getIntervalSeconds()             { return intervalSeconds; }
    public void setIntervalSeconds(int s)        { this.intervalSeconds = s; }

    public boolean isWatcherEnabled()     { return watcherEnabled; }
    public void setWatcherEnabled(boolean enabled) { this.watcherEnabled = enabled; }

    // Backwards-compatible accessors (deprecated) — kept so older callers continue to work
    @Deprecated
    public boolean isInboundWatcherEnabled() { return isWatcherEnabled(); }
    @Deprecated
    public void setInboundWatcherEnabled(boolean enabled) { setWatcherEnabled(enabled); }

    public int getInboundWatcherPollIntervalMinutes() { return inboundWatcherPollIntervalMinutes; }
    public void setInboundWatcherPollIntervalMinutes(int minutes) { this.inboundWatcherPollIntervalMinutes = minutes; }

    public int getInboundWatcherMaxAgeMinutes()  { return inboundWatcherMaxAgeMinutes; }
    public void setInboundWatcherMaxAgeMinutes(int minutes) { this.inboundWatcherMaxAgeMinutes = minutes; }

    public String getCronExpression()            { return cronExpression; }
    public void   setCronExpression(String c)    { this.cronExpression = c; }

    public LocalDateTime getLastRunAt()          { return lastRunAt; }
    public void          setLastRunAt(LocalDateTime d){ this.lastRunAt = d; }

    public LocalDateTime getLastStartedAt()      { return lastStartedAt; }
    public void          setLastStartedAt(LocalDateTime d){ this.lastStartedAt = d; }

    public String getLastRunResult()             { return lastRunResult; }
    public void   setLastRunResult(String r)     { this.lastRunResult = r; }

    public LocalDateTime getCreatedAt()          { return createdAt; }
    public void          setCreatedAt(LocalDateTime d){ this.createdAt = d; }
    public int getRetryCount()                 { return retryCount; }
    public void setRetryCount(int count)       { this.retryCount = count; }

    @Override public String toString()           { return name; }

    public void setLastKnownRemoteFileSize(long l) {
      this.lastKnownRemoteFileSize = l;
    }

    public long getLastKnownRemoteFileSize() {
        return lastKnownRemoteFileSize;
    }
}
