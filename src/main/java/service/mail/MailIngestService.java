package service.mail;

import model.RunTrace;
import model.ScheduledTask;
import service.GraphMailService;
import service.OAuth2TokenService;
import service.WatcherSkipException;
import persistence.AppStorage;
import util.AppSettings;
import util.MailFetchMode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Fetches mail through Microsoft Graph and saves it to disk (message text, attachments,
 * or LDM/MVT/PTM .RCV files). Split out of {@code TransferService}, which now only deals
 * with file transfers; everything mail-specific lives here.
 */
public class MailIngestService {

    private static final String GRAPH_MAIL_SCOPE = "https://graph.microsoft.com/Mail.ReadWrite offline_access";
    private static final String GRAPH_APP_ONLY_SCOPE = "https://graph.microsoft.com/.default";

    private final AppStorage storage;
    /** LDM/MVT/PTM text handling for mail tasks (header block, routing codes, station lookup, message ids). */
    private final MailTextProcessor mailText;
    private final OAuth2TokenService oauthService;
    private final GraphMailService graphMailService;

    public MailIngestService(AppStorage storage, OAuth2TokenService oauthService, GraphMailService graphMailService) {
        this.storage = storage;
        this.mailText = new MailTextProcessor(storage::getDataDir);
        this.oauthService = oauthService;
        this.graphMailService = graphMailService;
    }

    /** app-config.xml {@code <graphAuthMode>CERTIFICATE</graphAuthMode>}; anything else = signed-in user (default). */
    private static boolean graphCertificateMode() {
        String v = util.AppConfig.readValue("graphAuthMode");
        return v != null && v.trim().equalsIgnoreCase("CERTIFICATE");
    }

    private static String nvl(String s, String def) { return (s != null && !s.isEmpty()) ? s : def; }

    // ─── Mail / Outlook (Microsoft Graph) ────────────────────────────────────
    //
    // Originally implemented as raw IMAP socket commands (LOGIN, SELECT,
    // SEARCH, FETCH). That approach stopped working for two independent
    // reasons on modern Microsoft 365 tenants:
    //   1. Microsoft disabled Basic Authentication for IMAP tenant-wide, so
    //      "LOGIN user pass" is rejected regardless of how correct the
    //      credentials are.
    //   2. Many tenants additionally block the IMAP protocol itself (along
    //      with POP/SMTP AUTH) via Conditional Access / authentication
    //      policies, allowing only "modern", HTTPS-based clients — which
    //      OAuth2 alone does not bypass, because the block happens at the
    //      protocol layer before authentication is evaluated.
    //
    // Microsoft Graph (see GraphMailService) is a plain HTTPS REST API — the
    // same path the Outlook web app and mobile apps use — so it is
    // unaffected by both restrictions. Auth uses the OAuth2 device-code grant
    // (see OAuth2TokenService): a person authorizes once via a browser, and
    // every scheduled run after that silently exchanges a cached refresh
    // token for a new access token with no further interaction.
    //
    // ── Mail watcher ──────────────────────────────────────────────────────
    // Reuses the task-level watcherEnabled flag (also used by file-transfer
    // watchers — safe, since a task is only ever one TaskType). When enabled,
    // the configured Fetch Scope is overridden: every run fetches ALL messages
    // with receivedDateTime strictly after the last successful run's newest
    // processed message (capped by Max Messages as a safety limit), mirroring
    // the file-transfer watcher's epoch-baseline approach. An empty result
    // throws WatcherSkipException so the scheduler marks the run SKIPPED
    // rather than a bare (and slightly misleading) SUCCESS.
    //
    // ── Mark as read / move to folder ─────────────────────────────────────
    // Optional per-task post-processing, applied to each successfully fetched
    // message: mark as read (Graph PATCH), then optionally move to another
    // folder (Graph POST .../move — done *after* marking read, since a move
    // invalidates the original message ID). Both are best-effort per message:
    // one message's failure is logged and does not fail the whole run.

