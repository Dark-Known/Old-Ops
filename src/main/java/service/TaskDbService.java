package service;

import model.ScheduledTask;
import model.ScheduledTask.*;
import util.MailFetchMode;

import java.io.File;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Stores scheduled tasks in a small SQLite database instead of the single
 * hand-rolled {@code tasks.xml} file every save used to rewrite whole.
 *
 * <p>Database file lives at {@code <dataDir>/app.db} — the same file
 * {@link util.AppSettings}'s {@code settings} table and
 * {@link CredentialDbService}'s {@code credentials} table already use, each
 * service owning its own table ({@code tasks} here) rather than a separate
 * {@code .db} file per concern. One row per task in the {@code tasks}
 * table, keyed by id, mirroring the old one-{@code <task>}-element-per-task
 * layout. A single shared {@link Connection} is kept open and all access is
 * synchronized — write volume here is a handful of edits per session (task
 * create/edit/delete, plus the periodic last-run/status bookkeeping writes),
 * so a connection pool would be overkill, and SQLite only supports one
 * writer at a time regardless. This also means a task save is now a single
 * row upsert instead of reloading, editing, and rewriting the entire task
 * list to a temp file — the old approach existed only because XML has no
 * partial-write story, which SQLite doesn't have that problem to begin
 * with.
 *
 * <p>On first use: if a legacy {@code tasks.xml} file is found (from before
 * tasks were merged into {@code app.db}) and the {@code tasks} table is
 * currently empty, every {@code <task>} element is parsed with the same
 * field-by-field logic {@code XmlStorageService} used to use, inserted here,
 * and the old file renamed with a {@code .migrated} suffix as a safety net
 * (never deleted). Same one-time, non-destructive pattern already used for
 * {@code app-settings.db} (see {@link util.AppSettings}) and
 * {@code credentials.db}/{@code creds_*.xml} (see {@link CredentialDbService}).
 */
public class TaskDbService {

    private static final Logger log = Logger.getLogger(TaskDbService.class.getName());
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final Connection conn;
    private final File dataDir;

    public TaskDbService(File dataDir) {
        this.dataDir = dataDir;
        File dbFile = new File(dataDir, "app.db");
        Connection c = null;
        try {
            Class.forName("org.sqlite.JDBC");
            c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS tasks (" +
                        "id TEXT PRIMARY KEY," +
                        "name TEXT," +
                        "task_type TEXT," +
                        "status TEXT," +
                        "source_credential_id TEXT," +
                        "target_credential_id TEXT," +
                        "target_username TEXT," +
                        "transfer_direction TEXT," +
                        "transfer_mode TEXT," +
                        "source_path TEXT," +
                        "target_path TEXT," +
                        "additional_target_paths TEXT," +
                        "backup_source_path TEXT," +
                        "backup_destination_path TEXT," +
                        "backup_retention_days INTEGER," +
                        "backup_source_username TEXT," +
                        "backup_destination_username TEXT," +
                        "imap_folder TEXT," +
                        "mail_search_criteria TEXT," +
                        "mail_fetch_mode TEXT," +
                        "mail_mailbox_address TEXT," +
                        "mail_tenant_id TEXT," +
                        "mail_client_id TEXT," +
                        "mail_fetch_scope TEXT," +
                        "mail_max_results INTEGER," +
                        "mail_last_known_epoch INTEGER," +
                        "mail_mark_as_read INTEGER," +
                        "mail_move_to_folder_enabled INTEGER," +
                        "mail_move_to_folder_name TEXT," +
                        "mail_output_folder TEXT," +
                        "schedule_type TEXT," +
                        "scheduled_at TEXT," +
                        "interval_minutes INTEGER," +
                        "interval_seconds INTEGER," +
                        "cron_expression TEXT," +
                        "watcher_enabled INTEGER," +
                        "inbound_watcher_poll_interval_minutes INTEGER," +
                        "inbound_watcher_max_age_minutes INTEGER," +
                        "last_known_remote_file_epoch INTEGER," +
                        "last_known_remote_file_size INTEGER," +
                        "last_run_at TEXT," +
                        "last_started_at TEXT," +
                        "last_run_result TEXT," +
                        "created_at TEXT," +
                        "retry_count INTEGER" +
                        ")");
            }
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to open/initialize tasks table in app.db", e);
        }
        this.conn = c;
        if (this.conn != null) {
            migrateLegacyXmlIfPresent();
        }
    }

    private static final String UPSERT_SQL = "INSERT INTO tasks (" +
            "id, name, task_type, status, source_credential_id, target_credential_id, target_username, " +
            "transfer_direction, transfer_mode, source_path, target_path, additional_target_paths, " +
            "backup_source_path, backup_destination_path, backup_retention_days, backup_source_username, " +
            "backup_destination_username, imap_folder, mail_search_criteria, mail_fetch_mode, " +
            "mail_mailbox_address, mail_tenant_id, mail_client_id, mail_fetch_scope, mail_max_results, " +
            "mail_last_known_epoch, mail_mark_as_read, mail_move_to_folder_enabled, mail_move_to_folder_name, " +
            "mail_output_folder, schedule_type, scheduled_at, interval_minutes, interval_seconds, " +
            "cron_expression, watcher_enabled, inbound_watcher_poll_interval_minutes, " +
            "inbound_watcher_max_age_minutes, last_known_remote_file_epoch, last_known_remote_file_size, " +
            "last_run_at, last_started_at, last_run_result, created_at, retry_count" +
            ") VALUES (" + "?,".repeat(45).replaceAll(",$", "") + ") " +
            "ON CONFLICT(id) DO UPDATE SET " +
            "name=excluded.name, task_type=excluded.task_type, status=excluded.status, " +
            "source_credential_id=excluded.source_credential_id, target_credential_id=excluded.target_credential_id, " +
            "target_username=excluded.target_username, transfer_direction=excluded.transfer_direction, " +
            "transfer_mode=excluded.transfer_mode, source_path=excluded.source_path, target_path=excluded.target_path, " +
            "additional_target_paths=excluded.additional_target_paths, backup_source_path=excluded.backup_source_path, " +
            "backup_destination_path=excluded.backup_destination_path, backup_retention_days=excluded.backup_retention_days, " +
            "backup_source_username=excluded.backup_source_username, backup_destination_username=excluded.backup_destination_username, " +
            "imap_folder=excluded.imap_folder, mail_search_criteria=excluded.mail_search_criteria, " +
            "mail_fetch_mode=excluded.mail_fetch_mode, mail_mailbox_address=excluded.mail_mailbox_address, " +
            "mail_tenant_id=excluded.mail_tenant_id, mail_client_id=excluded.mail_client_id, " +
            "mail_fetch_scope=excluded.mail_fetch_scope, mail_max_results=excluded.mail_max_results, " +
            "mail_last_known_epoch=excluded.mail_last_known_epoch, mail_mark_as_read=excluded.mail_mark_as_read, " +
            "mail_move_to_folder_enabled=excluded.mail_move_to_folder_enabled, " +
            "mail_move_to_folder_name=excluded.mail_move_to_folder_name, mail_output_folder=excluded.mail_output_folder, " +
            "schedule_type=excluded.schedule_type, scheduled_at=excluded.scheduled_at, " +
            "interval_minutes=excluded.interval_minutes, interval_seconds=excluded.interval_seconds, " +
            "cron_expression=excluded.cron_expression, watcher_enabled=excluded.watcher_enabled, " +
            "inbound_watcher_poll_interval_minutes=excluded.inbound_watcher_poll_interval_minutes, " +
            "inbound_watcher_max_age_minutes=excluded.inbound_watcher_max_age_minutes, " +
            "last_known_remote_file_epoch=excluded.last_known_remote_file_epoch, " +
            "last_known_remote_file_size=excluded.last_known_remote_file_size, " +
            "last_run_at=excluded.last_run_at, last_started_at=excluded.last_started_at, " +
            "last_run_result=excluded.last_run_result, created_at=excluded.created_at, retry_count=excluded.retry_count";

    /** Inserts or replaces (by id) a task. Assigns an id if missing, same as the old XML-backed saveTask did. */
    public synchronized void save(ScheduledTask t) {
        if (conn == null) return;
        if (t.getId() == null || t.getId().isEmpty()) {
            t.setId(UUID.randomUUID().toString());
        }
        try (PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {
            int i = 1;
            ps.setString(i++, t.getId());
            ps.setString(i++, t.getName());
            ps.setString(i++, t.getTaskType() != null ? t.getTaskType().name() : null);
            ps.setString(i++, t.getStatus() != null ? t.getStatus().name() : null);
            ps.setString(i++, t.getSourceCredentialId());
            ps.setString(i++, t.getTargetCredentialId());
            ps.setString(i++, t.getTargetUsername());
            ps.setString(i++, t.getTransferDirection() != null ? t.getTransferDirection().name() : TransferDirection.OUTBOUND.name());
            ps.setString(i++, t.getTransferMode() != null ? t.getTransferMode().name() : TransferMode.ENTIRE_FOLDER.name());
            ps.setString(i++, t.getSourcePath());
            ps.setString(i++, t.getTargetPath());
            ps.setString(i++, t.getAdditionalTargetPaths());
            ps.setString(i++, t.getBackupSourcePath());
            ps.setString(i++, t.getBackupDestinationPath());
            ps.setInt(i++, t.getBackupRetentionDays());
            ps.setString(i++, t.getBackupSourceUsername());
            ps.setString(i++, t.getBackupDestinationUsername());
            ps.setString(i++, t.getImapFolder());
            ps.setString(i++, t.getMailSearchCriteria());
            ps.setString(i++, t.getMailFetchMode() != null ? t.getMailFetchMode().name() : MailFetchMode.BODY_ONLY.name());
            ps.setString(i++, t.getMailMailboxAddress());
            ps.setString(i++, t.getMailTenantId() != null ? t.getMailTenantId() : "common");
            ps.setString(i++, t.getMailClientId());
            ps.setString(i++, t.getMailFetchScope() != null ? t.getMailFetchScope().name() : MailFetchScope.LATEST_ONLY.name());
            ps.setInt(i++, t.getMailMaxResults() > 0 ? t.getMailMaxResults() : 50);
            ps.setLong(i++, t.getMailLastKnownEpoch());
            ps.setInt(i++, t.isMailMarkAsRead() ? 1 : 0);
            ps.setInt(i++, t.isMailMoveToFolderEnabled() ? 1 : 0);
            ps.setString(i++, t.getMailMoveToFolderName());
            ps.setString(i++, t.getMailOutputFolder());
            ps.setString(i++, t.getScheduleType() != null ? t.getScheduleType().name() : null);
            ps.setString(i++, t.getScheduledAt() != null ? t.getScheduledAt().format(DT_FMT) : null);
            ps.setInt(i++, t.getIntervalMinutes());
            ps.setInt(i++, t.getIntervalSeconds());
            ps.setString(i++, t.getCronExpression());
            ps.setInt(i++, t.isWatcherEnabled() ? 1 : 0);
            ps.setInt(i++, t.getInboundWatcherPollIntervalMinutes());
            ps.setInt(i++, t.getInboundWatcherMaxAgeMinutes());
            ps.setLong(i++, t.getLastKnownRemoteFileEpoch());
            ps.setLong(i++, t.getLastKnownRemoteFileSize());
            ps.setString(i++, t.getLastRunAt() != null ? t.getLastRunAt().format(DT_FMT) : null);
            ps.setString(i++, t.getLastStartedAt() != null ? t.getLastStartedAt().format(DT_FMT) : null);
            ps.setString(i++, t.getLastRunResult());
            ps.setString(i++, t.getCreatedAt() != null ? t.getCreatedAt().format(DT_FMT) : null);
            ps.setInt(i++, t.getRetryCount());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to save task " + t.getId(), e);
        }
    }

    /** Deletes the task with the given id, if any. */
    public synchronized void delete(String id) {
        if (conn == null || id == null || id.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM tasks WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to delete task " + id, e);
        }
    }

    /** All stored tasks. One bad row (e.g. an enum value no longer supported) is skipped rather than failing the whole load. */
    public synchronized List<ScheduledTask> loadAll() {
        List<ScheduledTask> list = new ArrayList<>();
        if (conn == null) return list;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM tasks")) {
            while (rs.next()) {
                try {
                    list.add(mapRow(rs));
                } catch (IllegalArgumentException enumEx) {
                    // Most commonly a stored enum value (e.g. a removed TaskType) that no
                    // longer exists in this build — skip just this task, same behavior as
                    // the old XML loader had for the same situation.
                    log.warning("Skipping task " + safeGetString(rs, "id")
                            + " — no longer supported by this version: " + enumEx.getMessage());
                } catch (Exception rowEx) {
                    log.log(Level.WARNING, "Skipping unreadable task " + safeGetString(rs, "id"), rowEx);
                }
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to load tasks", e);
        }
        return list;
    }

    private static String safeGetString(ResultSet rs, String col) {
        try { return rs.getString(col); } catch (SQLException e) { return "?"; }
    }

    private ScheduledTask mapRow(ResultSet rs) throws SQLException {
        ScheduledTask t = new ScheduledTask();
        t.setId(rs.getString("id"));
        t.setName(rs.getString("name"));
        t.setTaskType(TaskType.valueOf(rs.getString("task_type")));
        t.setStatus(TaskStatus.valueOf(rs.getString("status")));
        t.setSourceCredentialId(rs.getString("source_credential_id"));
        t.setTargetCredentialId(rs.getString("target_credential_id"));
        t.setTargetUsername(rs.getString("target_username"));
        String direction = rs.getString("transfer_direction");
        t.setTransferDirection(direction != null && !direction.isEmpty() ? TransferDirection.valueOf(direction) : TransferDirection.OUTBOUND);
        String mode = rs.getString("transfer_mode");
        t.setTransferMode(mode != null && !mode.isEmpty() ? TransferMode.valueOf(mode) : TransferMode.ENTIRE_FOLDER);
        t.setSourcePath(rs.getString("source_path"));
        t.setTargetPath(rs.getString("target_path"));
        t.setAdditionalTargetPaths(rs.getString("additional_target_paths"));
        t.setBackupSourcePath(rs.getString("backup_source_path"));
        t.setBackupDestinationPath(rs.getString("backup_destination_path"));
        t.setBackupRetentionDays(rs.getInt("backup_retention_days"));
        t.setBackupSourceUsername(rs.getString("backup_source_username"));
        t.setBackupDestinationUsername(rs.getString("backup_destination_username"));
        t.setImapFolder(rs.getString("imap_folder"));
        t.setMailSearchCriteria(rs.getString("mail_search_criteria"));
        String fetchMode = rs.getString("mail_fetch_mode");
        t.setMailFetchMode(fetchMode != null && !fetchMode.isEmpty() ? MailFetchMode.valueOf(fetchMode) : MailFetchMode.BODY_ONLY);
        t.setMailMailboxAddress(rs.getString("mail_mailbox_address"));
        String tenant = rs.getString("mail_tenant_id");
        t.setMailTenantId(tenant != null && !tenant.isEmpty() ? tenant : "common");
        t.setMailClientId(rs.getString("mail_client_id"));
        String scope = rs.getString("mail_fetch_scope");
        t.setMailFetchScope(scope != null && !scope.isEmpty() ? MailFetchScope.valueOf(scope) : MailFetchScope.LATEST_ONLY);
        int maxResults = rs.getInt("mail_max_results");
        t.setMailMaxResults(maxResults > 0 ? maxResults : 50);
        t.setMailLastKnownEpoch(rs.getLong("mail_last_known_epoch"));
        t.setMailMarkAsRead(rs.getInt("mail_mark_as_read") != 0);
        t.setMailMoveToFolderEnabled(rs.getInt("mail_move_to_folder_enabled") != 0);
        t.setMailMoveToFolderName(rs.getString("mail_move_to_folder_name"));
        t.setMailOutputFolder(rs.getString("mail_output_folder"));
        String scheduleType = rs.getString("schedule_type");
        if (scheduleType != null && !scheduleType.isEmpty()) t.setScheduleType(ScheduleType.valueOf(scheduleType));
        String scheduledAt = rs.getString("scheduled_at");
        if (scheduledAt != null && !scheduledAt.isEmpty()) t.setScheduledAt(LocalDateTime.parse(scheduledAt, DT_FMT));
        t.setIntervalMinutes(rs.getInt("interval_minutes"));
        t.setIntervalSeconds(rs.getInt("interval_seconds"));
        t.setCronExpression(rs.getString("cron_expression"));
        t.setWatcherEnabled(rs.getInt("watcher_enabled") != 0);
        t.setInboundWatcherPollIntervalMinutes(rs.getInt("inbound_watcher_poll_interval_minutes"));
        t.setInboundWatcherMaxAgeMinutes(rs.getInt("inbound_watcher_max_age_minutes"));
        t.setLastKnownRemoteFileEpoch(rs.getLong("last_known_remote_file_epoch"));
        t.setLastKnownRemoteFileSize(rs.getLong("last_known_remote_file_size"));
        String lastRunAt = rs.getString("last_run_at");
        if (lastRunAt != null && !lastRunAt.isEmpty()) t.setLastRunAt(LocalDateTime.parse(lastRunAt, DT_FMT));
        String lastStartedAt = rs.getString("last_started_at");
        if (lastStartedAt != null && !lastStartedAt.isEmpty()) t.setLastStartedAt(LocalDateTime.parse(lastStartedAt, DT_FMT));
        t.setLastRunResult(rs.getString("last_run_result"));
        String createdAt = rs.getString("created_at");
        if (createdAt != null && !createdAt.isEmpty()) t.setCreatedAt(LocalDateTime.parse(createdAt, DT_FMT));
        t.setRetryCount(rs.getInt("retry_count"));
        return t;
    }

    /**
     * One-time import of the legacy {@code tasks.xml} file, run only if the
     * {@code tasks} table is currently empty. Parses each {@code <task>}
     * element with the same field-by-field logic (including the same
     * defaults and legacy-tag fallbacks, e.g. {@code inboundWatcherEnabled}
     * before it was renamed to {@code watcherEnabled}) that
     * {@code XmlStorageService.loadTasks()} used before this class existed,
     * then saves each parsed task here. The old file is left on disk
     * afterward (renamed with a {@code .migrated} suffix) purely as a
     * safety net — never deleted.
     */
    private void migrateLegacyXmlIfPresent() {
        File legacy = new File(dataDir, "tasks.xml");
        if (!legacy.exists()) return;

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM tasks")) {
            if (rs.next() && rs.getInt(1) > 0) return; // already has data — don't overwrite
        } catch (SQLException e) {
            log.log(Level.WARNING, "Could not check tasks table before legacy tasks.xml migration", e);
            return;
        }

        int migrated = 0;
        try {
            javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            org.w3c.dom.Document doc = dbf.newDocumentBuilder().parse(legacy);
            org.w3c.dom.NodeList nodes = doc.getElementsByTagName("task");
            for (int i = 0; i < nodes.getLength(); i++) {
                try {
                    org.w3c.dom.Element e = (org.w3c.dom.Element) nodes.item(i);
                    ScheduledTask t = new ScheduledTask();
                    t.setId(e.getAttribute("id"));
                    t.setName(text(e, "name"));
                    t.setTaskType(TaskType.valueOf(text(e, "taskType")));
                    t.setStatus(TaskStatus.valueOf(text(e, "status")));
                    t.setSourceCredentialId(text(e, "sourceCredentialId"));
                    t.setTargetCredentialId(text(e, "targetCredentialId"));
                    t.setSourcePath(text(e, "sourcePath"));
                    t.setTargetPath(text(e, "targetPath"));
                    t.setAdditionalTargetPaths(text(e, "additionalTargetPaths"));
                    t.setBackupSourcePath(text(e, "backupSourcePath"));
                    t.setBackupDestinationPath(text(e, "backupDestinationPath"));
                    String backupRetention = text(e, "backupRetentionDays");
                    if (backupRetention != null && !backupRetention.isEmpty()) {
                        try {
                            t.setBackupRetentionDays(Integer.parseInt(backupRetention));
                        } catch (NumberFormatException ignored) {
                            t.setBackupRetentionDays(3);
                        }
                    }
                    t.setBackupSourceUsername(text(e, "backupSourceUsername"));
                    t.setBackupDestinationUsername(text(e, "backupDestinationUsername"));
                    t.setImapFolder(text(e, "imapFolder"));
                    t.setMailSearchCriteria(text(e, "mailSearchCriteria"));
                    String fetchMode = text(e, "mailFetchMode");
                    if (fetchMode == null || fetchMode.isEmpty()) fetchMode = "BODY_ONLY";
                    t.setMailFetchMode(MailFetchMode.valueOf(fetchMode));
                    t.setMailMailboxAddress(text(e, "mailMailboxAddress"));
                    String mailTenant = text(e, "mailTenantId");
                    t.setMailTenantId(mailTenant != null && !mailTenant.isEmpty() ? mailTenant : "common");
                    t.setMailClientId(text(e, "mailClientId"));
                    String fetchScope = text(e, "mailFetchScope");
                    t.setMailFetchScope(fetchScope != null && !fetchScope.isEmpty()
                            ? MailFetchScope.valueOf(fetchScope) : MailFetchScope.LATEST_ONLY);
                    String maxResults = text(e, "mailMaxResults");
                    try {
                        t.setMailMaxResults(maxResults != null && !maxResults.isEmpty()
                                ? Integer.parseInt(maxResults) : 50);
                    } catch (NumberFormatException nfe) {
                        t.setMailMaxResults(50);
                    }
                    String mailEpoch = text(e, "mailLastKnownEpoch");
                    try {
                        t.setMailLastKnownEpoch(mailEpoch != null && !mailEpoch.isEmpty()
                                ? Long.parseLong(mailEpoch) : 0L);
                    } catch (NumberFormatException nfe) {
                        t.setMailLastKnownEpoch(0L);
                    }
                    t.setMailMarkAsRead("true".equalsIgnoreCase(text(e, "mailMarkAsRead")));
                    t.setMailMoveToFolderEnabled("true".equalsIgnoreCase(text(e, "mailMoveToFolderEnabled")));
                    t.setMailMoveToFolderName(text(e, "mailMoveToFolderName"));
                    t.setMailOutputFolder(text(e, "mailOutputFolder"));
                    String direction = text(e, "transferDirection");
                    if (direction == null || direction.isEmpty()) direction = "OUTBOUND";
                    t.setTransferDirection(TransferDirection.valueOf(direction));
                    String txMode = text(e, "transferMode");
                    if (txMode == null || txMode.isEmpty()) txMode = "ENTIRE_FOLDER";
                    t.setTransferMode(TransferMode.valueOf(txMode));
                    t.setScheduleType(ScheduleType.valueOf(text(e, "scheduleType")));
                    String sat = text(e, "scheduledAt");
                    if (sat != null && !sat.isEmpty()) t.setScheduledAt(LocalDateTime.parse(sat, DT_FMT));
                    String intervalMinutes = text(e, "intervalMinutes");
                    if (intervalMinutes != null && !intervalMinutes.isEmpty()) {
                        try {
                            t.setIntervalMinutes(Integer.parseInt(intervalMinutes));
                        } catch (NumberFormatException ignored) {
                            t.setIntervalMinutes(0);
                        }
                    }
                    String intervalSeconds = text(e, "intervalSeconds");
                    if (intervalSeconds != null && !intervalSeconds.isEmpty()) {
                        try {
                            t.setIntervalSeconds(Integer.parseInt(intervalSeconds));
                        } catch (NumberFormatException ignored) {
                            t.setIntervalSeconds(0);
                        }
                    }
                    t.setCronExpression(text(e, "cronExpression"));
                    String pollInterval = text(e, "inboundWatcherPollIntervalMinutes");
                    if (pollInterval != null && !pollInterval.isEmpty()) {
                        try {
                            t.setInboundWatcherPollIntervalMinutes(Integer.parseInt(pollInterval));
                        } catch (NumberFormatException ignored) {
                            t.setInboundWatcherPollIntervalMinutes(0);
                        }
                    }
                    String lastRun = text(e, "lastRunAt");
                    if (lastRun != null && !lastRun.isEmpty()) t.setLastRunAt(LocalDateTime.parse(lastRun, DT_FMT));
                    String lastStarted = text(e, "lastStartedAt");
                    if (lastStarted != null && !lastStarted.isEmpty()) t.setLastStartedAt(LocalDateTime.parse(lastStarted, DT_FMT));
                    t.setLastRunResult(text(e, "lastRunResult"));
                    String created = text(e, "createdAt");
                    if (created != null && !created.isEmpty()) t.setCreatedAt(LocalDateTime.parse(created, DT_FMT));
                    String retryCount = text(e, "retryCount");
                    if (retryCount != null && !retryCount.isEmpty()) {
                        try {
                            t.setRetryCount(Integer.parseInt(retryCount));
                        } catch (NumberFormatException ignored) {
                            t.setRetryCount(0);
                        }
                    }
                    // Support both legacy 'inboundWatcherEnabled' and current 'watcherEnabled' tags
                    String watcherFlag = text(e, "watcherEnabled");
                    if (watcherFlag == null) watcherFlag = text(e, "inboundWatcherEnabled");
                    if (watcherFlag != null && !watcherFlag.isEmpty()) {
                        t.setWatcherEnabled(Boolean.parseBoolean(watcherFlag));
                    }
                    String watcherAge = text(e, "inboundWatcherMaxAgeMinutes");
                    if (watcherAge != null && !watcherAge.isEmpty()) {
                        try {
                            t.setInboundWatcherMaxAgeMinutes(Integer.parseInt(watcherAge));
                        } catch (NumberFormatException ignored) {
                            t.setInboundWatcherMaxAgeMinutes(0);
                        }
                    }
                    t.setTargetUsername(text(e, "targetUsername"));
                    String epoch = text(e, "lastKnownRemoteFileEpoch");
                    if (epoch != null && !epoch.isEmpty()) {
                        try {
                            t.setLastKnownRemoteFileEpoch(Long.parseLong(epoch));
                        } catch (NumberFormatException ignored) {
                            t.setLastKnownRemoteFileEpoch(0L);
                        }
                    }
                    String knownSize = text(e, "lastKnownRemoteFileSize");
                    if (knownSize != null && !knownSize.isEmpty()) {
                        try {
                            t.setLastKnownRemoteFileSize(Long.parseLong(knownSize));
                        } catch (NumberFormatException ignored) {
                            t.setLastKnownRemoteFileSize(-1L);
                        }
                    }
                    save(t);
                    migrated++;
                } catch (IllegalArgumentException enumEx) {
                    log.warning("Skipping legacy/unrecognized task at index " + i
                            + " during tasks.xml migration — no longer supported by this version: " + enumEx.getMessage());
                } catch (Exception taskEx) {
                    log.log(Level.WARNING, "Skipping unparseable task at index " + i + " during tasks.xml migration", taskEx);
                }
            }
            File renamed = new File(legacy.getParentFile(), legacy.getName() + ".migrated");
            legacy.renameTo(renamed);
            if (migrated > 0) {
                log.info("Migrated " + migrated + " task(s) from legacy tasks.xml into app.db");
            }
        } catch (Exception ex) {
            log.log(Level.WARNING, "Failed to migrate legacy tasks.xml into app.db — leaving tasks.xml in place untouched", ex);
        }
    }

    private static String text(org.w3c.dom.Element parent, String tag) {
        org.w3c.dom.NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() == 0) return "";
        return nl.item(0).getTextContent();
    }

    public synchronized void close() {
        if (conn != null) {
            try { conn.close(); } catch (SQLException ignored) {}
        }
    }
}