    public boolean executeImapMailTask(ScheduledTask task, Consumer<String> logLine)
            throws WatcherSkipException {
        String mailbox = nvl(task.getMailMailboxAddress(), "");
        if (mailbox.isEmpty()) {
            logLine.accept("[ERROR] Mailbox address is required for Outlook Mail tasks.");
            return false;
        }

        String tenantId = nvl(task.getMailTenantId(), "common");
        String clientId = nvl(task.getMailClientId(), "");
        if (clientId.isEmpty()) {
            logLine.accept("[ERROR] Azure AD Client ID is not configured on this task. "
                    + "Register a public-client app in Azure AD (see README) and set it "
                    + "in the Mail/Outlook tab, then click \"Authorize Mailbox\" once.");
            return false;
        }

        if (!oauthService.isEnrolled(mailbox)) {
            logLine.accept("[ERROR] Mailbox '" + mailbox + "' has not been authorized yet. "
                    + "Open this task in the editor and click \"Authorize Mailbox\" to complete "
                    + "one-time sign-in — after that this task runs unattended.");
            return false;
        }

        String outputFolder = nvl(task.getMailOutputFolder(), "");
        if (outputFolder.isEmpty()) {
            logLine.accept("[ERROR] Output Folder is not configured on this task — "
                    + "each fetched message is written there as a .RCV file.");
            return false;
        }
        Path outputDir = Paths.get(outputFolder);
        try {
            Files.createDirectories(outputDir);
        } catch (IOException ex) {
            logLine.accept("[ERROR] Cannot create/access Output Folder '" + outputFolder + "': " + ex.getMessage());
            return false;
        }

        String accessToken;
        GraphMailService graph = graphMailService;
        try {
            if (graphCertificateMode()) {
                // App-only login with a certificate: no user sign-in and no rotating refresh token.
                String pfx = util.AppConfig.readValue("graphCertFile");
                if (pfx == null) {
                    logLine.accept("[ERROR] graphAuthMode is CERTIFICATE but graphCertFile is not set in app-config.xml");
                    return false;
                }
                String envName = util.AppConfig.readValue("graphCertPasswordEnv");
                String pw = System.getenv(envName != null ? envName : "OPSTOOL_GRAPH_CERT_PW");
                accessToken = oauthService.getAppOnlyAccessToken(tenantId, clientId, Paths.get(pfx),
                        pw == null ? new char[0] : pw.toCharArray(), GRAPH_APP_ONLY_SCOPE);
                graph = graphMailService.forMailbox(mailbox);
                logLine.accept("[INFO] Using certificate (app-only) login for " + mailbox);
            } else {
                accessToken = oauthService.getValidAccessToken(mailbox, tenantId, clientId, GRAPH_MAIL_SCOPE);
            }
        } catch (IOException ex) {
            logLine.accept("[ERROR] OAuth2 token request failed: " + ex.getMessage());
            return false;
        }

        String folder   = nvl(task.getImapFolder(),         "INBOX");
        String criteria = nvl(task.getMailSearchCriteria(), "UNSEEN");
        MailFetchMode mode = task.getMailFetchMode() != null
                ? task.getMailFetchMode() : MailFetchMode.BODY_ONLY;

        boolean watcherOn = task.isWatcherEnabled();
        long afterEpoch = watcherOn ? task.getMailLastKnownEpoch() : 0L;

        ScheduledTask.MailFetchScope scope = task.getMailFetchScope() != null
                ? task.getMailFetchScope() : ScheduledTask.MailFetchScope.LATEST_ONLY;
        int cap = Math.max(1, task.getMailMaxResults() > 0 ? task.getMailMaxResults() : 50);
        // Watcher mode always means "everything new since last run" (capped),
        // overriding LATEST_ONLY/ALL_MATCHING the same way the file-transfer
        // watcher overrides manual TransferMode selection while it's active.
        int maxResults = watcherOn ? cap
                : (scope == ScheduledTask.MailFetchScope.LATEST_ONLY ? 1 : cap);

        logLine.accept("[INFO] Reading mail via Microsoft Graph — mailbox: " + mailbox
                + " | folder: " + folder + " | criteria: " + criteria + " | mode: " + mode
                + (watcherOn
                        ? " | watcher: ON (after " + (afterEpoch > 0 ? Instant.ofEpochMilli(afterEpoch) : "epoch") + ", max " + maxResults + ")"
                        : " | scope: " + scope + (scope == ScheduledTask.MailFetchScope.ALL_MATCHING ? " (max " + maxResults + ")" : "")));

        // Each message is handled end-to-end (download -> write .RCV/attachments -> mark read -> move)
        // on one of a few worker threads, so several mails arriving together are no longer processed
        // one after another. Graph allows ~4 concurrent requests per mailbox, hence the small cap.
        final String token = accessToken;
        final GraphMailService graphSvc = graph;
        final boolean markReadFlag = task.isMailMarkAsRead();
        final Map<String, String> folderIdCache = new java.util.concurrent.ConcurrentHashMap<>();
        final Object logLock = new Object();
        final java.util.Set<String> unsavedIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final Consumer<String> safeLog = s -> { synchronized (logLock) { logLine.accept(s); } };
        // Run key/trigger live in thread-locals of this (task) thread; the mail workers must be handed them.
        final MailRunContext mailCtx = new MailRunContext(task, mailbox, folder,
                RunTrace.currentRunKey(), RunTrace.currentTrigger());

        List<GraphMailService.MailMessage> messages;
        try {
            messages = graph.fetchMessages(
                    accessToken, folder, criteria, mode, maxResults, afterEpoch, safeLog,
                    mailWorkerThreads(),
                    m -> {
                        if (!processFetchedMailMessage(m, mode, outputDir, graphSvc, token, markReadFlag,
                                folderIdCache, logLock, logLine, mailCtx)) {
                            unsavedIds.add(m.id);
                        }
                    });
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            logLine.accept("[ERROR] Graph mail task failed: " + ex.getMessage());
            return false;
        }

        if (messages.isEmpty()) {
            if (watcherOn) {
                String skipMsg = "Watcher skipped: no messages received after "
                        + (afterEpoch > 0 ? Instant.ofEpochMilli(afterEpoch) : "the configured baseline") + ".";
                logLine.accept("[INFO] " + skipMsg);
                throw new WatcherSkipException(skipMsg);
            }
            return true; // nothing matched — not a failure outside watcher mode
        }

        // Newest received time across the whole batch becomes the watcher baseline (only reached when every
        // message was fetched; a fetch failure above returns early without advancing it).
        // A message whose content could not be written was left in the mailbox on purpose, so the baseline
        // must stay below it (otherwise watcher mode would never look at it again).
        long oldestUnsaved = Long.MAX_VALUE;
        for (GraphMailService.MailMessage m : messages) {
            if (!unsavedIds.contains(m.id)) continue;
            try { oldestUnsaved = Math.min(oldestUnsaved, Instant.parse(m.receivedDateTime).toEpochMilli()); }
            catch (Exception ignored) { /* unparseable: ignore */ }
        }
        long newestEpoch = 0L;
        for (GraphMailService.MailMessage m : messages) {
            if (unsavedIds.contains(m.id)) continue;
            try {
                long ts = Instant.parse(m.receivedDateTime).toEpochMilli();
                if (ts < oldestUnsaved && ts > newestEpoch) newestEpoch = ts;
            } catch (Exception ignored) {
                // Unparseable timestamp — skip baseline consideration for this message only.
            }
        }
        if (!unsavedIds.isEmpty()) {
            logLine.accept("[WARN] " + unsavedIds.size() + " message(s) could not be saved and were left in '"
                    + folder + "' — they will be retried on the next run.");
        }

        if (watcherOn && newestEpoch > 0) {
            task.setMailLastKnownEpoch(newestEpoch);
            if (!storage.saveTaskRunState(task)) {
                // Same risk as the file-transfer baseline above: if this doesn't
                // persist, the next run re-sees (and may re-fetch/re-move) the
                // same already-processed message(s).
                logLine.accept("[WARN] Failed to persist updated mail watcher baseline (epoch=" + newestEpoch
                        + ") — the next run may re-process these same message(s).");
            }
            String readable = Instant.ofEpochMilli(newestEpoch)
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            logLine.accept("[INFO] Mail watcher baseline updated \u2192 epoch=" + newestEpoch
                    + " (" + readable + " local) | task saved, ID=" + task.getId());
        }

        return true;
    }

    // ─── Per-message processing (runs on a mail worker thread) ──────────────

    /** Worker threads used per mail task run. Override with {@code mailWorkerThreads} in app-config.xml (1 = strictly sequential). */
    private int mailWorkerThreads() {
        int n = 4;
        try {
            String v = util.AppConfig.readValue("mailWorkerThreads");
            if (v != null && !v.trim().isEmpty()) n = Integer.parseInt(v.trim());
        } catch (RuntimeException ignored) { /* bad value -> default */ }
        return Math.max(1, Math.min(n, 8));
    }

    /** Guards "pick a free file name, then claim it" so concurrent workers never choose the same name. */
    private final Object rcvNameLock = new Object();

    /** Receives one event per mail message a run handled; the scheduler wires this to the Events feed. */
    public interface MailEventListener {
        void onMailEvent(ScheduledTask task, boolean saved, String summary, String details,
                         LocalDateTime start, LocalDateTime end, String runKey, String trigger);
    }

    private volatile MailEventListener mailEventListener;

    public void setMailEventListener(MailEventListener l) { this.mailEventListener = l; }

    /** What the mail workers need to know about the run they belong to (captured on the task thread). */
    private record MailRunContext(ScheduledTask task, String mailbox, String folder, String runKey, String trigger) {}

    /**
     * Everything that happens to one fetched message: log its content, write the .RCV file, save
     * attachments, mark it read, move it to its routed folder. The mailbox is only touched (mark read,
     * move) after the .RCV file and all attachments were written; if a write fails the message stays
     * where it is so the next run retries it, and this returns false. Log lines are buffered and
     * emitted as one block so output from messages handled in parallel doesn't interleave line by
     * line, and one Events-feed event with the full details is recorded per message.
     */
    private boolean processFetchedMailMessage(GraphMailService.MailMessage m, MailFetchMode mode, Path outputDir,
                                              GraphMailService graph, String accessToken, boolean markRead,
                                              Map<String, String> folderIdCache, Object logLock,
                                              Consumer<String> logLine, MailRunContext ctx) {
        List<String> buf = new ArrayList<>();
        Consumer<String> log = buf::add;
        LocalDateTime startedAt = LocalDateTime.now();
        boolean saved = true;
        String classification = null;
        Path rcvFile = null;
        long rcvBytes = -1;
        String rcvError = null;
        List<String> attachReport = new ArrayList<>();
        String readState = markRead ? "not attempted" : "not requested";
        String moveState = "not attempted";
        String destinationFolder = null;
        try {
            log.accept("[MAIL MESSAGE] " + m.subject + " | from=" + m.from
                    + " | received=" + m.receivedDateTime);
            if (m.bodyContent == null || m.bodyContent.isEmpty()) {
                log.accept("[WARN] No payload returned for message id=" + m.id);
            } else {
                for (String ln : m.bodyContent.split("\r?\n")) log.accept(ln);
            }
            log.accept("[END MAIL MESSAGE]");

            // Classify once, before naming the file, and reuse for the filename,
            // attachment placement, and the mailbox folder move below, so all
            // three stay consistent.
            classification = mailText.classifyForFolderRouting(m);

            try {
                String content = buildRcvFileContent(m, mode, log);
                synchronized (rcvNameLock) {
                    rcvFile = uniqueRcvPath(outputDir, buildRcvFileName(m, classification));
                    Files.createFile(rcvFile); // claim the name before releasing the lock
                }
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                Files.write(rcvFile, bytes);
                rcvBytes = bytes.length;
                log.accept("[INFO] Wrote " + rcvFile.getFileName());
            } catch (IOException ex) {
                rcvError = ex.getMessage();
                log.accept("[WARN] Failed to write .RCV file for '" + m.subject + "': " + ex.getMessage());
                saved = false;
            }

            if (!saveAttachmentsToDisk(m, outputDir, classification, log, attachReport)) saved = false;

            if (!saved) {
                moveState = "not moved — content was not fully saved, left in the mailbox for the next run";
                log.accept("[WARN] Not marking/moving '" + m.subject
                        + "' because its content was not fully saved — left in the mailbox for the next run.");
                return false;
            }

            // Mark-as-read must happen before move — moving invalidates the message ID.
            if (markRead) {
                try {
                    graph.markAsRead(accessToken, m.id);
                    readState = "yes";
                    log.accept("[INFO] Marked as read: " + m.subject);
                } catch (Exception ex) {
                    if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                    readState = "FAILED — " + ex.getMessage();
                    log.accept("[WARN] Failed to mark message as read (" + m.subject + "): " + ex.getMessage());
                }
            }

            // Every processed message is routed automatically by content: LDM/PTM
            // messages go to their respective configured folder, anything
            // else goes to the configured "Others" folder.
            destinationFolder = mailText.resolveMoveFolderName(classification);
            try {
                graph.moveMessage(accessToken, m.id, destinationFolder, log, folderIdCache);
                moveState = "moved to '" + destinationFolder + "'";
                log.accept("[INFO] Moved to folder '" + destinationFolder + "': " + m.subject);
            } catch (Exception ex) {
                if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                moveState = "FAILED to move to '" + destinationFolder + "' — " + ex.getMessage();
                log.accept("[WARN] Failed to move message (" + m.subject + "): " + ex.getMessage());
            }
            return true;
        } finally {
            // Attachments over 1 MB live in temporary files while the message is processed: remove them now.
            if (m.attachmentFiles != null) for (GraphMailService.AttachmentFile f : m.attachmentFiles) f.discard();
            synchronized (logLock) {
                for (String line : buf) logLine.accept(line);
            }
            fireMailEvent(ctx, m, mode, classification, saved, rcvFile, rcvBytes, rcvError, attachReport,
                    readState, moveState, startedAt);
        }
    }

    /** Builds and delivers the Events-feed entry for one mail message — a one-line summary plus a full detail block. */
    private void fireMailEvent(MailRunContext ctx, GraphMailService.MailMessage m, MailFetchMode mode,
                               String classification, boolean saved, Path rcvFile, long rcvBytes, String rcvError,
                               List<String> attachReport, String readState, String moveState,
                               LocalDateTime startedAt) {
        MailEventListener l = mailEventListener;
        if (l == null) return;
        try {
            LocalDateTime endedAt = LocalDateTime.now();
            String subject = (m.subject == null || m.subject.isBlank()) ? "(no subject)" : m.subject.trim();
            String received = m.receivedDateTime;
            try {
                received = Instant.parse(m.receivedDateTime).atZone(java.time.ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " local (" + m.receivedDateTime + ")";
            } catch (Exception ignored) { /* keep the raw value */ }
            int nAtt = m.attachmentFiles == null ? 0 : m.attachmentFiles.size();
            boolean fromAttachment = mode != MailFetchMode.FULL_MESSAGE
                    && m.attachmentText != null && !m.attachmentText.trim().isEmpty();

            StringBuilder summary = new StringBuilder();
            summary.append(classification == null ? "Mail" : classification).append(" \u2014 \"").append(subject).append("\"");
            if (m.from != null && !m.from.isBlank()) summary.append(" from ").append(m.from);
            if (saved) {
                summary.append(" \u2192 ").append(rcvFile != null ? rcvFile.getFileName() : "(no file)");
                if (nAtt > 0) summary.append(" + ").append(nAtt).append(nAtt == 1 ? " attachment" : " attachments");
                summary.append("; ").append(moveState);
            } else {
                summary.append(" \u2014 NOT saved");
                if (rcvError != null) summary.append(": ").append(rcvError);
                summary.append("; left in '").append(ctx.folder()).append("' for the next run");
            }

            StringBuilder d = new StringBuilder();
            d.append("Subject:         ").append(subject).append('\n');
            d.append("From:            ").append(m.from == null ? "" : m.from).append('\n');
            d.append("Received:        ").append(received).append('\n');
            d.append("Message ID:      ").append(m.id).append('\n');
            d.append("Mailbox / folder: ").append(ctx.mailbox()).append(" / ").append(ctx.folder()).append('\n');
            d.append("Fetch mode:      ").append(mode).append('\n');
            d.append("Classified as:   ").append(classification == null ? "(not classified)" : classification).append('\n');
            d.append("Content source:  ").append(mode == MailFetchMode.FULL_MESSAGE ? "raw MIME"
                    : fromAttachment ? "text attachment (" + String.join(", ", m.attachmentNames) + ")" : "message body").append('\n');
            d.append("Body length:     ").append(m.bodyContent == null ? 0 : m.bodyContent.length()).append(" chars\n");
            d.append(".RCV file:       ");
            if (rcvFile != null && rcvBytes >= 0) d.append(rcvFile).append(" (").append(rcvBytes).append(" bytes)");
            else d.append("NOT written").append(rcvError != null ? " \u2014 " + rcvError : "");
            d.append('\n');
            d.append("Attachments:     ").append(nAtt == 0 ? "none" : String.valueOf(nAtt)).append('\n');
            for (String a : attachReport) d.append("  - ").append(a).append('\n');
            d.append("Marked as read:  ").append(readState).append('\n');
            d.append("Mailbox action:  ").append(moveState).append('\n');
            d.append("Handled by:      ").append(Thread.currentThread().getName()).append('\n');
            d.append("Processing time: ").append(java.time.Duration.between(startedAt, endedAt).toMillis()).append(" ms\n");

            l.onMailEvent(ctx.task(), saved, summary.toString(), d.toString(), startedAt, endedAt,
                    ctx.runKey(), ctx.trigger());
        } catch (RuntimeException ignored) {
            // The Events feed is best-effort — never let it break mail processing.
        }
    }

    // ─── Attachment download ────────────────────────────────────────────────

    /**
     * Saves every downloadable (non-inline, file-type) attachment of a
     * message to disk under:
     *   <attachmentBase>/<LDM|PTM|Others>/<message-scoped subfolder>/<original filename>
     * using the same LDM/PTM/Others classification that decides which
     * mailbox folder the message itself gets moved to, so attachments and
     * their parent message end up filed the same way. Each message gets its
     * own subfolder (named the same as its .RCV file, minus the extension)
     * so that attachments from different messages sharing a filename (e.g.
     * two "report.pdf") never collide or overwrite one another.
     *
     * <p>{@code <attachmentBase>} is
     * {@link AppSettings#getAttachmentDownloadLocation()} — a single,
     * independently configurable location (editable live via the Settings
     * panel, or app-settings.json / app-config.xml's
     * {@code <sitaMessaging><attachmentDownloadLocation>} on first run) —
     * so attachments no longer have to land inside each task's own output
     * directory. If it's unset, the previous behavior (attachments nested
     * under the task's {@code outputDir}) is kept for backward compatibility.
     */
    private boolean saveAttachmentsToDisk(GraphMailService.MailMessage m, Path outputDir,
                                          String classification, Consumer<String> logLine,
                                          List<String> report) {
        if (m.attachmentFiles == null || m.attachmentFiles.isEmpty()) return true;
        boolean allSaved = true;

        String bucket = "LDM".equals(classification) ? "LDM"
                : "PTM".equals(classification) ? "PTM"
                : "Others";

        String messageFolderName = buildRcvFileName(m, classification);
        int dot = messageFolderName.lastIndexOf('.');
        if (dot > 0) messageFolderName = messageFolderName.substring(0, dot);

        String configuredBase = AppSettings.getAttachmentDownloadLocation();
        Path attachmentBaseDir = (configuredBase != null) ? Paths.get(configuredBase) : outputDir.resolve("Attachments");
        Path attachmentsDir = attachmentBaseDir.resolve(bucket).resolve(messageFolderName);
        try {
            Files.createDirectories(attachmentsDir);
        } catch (IOException ex) {
            logLine.accept("[WARN] Could not create attachments folder '" + attachmentsDir + "': " + ex.getMessage());
            report.add("(all) \u2014 FAILED: could not create folder " + attachmentsDir + ": " + ex.getMessage());
            return false;
        }

        for (GraphMailService.AttachmentFile file : m.attachmentFiles) {
            String safeName = (file.name == null || file.name.trim().isEmpty() ? "attachment" : file.name.trim())
                    .replaceAll("[\\\\/:*?\"<>|]", "_");
            Path dest;
            // Guard against two attachments sharing a name — on the same message, or on two messages
            // handled in parallel that landed in the same folder (same-second LDM/PTM name). The name
            // is claimed under a lock so concurrent workers can't pick the same one.
            synchronized (rcvNameLock) {
                dest = attachmentsDir.resolve(safeName);
                int suffix = 1;
                while (Files.exists(dest)) {
                    int extDot = safeName.lastIndexOf('.');
                    String base = extDot > 0 ? safeName.substring(0, extDot) : safeName;
                    String ext  = extDot > 0 ? safeName.substring(extDot) : "";
                    dest = attachmentsDir.resolve(base + "_" + (suffix++) + ext);
                }
                try {
                    Files.createFile(dest);
                } catch (IOException ex) {
                    logLine.accept("[WARN] Failed to save attachment '" + file.name + "': " + ex.getMessage());
                    report.add(file.name + " \u2014 FAILED: " + ex.getMessage());
                    allSaved = false;
                    continue;
                }
            }
            try {
                file.copyTo(dest);
                logLine.accept("[INFO] Saved attachment '" + file.name + "' -> " + dest);
                report.add(file.name + " (" + (file.contentType == null || file.contentType.isEmpty() ? "unknown type" : file.contentType)
                        + ", " + file.length() + " bytes) \u2192 " + dest);
            } catch (IOException ex) {
                logLine.accept("[WARN] Failed to save attachment '" + file.name + "': " + ex.getMessage());
                report.add(file.name + " \u2014 FAILED: " + ex.getMessage());
                allSaved = false;
            }
        }
        return allSaved;
    }

    // ─── .RCV file output ─────────────────────────────────────────────────────

    private static final DateTimeFormatter RCV_LDM_PTM_TS_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * Builds a filesystem-safe filename for a fetched message.
     *
     * <p>LDM/PTM messages (classification == "LDM" or "PTM") use the fixed
     * operational naming convention {@code LDM_YYYYMMDDHHMMSS.RCV} /
     * {@code PTM_YYYYMMDDHHMMSS.RCV} instead of the generic subject-based
     * name — collisions within the same second are resolved by
     * {@link #uniqueRcvPath(Path, String)} at write time.
     *
     * <p>Everything else keeps the previous generic form:
     * {@code <timestamp>_<sanitized subject>_<id suffix>.RCV}
     */
    private String buildRcvFileName(GraphMailService.MailMessage m, String classification) {
        if ("LDM".equals(classification) || "PTM".equals(classification)) {
            String ts = java.time.LocalDateTime.now().format(RCV_LDM_PTM_TS_FMT);
            return classification + "_" + ts + ".RCV";
        }

        String subject = (m.subject == null || m.subject.trim().isEmpty()) ? "no_subject" : m.subject.trim();
        String safeSubject = subject.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", "_");
        if (safeSubject.length() > 60) safeSubject = safeSubject.substring(0, 60);

        String ts = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String idSuffix = (m.id != null && m.id.length() >= 6)
                ? m.id.substring(m.id.length() - 6)
                : String.format("%06d", Math.abs((long) (Math.random() * 1_000_000)));

        return ts + "_" + safeSubject + "_" + idSuffix + ".RCV";
    }

    /**
     * Resolves {@code outputDir/desiredName} to a path that doesn't already
     * exist, appending "_2", "_3", ... before the extension if needed.
     * Needed because the LDM/PTM naming convention (type + second-resolution
     * timestamp) can collide when two messages of the same type land within
     * the same second.
     */
    private Path uniqueRcvPath(Path outputDir, String desiredName) {
        Path candidate = outputDir.resolve(desiredName);
        if (!Files.exists(candidate)) return candidate;

        int dot = desiredName.lastIndexOf('.');
        String base = dot > 0 ? desiredName.substring(0, dot) : desiredName;
        String ext = dot > 0 ? desiredName.substring(dot) : "";
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = outputDir.resolve(base + "_" + suffix + ext);
            suffix++;
        }
        return candidate;
    }

    /**
     * Builds the .RCV file content. FULL_MESSAGE mode already returns raw
     * RFC 2822 MIME (headers included) from Graph, so it's written as-is.
     *
     * Content source preference: a message's own (non-inline) attachment text
     * takes priority over its body when a parseable text attachment exists
     * (see GraphMailService#fetchTextAttachments) — the structured content
     * these tasks care about is more often carried in an attached .txt/.csv
     * than typed into the body. Falls back to the body when there's no
     * usable attachment.
     *
     * BODY_ONLY and HEADERS_AND_BODY previously produced identical output —
     * both prepended Subject/From/Received regardless of mode, so "BODY_ONLY"
     * never actually meant body-only. Fixed to a real three-tier distinction:
     *   BODY_ONLY        → the message content: HTML converted to plain text,
     *                       automated banners (Outlook "first contact" /
     *                       external-sender warnings) removed, truncated to
     *                       start at the LDM/MVT/PTM marker, then
     *                       signatures/sign-offs, footers/disclaimers, and
     *                       quoted reply history stripped out (best-effort).
     *                       When an LDM/MVT/PTM marker is found, a SITA-style
     *                       =HEADER/=PRIORITY/... block (see
     *                       buildSitaHeaderBlock) is prepended.
     *   HEADERS_AND_BODY → Subject/From/Received + the full content as plain
     *                       text, untouched (signatures/quotes kept, no SITA
     *                       header — that block is specific to BODY_ONLY).
     *   FULL_MESSAGE     → raw MIME from Graph, as before.
     */
    private String buildRcvFileContent(GraphMailService.MailMessage m, MailFetchMode mode, Consumer<String> logLine) {
        if (mode == MailFetchMode.FULL_MESSAGE) {
            // Raw MIME — left completely untouched. A blank line here is
            // structurally meaningful (it's what separates the MIME headers
            // from the body per RFC 2822), so blank-line removal must never
            // be applied to this branch.
            return m.bodyContent != null ? m.bodyContent : "";
        }

        boolean useAttachment = m.attachmentText != null && !m.attachmentText.trim().isEmpty();
        String sourceText = useAttachment ? m.attachmentText : mailText.toPlainText(m.bodyContent, m.bodyType);

        if (mode == MailFetchMode.BODY_ONLY) {
            String bannerStripped = mailText.removeAutomatedBanners(sourceText);
            MailTextProcessor.MarkerMatch marker = mailText.findMessageTypeMarker(bannerStripped);
            String markerBody = marker != null ? bannerStripped.substring(marker.index) : bannerStripped;

            // For LDM/PTM, the literal marker is immediately followed by an
            // "=TEXT" tag line marking the start of the free-text content,
            // matching the =FIELD convention used by the rest of the header.
            if (marker != null && ("LDM".equals(marker.type) || "PTM".equals(marker.type))) {
                markerBody = marker.type + "\n=TEXT" + markerBody.substring(marker.type.length());
            }

            String messageBody = mailText.removeBlankLines(mailText.stripSignatureAndQuotedContent(markerBody));

            if (marker == null) {
                // Not an LDM/MVT/PTM message — no SITA header applies.
                return messageBody;
            }

            String originCode = null;
            String destinationCode = null;
            if ("LDM".equals(marker.type)) {
                originCode = mailText.extractLdmOriginCode(bannerStripped, messageBody);
                destinationCode = mailText.extractLdmDestinationCode(messageBody);
            } else if ("PTM".equals(marker.type)) {
                String[] od = mailText.extractPtmOriginDestination(messageBody);
                originCode = od[0];
                destinationCode = od[1];
            } else {
                // No extraction rule has been specified yet for MVT —
                // the configured default address is used for both fields.
                logLine.accept("[INFO] No origin/destination extraction rule configured for " + marker.type
                        + " messages yet — falling back to the configured default SITA address.");
            }

            String originAddress = mailText.resolveStationAddress(originCode, marker.type, "ORIGIN", logLine);
            String destinationAddress = mailText.resolveStationAddress(destinationCode, marker.type, "DESTINATION TYPE B", logLine);

            String header = mailText.buildSitaHeaderBlock(marker.type, originAddress, destinationAddress, mailText.nextMessageId());
            return header + messageBody;
        }

        // HEADERS_AND_BODY
        StringBuilder sb = new StringBuilder();
        sb.append("Subject: ").append(m.subject != null ? m.subject : "").append("\r\n");
        sb.append("From: ").append(m.from != null ? m.from : "").append("\r\n");
        sb.append("Received: ").append(m.receivedDateTime != null ? m.receivedDateTime : "").append("\r\n");
        if (useAttachment && !m.attachmentNames.isEmpty()) {
            sb.append("Source: attachment (").append(String.join(", ", m.attachmentNames)).append(")\r\n");
        }
        sb.append("\r\n");
        sb.append(mailText.removeBlankLines(sourceText));
        return sb.toString();
    }
}
