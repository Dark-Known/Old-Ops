package service;

import model.Credential;
import model.RemoteFileException;
import model.RemoteFileMetadata;
import model.ScheduledTask;
import model.ScheduledTask.TransferDirection;
import model.ScheduledTask.TransferMode;
import service.RemoteFileMetadataServiceFactory.ManagedMetadataService;
import util.MailFetchMode;
import util.MiniJson;
import util.AppConfig;
import util.AppSettings;

import java.io.*;
import java.net.InetAddress;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Executes file transfers via WinSCP's scripting interface (winscp.com).
 * Local→local file transfers are not supported — every FILE_TRANSFER task
 * moves data to/from a remote SFTP target.
 *
 * <h2>Watcher integration — both directions</h2>
 * When {@code task.isWatcherEnabled() == true} AND
 * {@code task.getTransferMode() == LATEST_ONLY}, the watcher path fires
 * regardless of transfer direction:
 *
 * <ul>
 *   <li><b>OUTBOUND LATEST_ONLY</b> — {@link LocalFileMetadataService} lists
 *       the <em>local</em> source directory and returns only files whose
 *       lastModified is newer than the stored epoch.  Those files are then
 *       pushed to the remote with WinSCP {@code put}.</li>
 *   <li><b>INBOUND LATEST_ONLY</b> — {@link SftpRemoteFileMetadataService}
 *       lists the <em>remote</em> source directory and returns only new
 *       files.  Those files are pulled with WinSCP {@code get}.</li>
 * </ul>
 *
 * <h2>Batching</h2>
 * Transfers (and Backup) are split into SIZE-based batches — see
 * {@link AppSettings#getTransferBatchMaxBytes()} — rather than a fixed file
 * count, with a configurable pause between batches
 * ({@link AppSettings#getTransferBatchIntervalSeconds()}).
 *
 * In watcher mode an empty result throws {@link WatcherSkipException} so the
 * scheduler marks the run SKIPPED rather than FAILED.  On success the epoch
 * of the newest transferred file is persisted as the new baseline.
 */
public class TransferService {

    private static final String[] WINSCP_PATHS = {
            "C:\\Program Files (x86)\\WinSCP\\WinSCP.com",
            "C:\\Program Files\\WinSCP\\WinSCP.com",
            "winscp.com"
    };

    private final XmlStorageService storage;
    private final RemoteFileMetadataServiceFactory metadataServiceFactory;
    private String winScpPath;

    // Outlook mail is read via Microsoft Graph (see class docs on executeImapMailTask).
    private static final String GRAPH_MAIL_SCOPE = "https://graph.microsoft.com/Mail.ReadWrite offline_access";
    private final OAuth2TokenService oauthService;
    private final GraphMailService graphMailService = new GraphMailService();

    // Keyed by taskId. A Set rather than a single Process because, with
    // AppSettings.getTransferBatchConcurrency() > 1, more than one WinSCP
    // process can legitimately be in flight for the same task at once (one
    // per concurrently-running batch).
    private final ConcurrentMap<String, java.util.Set<Process>> activeProcesses = new ConcurrentHashMap<>();

    public TransferService(XmlStorageService storage) {
        this.storage                = storage;
        this.metadataServiceFactory = new RemoteFileMetadataServiceFactory(storage);
        this.winScpPath             = detectWinScp();
        // Shared dataDir-based path, NOT the per-user-home default — the
        // Daemon runs as SYSTEM (see app-config.xml <runAsSystem>) while the
        // GUI runs as the logged-in user, so a user-home-based token path
        // would put them in two different, mutually invisible directories.
        this.oauthService           = new OAuth2TokenService(OAuth2TokenService.sharedTokenDir(storage.getDataDir()));
    }

    /** Exposed so the UI's "Authorize Mailbox" flow can enroll without duplicating OAuth logic. */
    public OAuth2TokenService getOAuthService() { return oauthService; }

    public void   setWinScpPath(String path) { this.winScpPath = path; }
    public String getWinScpPath()            { return winScpPath; }

    private String detectWinScp() {
        for (String p : WINSCP_PATHS) {
            if (new File(p).exists()) return p;
        }
        return WINSCP_PATHS[0];
    }

    // ─── Auto-detect source system ───────────────────────────────────────────

    public static String getLocalHostname() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return System.getenv("COMPUTERNAME"); }
    }

    public static String getLocalUsername() {
        return System.getProperty("user.name", System.getenv("USERNAME"));
    }

    // ─── Main entry point ────────────────────────────────────────────────────

    public boolean executeTransfer(ScheduledTask task, Consumer<String> logLine)
            throws WatcherSkipException {
        return executeTransfer(task, logLine, Collections.emptySet());
    }

    /**
     * @param eventFileNames filenames the push-watch layer (LocalWatchManager /
     *     RemotePushWatcher) actually observed changing for this run, or empty if
     *     this is an ordinary scheduled/manual run or the watcher only knows
     *     "something changed" without naming it (e.g. an OVERFLOW event). When
     *     non-empty and the task is watcher-enabled LATEST_ONLY, {@link
     *     #executeWatcherTransfer} transfers exactly these named files instead of
     *     deriving the file list from a directory scan filtered against the stored
     *     baseline — see that method's javadoc for why that distinction matters.
     */
    public boolean executeTransfer(ScheduledTask task, Consumer<String> logLine, Set<String> eventFileNames)
            throws WatcherSkipException {

        Credential target = resolveTargetCredential(task, logLine);
        logTransferPaths(task, target, logLine);

        // ── Watcher path: fires for BOTH directions when LATEST_ONLY + watcher enabled
        if (task.isWatcherEnabled()
                && task.getTransferMode() == TransferMode.LATEST_ONLY) {
            return executeWatcherTransfer(task, target, logLine, eventFileNames);
        }

        // ── Non-watcher remote path ───────────────────────────────────────────
        // Local→local file transfers are not supported — every FILE_TRANSFER
        // task must resolve to a remote target credential.
        if (target == null) {
            logLine.accept("[ERROR] No target credential resolved. Local\u2192local file transfers are "
                    + "not supported — set a Target Username/host/credential for this task.");
            return false;
        }

        TransferMode mode = task.getTransferMode() != null ? task.getTransferMode() : TransferMode.ENTIRE_FOLDER;
        if (mode != TransferMode.SPECIFIC_FILE) {
            try {
                return executeBatchedRemoteTransfer(target, task, logLine);
            } catch (Exception e) {
                logLine.accept("[ERROR] Transfer failed: " + e.getMessage());
                return false;
            }
        }

        File scriptFile = null;
        try {
            scriptFile = buildWinScpScript(target, target.getPassword(), task, logLine);
            logLine.accept("[INFO] WinSCP script prepared. Starting transfer...");
            boolean ok = runWinScpScript(scriptFile, logLine, task.getId());

            if (ok && task.getTransferDirection() == TransferDirection.INBOUND) {
                List<String> extraDestFolders = task.getAdditionalTargetPathList();
                if (!extraDestFolders.isEmpty()) {
                    String remotePath = normalizeRemotePath(task.getTargetPath());
                    String fname = remotePath.substring(remotePath.lastIndexOf('/') + 1);
                    String destPath = buildLocalDestinationPath(normalizeLocalPath(task.getSourcePath()), fname);
                    copyDownloadedFilesToExtraFolders(Collections.singletonList(destPath), extraDestFolders, logLine);
                }
            }
            return ok;
        } catch (Exception e) {
            logLine.accept("[ERROR] Transfer failed: " + e.getMessage());
            return false;
        } finally {
            if (scriptFile != null) scriptFile.delete();
        }
    }

    // ─── Local file batch helper (used by Backup for a local source/destination) ──

    /** Whether a local file batch operation copies (leaves original in place) or moves it. */
    private enum LocalFileOp { COPY, MOVE }

    /**
     * Transfers {@code files} into {@code destDir} on the local filesystem, in
     * SIZE-based batches (see {@link AppSettings#getTransferBatchMaxBytes()}),
     * pausing {@link AppSettings#getTransferBatchIntervalSeconds()} between
     * batches. Used by Backup for a local source/destination side.
     *
     * <p>Within each batch, individual file copies/moves run concurrently —
     * up to {@link AppSettings#getTransferBatchConcurrency()} at once — the
     * same setting that controls parallel SFTP sessions for remote transfers.
     * Concurrency is 1 (fully sequential, original behavior) by default.
     * This matters for local backlogs of many small files too: even on local
     * disks, thousands of individual open/copy/close syscalls each carry
     * fixed overhead, and overlapping them (especially when the destination
     * is a network share/mapped drive rather than truly local disk) can cut
     * wall-clock time substantially versus doing them strictly one at a time.
     */
    private boolean runLocalFileBatch(List<Path> files, Path destDir, LocalFileOp op, Consumer<String> logLine) {
        List<List<Path>> batches = chunkPathsBySize(files);
        long maxBytes = AppSettings.getTransferBatchMaxBytes();
        int intervalSeconds = AppSettings.getTransferBatchIntervalSeconds();
        int concurrency = AppSettings.getTransferBatchConcurrency();
        if (batches.size() > 1) {
            logLine.accept("[INFO] " + files.size() + " file(s) to " + (op == LocalFileOp.MOVE ? "move" : "copy")
                    + " — splitting into " + batches.size() + " batch(es), capped at ~"
                    + humanReadableBytes(maxBytes) + " per batch (configurable in Settings)."
                    + (concurrency > 1 ? " Running up to " + concurrency + " file(s) at a time." : ""));
        }
        boolean allOk = true;
        int done = 0;
        for (int i = 0; i < batches.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                logLine.accept("[INFO] " + (op == LocalFileOp.MOVE ? "Backup" : "Copy") + " cancelled.");
                return false;
            }
            List<Path> batch = batches.get(i);
            if (batches.size() > 1) {
                logLine.accept("[INFO] Batch " + (i + 1) + "/" + batches.size()
                        + " (" + batch.size() + " file(s))");
            }
            int[] batchDone = new int[1];
            boolean batchOk = (concurrency <= 1)
                    ? runLocalFileOpsSequentially(batch, destDir, op, logLine, batchDone)
                    : runLocalFileOpsConcurrently(batch, destDir, op, logLine, concurrency, batchDone);
            allOk &= batchOk;
            done += batchDone[0];
            if (i < batches.size() - 1 && intervalSeconds > 0) {
                sleepBetweenBatches(intervalSeconds, logLine);
            }
        }
        if (allOk) {
            logLine.accept("[SUCCESS] Local " + (op == LocalFileOp.MOVE ? "backup" : "copy")
                    + " completed (" + done + " file(s)).");
        }
        return allOk;
    }

    /** Original one-at-a-time behavior for a single batch — used when concurrency is 1 (default). */
    private boolean runLocalFileOpsSequentially(List<Path> batch, Path destDir, LocalFileOp op,
            Consumer<String> logLine, int[] doneOut) {
        boolean allOk = true;
        int done = 0;
        for (Path src : batch) {
            boolean ok = op == LocalFileOp.MOVE
                    ? moveSingleFile(src, destDir.resolve(src.getFileName()), logLine)
                    : copySingleFile(src, destDir.resolve(src.getFileName()), logLine);
            allOk &= ok;
            if (ok) done++;
        }
        doneOut[0] = done;
        return allOk;
    }

    /**
     * Runs a batch's file copies/moves through a fixed-size thread pool
     * (size = {@code concurrency}) instead of one file at a time. Safe here
     * because {@link #copySingleFile} / {@link #moveSingleFile} each touch a
     * distinct source/destination pair with no shared mutable state besides
     * the thread-safe logging callback.
     */
    private boolean runLocalFileOpsConcurrently(List<Path> batch, Path destDir, LocalFileOp op,
            Consumer<String> logLine, int concurrency, int[] doneOut) {
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(Math.min(concurrency, batch.size()));
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(0);
        boolean allOk = true;
        try {
            List<java.util.concurrent.Callable<Boolean>> tasks = new ArrayList<>();
            for (Path src : batch) {
                tasks.add(() -> {
                    boolean ok = op == LocalFileOp.MOVE
                            ? moveSingleFile(src, destDir.resolve(src.getFileName()), logLine)
                            : copySingleFile(src, destDir.resolve(src.getFileName()), logLine);
                    if (ok) done.incrementAndGet();
                    return ok;
                });
            }
            List<java.util.concurrent.Future<Boolean>> results;
            try {
                results = pool.invokeAll(tasks);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                doneOut[0] = done.get();
                return false;
            }
            for (java.util.concurrent.Future<Boolean> r : results) {
                try {
                    if (!Boolean.TRUE.equals(r.get())) allOk = false;
                } catch (Exception e) {
                    allOk = false;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        doneOut[0] = done.get();
        return allOk;
    }

    /** Copies a single file, replacing the destination if it already exists. */
    private boolean copySingleFile(Path src, Path dest, Consumer<String> logLine) {
        try {
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
            logLine.accept("[SUCCESS] Copied: " + src + " → " + dest);
            return true;
        } catch (IOException ex) {
            logLine.accept("[ERROR] Failed to copy " + src.getFileName() + ": " + ex.getMessage());
            return false;
        }
    }

    /** Moves a single file, replacing the destination if it already exists. */
    private boolean moveSingleFile(Path src, Path dest, Consumer<String> logLine) {
        try {
            Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING);
            logLine.accept("[SUCCESS] Backed up: " + src.getFileName());
            return true;
        } catch (IOException ex) {
            logLine.accept("[ERROR] Failed to back up " + src.getFileName() + ": " + ex.getMessage());
            return false;
        }
    }

    private void sleepBetweenBatches(int intervalSeconds, Consumer<String> logLine) {
        try {
            logLine.accept("[INFO] Pausing " + intervalSeconds + "s before next batch...");
            Thread.sleep(intervalSeconds * 1000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static String humanReadableBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format(Locale.ROOT, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    // ─── Watcher transfer — unified for INBOUND and OUTBOUND ─────────────────

    /**
     * Single watcher path used by both directions.
     *
     * <p><b>OUTBOUND:</b> uses {@link LocalFileMetadataService} to list the
     * local source directory.  The watch directory is {@code task.getSourcePath()}.
     * New files are pushed via WinSCP {@code put}.
     *
     * <p><b>INBOUND:</b> uses {@link SftpRemoteFileMetadataService} (or
     * {@link LocalFileMetadataService} for local→local) to list the remote
     * source directory.  The watch directory is {@code task.getTargetPath()}
     * (the remote side).  New files are pulled via WinSCP {@code get} or
     * copied locally.
     *
     * <p>In both cases an empty result throws {@link WatcherSkipException}.
     * On success the newest file's epoch and size are persisted.
     *
     * <p><b>Event-driven fast path:</b> when {@code eventFileNames} is non-empty
     * (the push watcher actually named the file(s) it saw change), those exact
     * files are transferred directly — the baseline "modified after" filter below
     * is bypassed entirely for them. This matters because that filter is a
     * dedupe/safety mechanism for the *scan*, not a gate on "did a real event
     * happen": a stale or clock-skewed baseline can otherwise silently exclude
     * the very file that just triggered the watch event, which looks from the
     * outside like "the watcher fired but nothing transferred". If none of the
     * named files can actually be found in the watch directory (e.g. it was a
     * transient temp file that's already gone), this falls back to the normal
     * baseline scan below rather than skipping the run outright.
     */
    private boolean executeWatcherTransfer(ScheduledTask task,
                                           Credential target,
                                           Consumer<String> logLine,
                                           Set<String> eventFileNames)
            throws WatcherSkipException {

        boolean isOutbound = task.getTransferDirection() == TransferDirection.OUTBOUND;

        long lastKnownEpochMillis = task.getLastKnownRemoteFileEpoch();
        Instant modifiedAfter = lastKnownEpochMillis > 0
                ? Instant.ofEpochMilli(lastKnownEpochMillis)
                : Instant.EPOCH;

        boolean eventDriven = eventFileNames != null && !eventFileNames.isEmpty();
        logLine.accept("[INFO] Watcher enabled (LATEST_ONLY, "
                + (isOutbound ? "OUTBOUND" : "INBOUND")
                + "). " + (eventDriven
                    ? "Event named " + eventFileNames.size() + " file(s) directly: " + eventFileNames
                    : "Querying files modified after: " + modifiedAfter + " (epoch=" + lastKnownEpochMillis + ")"));

        List<RemoteFileMetadata> newFiles = null;

        if (eventDriven) {
            newFiles = resolveEventNamedFiles(task, target, isOutbound, eventFileNames, logLine);
        }

        if (newFiles != null && newFiles.isEmpty()) {
            logLine.accept("[INFO] None of the event-named file(s) currently exist in the watch "
                    + "directory — falling back to a full baseline scan.");
            newFiles = null;
        }

        if (newFiles == null) {
        if (isOutbound) {
            // OUTBOUND: always scan the LOCAL source directory
            String watchDir = resolveOutboundWatchDirectory(task);
            logLine.accept("[INFO] Metadata service: LOCAL | watch directory: " + watchDir);

            LocalFileMetadataService localService = new LocalFileMetadataService();
            try {
                if (lastKnownEpochMillis > 0 && task.getLastKnownRemoteFileSize() >= 0) {
                    Instant modifiedAfterInclusive = Instant.ofEpochMilli(lastKnownEpochMillis - 1);
                    List<RemoteFileMetadata> candidates =
                            localService.getFilesModifiedAfter(watchDir, modifiedAfterInclusive);
                    long baselineSize = task.getLastKnownRemoteFileSize();
                    newFiles = candidates.stream()
                            .filter(f -> f.lastModified().isAfter(modifiedAfter)
                                    || (f.lastModified().equals(modifiedAfter) && f.size() != baselineSize))
                            .collect(Collectors.toList());
                } else {
                    newFiles = localService.getFilesModifiedAfter(watchDir, modifiedAfter);
                }
            } catch (RemoteFileException ex) {
                logLine.accept("[ERROR] Failed to list local source: " + ex.getMessage());
                return false;
            }

            if (lastKnownEpochMillis <= 0 && newFiles != null && !newFiles.isEmpty()) {
                long maxTs = newFiles.stream()
                        .mapToLong(f -> f.lastModified().toEpochMilli()).max().orElse(0L);
                List<RemoteFileMetadata> latestOnly = newFiles.stream()
                        .filter(f -> f.lastModified().toEpochMilli() == maxTs)
                        .collect(Collectors.toList());
                logLine.accept("[INFO] Initial run: reduced watcher candidates to "
                        + latestOnly.size() + " latest file(s) (ts=" + Instant.ofEpochMilli(maxTs) + ")");
                newFiles = latestOnly;
            }

        } else {
            // INBOUND: scan the remote source directory over SFTP
            try (ManagedMetadataService managed =
                         metadataServiceFactory.create(task, target)) {

                String watchDir = managed.watchDirectory();
                logLine.accept("[INFO] Metadata service: SFTP | watch directory: " + watchDir);

                if (lastKnownEpochMillis > 0 && task.getLastKnownRemoteFileSize() >= 0) {
                    Instant modifiedAfterInclusive = Instant.ofEpochMilli(lastKnownEpochMillis - 1);
                    List<RemoteFileMetadata> candidates =
                            managed.service().getFilesModifiedAfter(watchDir, modifiedAfterInclusive);
                    long baselineSize = task.getLastKnownRemoteFileSize();
                    newFiles = candidates.stream()
                            .filter(f -> f.lastModified().isAfter(modifiedAfter)
                                    || (f.lastModified().equals(modifiedAfter) && f.size() != baselineSize))
                            .collect(Collectors.toList());
                } else {
                    newFiles = managed.service().getFilesModifiedAfter(watchDir, modifiedAfter);
                }

            } catch (RemoteFileException ex) {
                logLine.accept("[ERROR] Failed to query file metadata: " + ex.getMessage());
                return false;
            } catch (Exception ex) {
                logLine.accept("[ERROR] Unexpected error opening metadata service: " + ex.getMessage());
                return false;
            }

            if (lastKnownEpochMillis <= 0 && newFiles != null && !newFiles.isEmpty()) {
                long maxTs = newFiles.stream()
                        .mapToLong(f -> f.lastModified().toEpochMilli()).max().orElse(0L);
                List<RemoteFileMetadata> latestOnly = newFiles.stream()
                        .filter(f -> f.lastModified().toEpochMilli() == maxTs)
                        .collect(Collectors.toList());
                logLine.accept("[INFO] Initial run: reduced watcher candidates to "
                        + latestOnly.size() + " latest file(s) (ts=" + Instant.ofEpochMilli(maxTs) + ")");
                newFiles = latestOnly;
            }
        }
        }

        // ── Skip check ────────────────────────────────────────────────────────
        if (newFiles.isEmpty()) {
            String skipMsg = "Watcher skipped: no files modified after "
                    + modifiedAfter + " found in watch directory.";
            logLine.accept("[INFO] " + skipMsg);
            throw new WatcherSkipException(skipMsg);
        }

        logLine.accept("[INFO] Watcher found " + newFiles.size() + " new/updated file(s):");
        newFiles.forEach(f -> logLine.accept("[INFO]   " + f.fileName()
                + " | lastModified=" + f.lastModified()
                + " | size=" + f.size()));

        RemoteFileMetadata newest = newFiles.stream()
                .max((a, b) -> a.lastModified().compareTo(b.lastModified()))
                .orElse(null);
        if (newest == null) {
            String skipMsg = "Watcher skipped: no files to process after filtering.";
            logLine.accept("[INFO] " + skipMsg);
            throw new WatcherSkipException(skipMsg);
        }

        // ── Transfer ──────────────────────────────────────────────────────────
        boolean success;
        if (isOutbound) {
            success = executeWinScpWatcherOutbound(task, target, newFiles, logLine);
        } else {
            success = executeWinScpWatcherInbound(task, target, newFiles, logLine);
        }

        // ── Persist new baseline ──────────────────────────────────────────────
        // Guard against the event-driven path (which bypasses the "modified after
        // baseline" filter above) moving the baseline backward — e.g. a stray
        // MODIFY event on a file older than what's already been transferred.
        // The non-event scan path can't hit this: it only ever returns files
        // already known to be >= baseline, so newest is always >= baseline there too.
        if (success && newest.lastModified().toEpochMilli() < lastKnownEpochMillis) {
            logLine.accept("[INFO] Event-named file's timestamp is older than the current baseline "
                    + "(epoch=" + lastKnownEpochMillis + ") — leaving baseline unchanged.");
        } else if (success) {
            long newEpoch = newest.lastModified().toEpochMilli();
            long countWithSameTs = newFiles.stream()
                    .filter(f -> f.lastModified().toEpochMilli() == newEpoch)
                    .count();
            long newSize = countWithSameTs > 1 ? -1L : newest.size();

            task.setLastKnownRemoteFileEpoch(newEpoch);
            task.setLastKnownRemoteFileSize(newSize);
            storage.saveTask(task);
            String readableTime = Instant.ofEpochMilli(newEpoch)
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            logLine.accept("[INFO] Watcher baseline updated → epoch=" + newEpoch
                    + " (" + readableTime + " local)"
                    + ", size=" + newSize
                    + " (newest file: " + newest.fileName() + ")"
                    + " | task saved, ID=" + task.getId());
        }

        return success;
    }

    /**
     * Resolves {@code names} to their live {@link RemoteFileMetadata} by listing
     * the watch directory (local for OUTBOUND, SFTP for INBOUND) and keeping only
     * the entries whose filename the watcher actually named — used by the
     * event-driven fast path in {@link #executeWatcherTransfer} instead of a
     * baseline-filtered scan. Deliberately re-lists rather than {@code stat}-ing
     * each name individually: the existing {@link RemoteFileMetadataService}
     * implementations only expose "list everything after a cutoff", and calling
     * that once with {@link Instant#EPOCH} and filtering client-side avoids
     * needing a new per-file stat method on the SFTP implementation just for
     * this path. Returns an empty list if none of the named files currently
     * exist, or {@code null} on a hard connection/listing failure — the caller
     * treats both the same way (fall back to the ordinary baseline scan below),
     * in keeping with this class's existing "push is an optimization, the
     * baseline scan is always the safety net" pattern elsewhere.
     */
    private List<RemoteFileMetadata> resolveEventNamedFiles(ScheduledTask task,
                                                             Credential target,
                                                             boolean isOutbound,
                                                             Set<String> names,
                                                             Consumer<String> logLine) {
        try {
            List<RemoteFileMetadata> all;
            if (isOutbound) {
                String watchDir = resolveOutboundWatchDirectory(task);
                all = new LocalFileMetadataService().getFilesModifiedAfter(watchDir, Instant.EPOCH);
            } else {
                try (ManagedMetadataService managed = metadataServiceFactory.create(task, target)) {
                    all = managed.service().getFilesModifiedAfter(managed.watchDirectory(), Instant.EPOCH);
                }
            }
            List<RemoteFileMetadata> matched = all.stream()
                    .filter(f -> names.contains(f.fileName()))
                    .collect(Collectors.toList());
            Set<String> foundNames = matched.stream().map(RemoteFileMetadata::fileName).collect(Collectors.toSet());
            for (String name : names) {
                if (!foundNames.contains(name)) {
                    logLine.accept("[INFO]   (event-named file no longer present, skipping: " + name + ")");
                }
            }
            return matched;
        } catch (RemoteFileException ex) {
            logLine.accept("[ERROR] Failed to resolve event-named file(s): " + ex.getMessage());
            return null;
        } catch (Exception ex) {
            logLine.accept("[ERROR] Unexpected error resolving event-named file(s): " + ex.getMessage());
            return null;
        }
    }

    private void probeWindowsRemotePaths(com.jcraft.jsch.Session session,
                                         String rawPath,
                                         Consumer<String> logLine) {
        try {
            com.jcraft.jsch.ChannelSftp probe =
                    (com.jcraft.jsch.ChannelSftp) session.openChannel("sftp");
            probe.connect();

            String base        = rawPath.replace("\\", "/").replaceAll("/+$", "");
            String noLead      = base.replaceFirst("^/", "");
            String asIs        = base;
            String withSlash   = "/" + noLead;
            String noDrive     = base.replaceFirst("^[A-Za-z]:/", "/");
            String cygwin      = base.replaceFirst("^([A-Za-z]):/", "/$1/");
            String doubleSlash = "//" + noLead;

            logLine.accept("[PROBE] pwd = " + probe.pwd());

            for (String candidate : List.of(asIs, withSlash, noDrive, cygwin, doubleSlash, "/")) {
                try {
                    com.jcraft.jsch.SftpATTRS attrs = probe.stat(candidate);
                    logLine.accept("[PROBE] SUCCESS : '" + candidate
                            + "' | isDir=" + attrs.isDir());
                } catch (com.jcraft.jsch.SftpException e) {
                    logLine.accept("[PROBE] FAILED  : '" + candidate
                            + "' | code=" + e.id + " | " + e.getMessage());
                }
            }

            probe.disconnect();

        } catch (Exception e) {
            logLine.accept("[PROBE] Could not open probe channel: " + e.getMessage());
        }
    }

    // ─── Outbound watcher watch-directory resolution ──────────────────────────

    private String resolveOutboundWatchDirectory(ScheduledTask task) {
        String path = task.getSourcePath();
        if (path == null) return ".";
        if (path.endsWith("*")) {
            int lastSep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            if (lastSep >= 0) path = path.substring(0, lastSep);
        }
        File f = new File(path);
        if (f.isFile()) return f.getParent() != null ? f.getParent() : ".";
        return path;
    }

    // ─── WinSCP outbound watcher transfer ────────────────────────────────────

    private boolean executeWinScpWatcherOutbound(ScheduledTask task,
                                                 Credential target,
                                                 List<RemoteFileMetadata> files,
                                                 Consumer<String> logLine) {
        if (target == null) {
            logLine.accept("[ERROR] No target credential for outbound transfer.");
            return false;
        }

        String localSourceDir = resolveOutboundWatchDirectory(task);
        String remotePath     = normalizeRemotePath(task.getTargetPath());
        String remoteDir      = remotePath.endsWith("/") ? remotePath : remotePath + "/";

        try {
            List<SizedCommand> commands = new ArrayList<>();
            List<String> extraDestFolders = task.getAdditionalTargetPathList();
            if (!extraDestFolders.isEmpty()) {
                logLine.accept("[INFO] Also copying to " + extraDestFolders.size()
                        + " additional destination(s) on the same server: " + String.join(", ", extraDestFolders));
            }
            for (RemoteFileMetadata meta : files) {
                String localFile  = normalizeLocalPath(
                        Paths.get(localSourceDir, meta.fileName()).toString());
                String remoteFile = remoteDir + meta.fileName();
                commands.add(new SizedCommand(
                        "put " + escapeWinScpPath(localFile) + " " + escapeWinScpRemotePath(remoteFile),
                        meta.size()));
                logLine.accept("[INFO] Queued outbound: " + localFile + " → " + remoteFile);

                for (String extraFolder : extraDestFolders) {
                    String extraRemoteDir = normalizeRemotePath(extraFolder);
                    extraRemoteDir = extraRemoteDir.endsWith("/") ? extraRemoteDir : extraRemoteDir + "/";
                    String extraRemoteFile = extraRemoteDir + meta.fileName();
                    commands.add(new SizedCommand(
                            "put " + escapeWinScpPath(localFile) + " " + escapeWinScpRemotePath(extraRemoteFile),
                            meta.size()));
                }
            }
            return runBatchedWinScpCommands(target, target.getPassword(), commands, logLine, task.getId());
        } catch (Exception ex) {
            logLine.accept("[ERROR] Outbound watcher WinSCP transfer failed: " + ex.getMessage());
            return false;
        }
    }

    // ─── WinSCP inbound watcher transfer ─────────────────────────────────────

    private boolean executeWinScpWatcherInbound(ScheduledTask task,
                                                Credential target,
                                                List<RemoteFileMetadata> files,
                                                Consumer<String> logLine) {
        String localDestDir = normalizeLocalPath(task.getSourcePath());
        String remotePath   = normalizeRemotePath(task.getTargetPath());
        String remoteDir    = remotePath.endsWith("/") ? remotePath : remotePath + "/";

        try {
            List<SizedCommand> commands = new ArrayList<>();
            List<String> downloadedLocalPaths = new ArrayList<>();
            for (RemoteFileMetadata meta : files) {
                String remoteFile = remoteDir + meta.fileName();
                String destPath   = buildLocalDestinationPath(localDestDir, meta.fileName());
                commands.add(new SizedCommand(
                        "get " + escapeWinScpRemotePath(remoteFile) + " " + escapeWinScpPath(destPath),
                        meta.size()));
                logLine.accept("[INFO] Queued inbound: " + remoteFile + " → " + destPath);
                downloadedLocalPaths.add(destPath);
            }
            boolean ok = runBatchedWinScpCommands(target, target.getPassword(), commands, logLine, task.getId());
            List<String> extraDestFolders = task.getAdditionalTargetPathList();
            if (ok && !extraDestFolders.isEmpty()) {
                logLine.accept("[INFO] Also copying downloaded file(s) to " + extraDestFolders.size()
                        + " additional local destination(s): " + String.join(", ", extraDestFolders));
                copyDownloadedFilesToExtraFolders(downloadedLocalPaths, extraDestFolders, logLine);
            }
            return ok;
        } catch (Exception ex) {
            logLine.accept("[ERROR] Inbound watcher WinSCP transfer failed: " + ex.getMessage());
            return false;
        }
    }

    // ─── WinSCP script builder (non-watcher remote paths) ────────────────────

    /**
     * Builds a single-file WinSCP script. Only used for SPECIFIC_FILE mode —
     * ENTIRE_FOLDER and LATEST_ONLY go through {@link #executeBatchedRemoteTransfer}
     * instead, since those can involve more than one file and need batching.
     */
    private File buildWinScpScript(Credential target, String password,
                                   ScheduledTask task,
                                   Consumer<String> logLine) throws Exception {

        File tmpScript = File.createTempFile("opstool_", ".txt");
        secureTemp(tmpScript);

        String localPath  = normalizeLocalPath(task.getSourcePath());
        String remotePath = normalizeRemotePath(task.getTargetPath());

        logLine.accept("[INFO] Target OS: " + target.getOsType() + " | Protocol: SFTP");
        logLine.accept("[INFO] Transfer mode: " + task.getTransferMode().name());

        try (PrintWriter pw = new PrintWriter(new FileWriter(tmpScript))) {
            pw.println("option batch abort");
            pw.println("option confirm off");
            pw.println("open sftp://" + escapeUrl(target.getUsername())
                    + ":" + escapeUrl(password) + "@" + target.getHost() + "/ -hostkey=\"*\"");

            boolean inbound = task.getTransferDirection() == TransferDirection.INBOUND;

            if (inbound) {
                logLine.accept("[INFO] Mode: Specific file (INBOUND)");
                String fname    = remotePath.substring(remotePath.lastIndexOf('/') + 1);
                String destPath = buildLocalDestinationPath(localPath, fname);
                pw.println("get " + escapeWinScpRemotePath(remotePath)
                        + " " + escapeWinScpPath(destPath));
            } else {
                logLine.accept("[INFO] Mode: Specific file (OUTBOUND)");
                String fileName = new File(localPath).getName();
                String remoteDestPath = prepareRemoteDestination(remotePath, fileName);
                pw.println("put "
                        + escapeWinScpPath(localPath)
                        + " " + escapeWinScpRemotePath(remoteDestPath));

                List<String> extraDestFolders = task.getAdditionalTargetPathList();
                if (!extraDestFolders.isEmpty()) {
                    logLine.accept("[INFO] Also copying to " + extraDestFolders.size()
                            + " additional destination(s) on the same server: " + String.join(", ", extraDestFolders));
                }
                for (String extraFolder : extraDestFolders) {
                    String extraDestPath = prepareRemoteDestination(normalizeRemotePath(extraFolder), fileName);
                    pw.println("put "
                            + escapeWinScpPath(localPath)
                            + " " + escapeWinScpRemotePath(extraDestPath));
                }
            }

            pw.println("close");
            pw.println("exit");
        }
        return tmpScript;
    }

    // ─── Legacy WinSCP ls (non-watcher INBOUND LATEST_ONLY fallback) ─────────

    /** A remote file's last-modified epoch (ms) and size (bytes), as parsed from `ls -l`. */
    private static final class RemoteFileStat {
        final long epochMillis;
        final long size;
        RemoteFileStat(long epochMillis, long size) {
            this.epochMillis = epochMillis;
            this.size = size;
        }
    }

    private java.util.Map<String, RemoteFileStat> rawRemoteFileStats(
            Credential target, String password, String dirPath,
            Consumer<String> logLine) throws Exception {

        logLine.accept("[INFO] Listing remote directory (WinSCP ls): " + dirPath);

        File listScript = File.createTempFile("opstool_ls_", ".txt");
        secureTemp(listScript);

        try (PrintWriter pw = new PrintWriter(new FileWriter(listScript))) {
            pw.println("option batch abort");
            pw.println("option confirm off");
            pw.println("open sftp://" + escapeUrl(target.getUsername())
                    + ":" + escapeUrl(password) + "@" + target.getHost() + "/ -hostkey=\"*\"");
            pw.println("ls \"" + dirPath + "\"");
            pw.println("close");
            pw.println("exit");
        }

        List<String> rawLines = new ArrayList<>();
        File listLogFile = new File(listScript.getAbsolutePath() + ".log");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    winScpPath, "/script=" + listScript.getAbsolutePath(),
                    "/log=" + listLogFile.getAbsolutePath(), "/loglevel=1");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            try (BufferedReader br =
                         new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    // NOTE: intentionally not logging every raw `ls` line here.
                    // For directories with many thousands of files this used to push
                    // one log line (and one UI update) per file, which is what drove
                    // the CPU/memory spike during large listings. We still collect
                    // every line for parsing below — just log a single summary count.
                    rawLines.add(line);
                }
            }
            proc.waitFor();
        } finally {
            listScript.delete();
            try { listLogFile.delete(); } catch (Exception ignored) {}
        }
        logLine.accept("[INFO] Received " + rawLines.size() + " lines from remote listing.");

        java.util.Map<String, Integer> MONTH_MAP = new java.util.HashMap<>();
        MONTH_MAP.put("jan",1); MONTH_MAP.put("feb",2); MONTH_MAP.put("mar",3);
        MONTH_MAP.put("apr",4); MONTH_MAP.put("may",5); MONTH_MAP.put("jun",6);
        MONTH_MAP.put("jul",7); MONTH_MAP.put("aug",8); MONTH_MAP.put("sep",9);
        MONTH_MAP.put("oct",10);MONTH_MAP.put("nov",11);MONTH_MAP.put("dec",12);

        int currentYear = LocalDate.now().getYear();
        java.util.Map<String, RemoteFileStat> fileStats = new java.util.LinkedHashMap<>();

        for (String line : rawLines) {
            String t = line.trim();
            if (t.isEmpty() || !t.startsWith("-")) continue;
            String[] parts = t.split("\\s+", 10);
            if (parts.length < 9) continue;

            long sizeBytes;
            try { sizeBytes = Long.parseLong(parts[4]); } catch (NumberFormatException e) { sizeBytes = 0L; }

            String monthStr   = parts[5].toLowerCase(java.util.Locale.ENGLISH);
            String dayStr     = parts[6].trim();
            String timeOrYear = parts[7];
            String fileName   = parts[8];

            Integer monthNum = MONTH_MAP.get(monthStr);
            if (monthNum == null) continue;
            int dayNum;
            try { dayNum = Integer.parseInt(dayStr); } catch (NumberFormatException e) { continue; }

            // NOTE: these timestamps are parsed as if `ls -l` reported them in this
            // JVM's local zone (java.time.ZoneId.systemDefault()) rather than UTC.
            // That has to match the zone used later to bucket files into day-folders
            // (see enumerateLocalBackupCandidates/enumerateRemoteBackupCandidates,
            // which also use ZoneId.systemDefault()) — otherwise a file near a day
            // boundary gets parsed in one zone and bucketed in another, and ends up
            // filed under the wrong calendar day (e.g. a D-1 file landing in the D
            // folder). If the remote server's clock is known to run in a different
            // zone than this machine, that zone should become a configurable setting
            // here instead of an assumption.
            long epochMillis;
            try {
                if (timeOrYear.contains(":")) {
                    int colons = (int) timeOrYear.chars().filter(c -> c == ':').count();
                    if (colons == 2) {
                        if (parts.length < 10) continue;
                        int year = Integer.parseInt(parts[8]);
                        fileName = parts[9];
                        String[] tp = timeOrYear.split(":");
                        epochMillis = LocalDateTime.of(year, monthNum, dayNum,
                                        Integer.parseInt(tp[0]), Integer.parseInt(tp[1]),
                                        Integer.parseInt(tp[2]))
                                .atZone(java.time.ZoneId.systemDefault())
                                .toInstant().toEpochMilli();
                    } else {
                        String[] tp = timeOrYear.split(":");
                        LocalDateTime ldt = LocalDateTime.of(currentYear, monthNum, dayNum,
                                Integer.parseInt(tp[0]), Integer.parseInt(tp[1]), 0);
                        if (ldt.isAfter(LocalDateTime.now())) ldt = ldt.minusYears(1);
                        epochMillis = ldt.atZone(java.time.ZoneId.systemDefault())
                                .toInstant().toEpochMilli();
                    }
                } else {
                    LocalDate ld = LocalDate.of(Integer.parseInt(timeOrYear), monthNum, dayNum);
                    epochMillis = ld.atStartOfDay(java.time.ZoneId.systemDefault())
                            .toInstant().toEpochMilli();
                }
            } catch (Exception ex) { continue; }

            fileStats.put(dirPath + fileName, new RemoteFileStat(epochMillis, sizeBytes));
        }

        return fileStats;
    }

    private static String remoteListingDirFor(String remotePath) {
        return remotePath.endsWith("*")
                ? remotePath.substring(0, remotePath.lastIndexOf('/') + 1)
                : remotePath.endsWith("/") ? remotePath : remotePath + "/";
    }

    /** Full remote file listing WITH sizes, keyed by full remote path — used for size-based batching. */
    private java.util.Map<String, RemoteFileStat> listAllRemoteFileStatsViaWinScp(
            Credential target, String password, String remotePath,
            Consumer<String> logLine) throws Exception {

        String dirPath = remoteListingDirFor(remotePath);
        return rawRemoteFileStats(target, password, dirPath, logLine);
    }

    // ─── WinSCP process runner ────────────────────────────────────────────────

    private boolean runWinScpScript(File scriptFile, Consumer<String> logLine,
                                    String taskId) throws Exception {
        // Unique per call (derived from the already-unique scriptFile name)
        // rather than the old fixed shared path — required now that batches
        // can run concurrently (AppSettings.getTransferBatchConcurrency() >
        // 1), since two WinSCP processes writing the same log file at once
        // would collide/lock on Windows. Best-effort cleanup afterward.
        File logFile = new File(scriptFile.getAbsolutePath() + ".log");
        ProcessBuilder pb = new ProcessBuilder(
                winScpPath,
                "/script=" + scriptFile.getAbsolutePath(),
                "/log=" + logFile.getAbsolutePath(),
                "/loglevel=1");
        pb.redirectErrorStream(true);
        Process proc = null;
        try {
            proc = pb.start();
            if (taskId != null) {
                activeProcesses.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).add(proc);
            }

            try (BufferedReader br =
                         new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    logLine.accept(maskPasswords(line));
                    if (Thread.currentThread().isInterrupted()) {
                        try { proc.destroyForcibly(); } catch (Exception ignored) {}
                        throw new InterruptedException("Transfer cancelled");
                    }
                }
            }
            int exitCode = proc.waitFor();
            if (exitCode == 0) {
                logLine.accept("[SUCCESS] Transfer completed successfully.");
                return true;
            } else {
                logLine.accept("[ERROR] WinSCP exited with code: " + exitCode);
                return false;
            }
        } catch (InterruptedException ie) {
            logLine.accept("[INFO] Transfer interrupted: " + ie.getMessage());
            if (proc != null) try { proc.destroyForcibly(); } catch (Exception ignored) {}
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (taskId != null) {
                java.util.Set<Process> set = activeProcesses.get(taskId);
                if (set != null) {
                    set.remove(proc);
                    if (set.isEmpty()) activeProcesses.remove(taskId, set);
                }
            }
            try { logFile.delete(); } catch (Exception ignored) {}
        }
    }

    // ─── Batch splitting (any transfer mode, any direction) ──────────────────
    //
    // Batches are SIZE-based (total bytes) rather than file-count based: when
    // a transfer or backup would move more total bytes than the configured
    // cap in one go, it is split into sequential batches, each capped near
    // AppSettings.getTransferBatchMaxBytes(), with a pause of
    // AppSettings.getTransferBatchIntervalSeconds() between batches. A single
    // file larger than the cap is still sent alone in its own batch — it is
    // never split. Values are read live from AppSettings (app-settings.json,
    // seeded from app-config.xml's <transfer> block) — editable from the
    // Settings panel with no restart required.

    /** A WinSCP command line ("put ..."/"get ...") paired with the transferred file's size, for size-based batching. */
    private static final class SizedCommand {
        final String command;
        final long size;
        SizedCommand(String command, long size) { this.command = command; this.size = size; }
    }

    private static <T> List<List<T>> chunkBySize(
            List<T> items, java.util.function.ToLongFunction<T> sizeOf, long maxBytes) {
        List<List<T>> out = new ArrayList<>();
        if (items.isEmpty()) return out;
        if (maxBytes <= 0) maxBytes = Long.MAX_VALUE;
        List<T> current = new ArrayList<>();
        long currentSize = 0;
        for (T item : items) {
            long sz = Math.max(sizeOf.applyAsLong(item), 0);
            if (!current.isEmpty() && currentSize + sz > maxBytes) {
                out.add(current);
                current = new ArrayList<>();
                currentSize = 0;
            }
            current.add(item);
            currentSize += sz;
        }
        if (!current.isEmpty()) out.add(current);
        return out;
    }

    private static List<List<Path>> chunkPathsBySize(List<Path> files) {
        long maxBytes = AppSettings.getTransferBatchMaxBytes();
        return chunkBySize(files, p -> { try { return Files.size(p); } catch (IOException e) { return 0L; } }, maxBytes);
    }

    /**
     * Runs a list of sized WinSCP command lines against one SFTP session per
     * batch, so a large file set is worked through in bounded byte-size
     * chunks instead of one unbounded script, pausing between batches.
     * Continues through remaining batches even if an earlier one fails, so a
     * transient failure partway through a large backlog doesn't block
     * everything after it; overall success requires every batch to have
     * succeeded.
     *
     * <p>By default batches run one at a time (matching the original
     * behavior). If {@link AppSettings#getTransferBatchConcurrency()} is set
     * above 1, up to that many batches run at once — each its own WinSCP/SFTP
     * session — which is the difference between "many small round trips one
     * after another" and "many small round trips in parallel". This matters
     * a lot for backlogs of many small files (e.g. tens of thousands of
     * few-KB files): the byte-size cap alone still packs thousands of files
     * into one "small" batch, and per-file SFTP round-trip latency —
     * not bandwidth — is what dominates wall-clock time in that case.
     * Batches are still processed in size-capped groups ("waves") of up to
     * {@code concurrency} batches; the configured pause happens between
     * waves rather than between every single batch.
     */
    private boolean runBatchedWinScpCommands(Credential target, String password,
            List<SizedCommand> sizedCommands, Consumer<String> logLine, String taskId) throws Exception {

        if (sizedCommands.isEmpty()) {
            logLine.accept("[WARN] Nothing to transfer.");
            return false;
        }

        long maxBytes = AppSettings.getTransferBatchMaxBytes();
        int intervalSeconds = AppSettings.getTransferBatchIntervalSeconds();
        int concurrency = AppSettings.getTransferBatchConcurrency();
        List<List<SizedCommand>> batches = chunkBySize(sizedCommands, sc -> sc.size, maxBytes);

        if (batches.size() > 1) {
            long totalBytes = sizedCommands.stream().mapToLong(sc -> sc.size).sum();
            logLine.accept("[INFO] " + sizedCommands.size() + " file(s), " + humanReadableBytes(totalBytes)
                    + " total — splitting into " + batches.size() + " batch(es), capped at ~"
                    + humanReadableBytes(maxBytes) + " per batch (configurable in Settings)."
                    + (concurrency > 1 ? " Running up to " + concurrency + " batch(es) at a time." : ""));
        }

        if (concurrency <= 1 || batches.size() <= 1) {
            return runBatchesSequentially(target, password, batches, intervalSeconds, logLine, taskId);
        }
        return runBatchesConcurrently(target, password, batches, concurrency, intervalSeconds, logLine, taskId);
    }

    /** Original one-at-a-time behavior — used when concurrency is 1 (default) or there's only one batch. */
    private boolean runBatchesSequentially(Credential target, String password, List<List<SizedCommand>> batches,
            int intervalSeconds, Consumer<String> logLine, String taskId) throws Exception {
        boolean allOk = true;
        for (int i = 0; i < batches.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                logLine.accept("[INFO] Transfer cancelled.");
                return false;
            }
            if (!runOneBatch(target, password, batches.get(i), i, batches.size(), logLine, taskId)) {
                allOk = false;
            }
            if (i < batches.size() - 1 && intervalSeconds > 0) {
                sleepBetweenBatches(intervalSeconds, logLine);
            }
        }
        return allOk;
    }

    /**
     * Runs batches in waves of up to {@code concurrency} at once, each wave
     * on its own thread submitting its own WinSCP process, waiting for the
     * whole wave to finish before pausing (if configured) and starting the
     * next wave. Keeps the existing pause-between-groups behavior while
     * letting each group's SFTP round trips overlap instead of queueing.
     */
    private boolean runBatchesConcurrently(Credential target, String password, List<List<SizedCommand>> batches,
            int concurrency, int intervalSeconds, Consumer<String> logLine, String taskId) throws Exception {
        boolean allOk = true;
        int total = batches.size();
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(Math.min(concurrency, total));
        try {
            for (int waveStart = 0; waveStart < total; waveStart += concurrency) {
                if (Thread.currentThread().isInterrupted()) {
                    logLine.accept("[INFO] Transfer cancelled.");
                    return false;
                }
                int waveEnd = Math.min(waveStart + concurrency, total);
                List<java.util.concurrent.Callable<Boolean>> waveTasks = new ArrayList<>();
                for (int i = waveStart; i < waveEnd; i++) {
                    final int idx = i;
                    waveTasks.add(() -> runOneBatch(target, password, batches.get(idx), idx, total, logLine, taskId));
                }
                List<java.util.concurrent.Future<Boolean>> results;
                try {
                    results = pool.invokeAll(waveTasks);
                } catch (InterruptedException ie) {
                    logLine.accept("[INFO] Transfer cancelled.");
                    Thread.currentThread().interrupt();
                    return false;
                }
                for (java.util.concurrent.Future<Boolean> r : results) {
                    try {
                        if (!Boolean.TRUE.equals(r.get())) allOk = false;
                    } catch (Exception e) {
                        allOk = false;
                    }
                }
                if (waveEnd < total && intervalSeconds > 0) {
                    sleepBetweenBatches(intervalSeconds, logLine);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return allOk;
    }

    /** Builds and runs the WinSCP script for a single batch. Safe to call from multiple threads concurrently. */
    private boolean runOneBatch(Credential target, String password, List<SizedCommand> batch,
            int index, int totalBatches, Consumer<String> logLine, String taskId) {
        if (totalBatches > 1) {
            long batchBytes = batch.stream().mapToLong(sc -> sc.size).sum();
            logLine.accept("[INFO] Batch " + (index + 1) + "/" + totalBatches
                    + " (" + batch.size() + " file(s), " + humanReadableBytes(batchBytes) + ")");
        }
        File scriptFile;
        try {
            scriptFile = File.createTempFile("opstool_batch_", ".txt");
            secureTemp(scriptFile);
            try (PrintWriter pw = new PrintWriter(new FileWriter(scriptFile))) {
                pw.println("option batch abort");
                pw.println("option confirm off");
                pw.println("open sftp://" + escapeUrl(target.getUsername())
                        + ":" + escapeUrl(password) + "@" + target.getHost() + "/ -hostkey=\"*\"");
                for (SizedCommand sc : batch) pw.println(sc.command);
                pw.println("close");
                pw.println("exit");
            }
        } catch (IOException ex) {
            logLine.accept("[ERROR] Batch " + (index + 1) + "/" + totalBatches
                    + " failed to prepare script: " + ex.getMessage());
            return false;
        }
        boolean ok;
        try {
            ok = runWinScpScript(scriptFile, logLine, taskId);
        } catch (Exception ex) {
            logLine.accept("[ERROR] Batch " + (index + 1) + "/" + totalBatches + " failed: " + ex.getMessage());
            ok = false;
        } finally {
            scriptFile.delete();
        }
        if (!ok) {
            logLine.accept("[ERROR] Batch " + (index + 1) + "/" + totalBatches + " failed.");
        }
        return ok;
    }

    /**
     * Non-watcher remote transfer for ENTIRE_FOLDER and LATEST_ONLY modes
     * (both directions): enumerates the files client-side, then transfers
     * them via {@link #runBatchedWinScpCommands}. This replaces the old
     * single WinSCP {@code synchronize} command for ENTIRE_FOLDER — trading
     * synchronize's delta-sync optimization for real per-file batch control,
     * which is what makes batching possible at all (synchronize enumerates
     * remotely inside WinSCP itself, so it can't be split from our side).
     */
    private boolean executeBatchedRemoteTransfer(Credential target, ScheduledTask task,
            Consumer<String> logLine) throws Exception {

        String localPath  = normalizeLocalPath(task.getSourcePath());
        String remotePath = normalizeRemotePath(task.getTargetPath());
        boolean inbound = task.getTransferDirection() == TransferDirection.INBOUND;
        TransferMode mode = task.getTransferMode() != null ? task.getTransferMode() : TransferMode.ENTIRE_FOLDER;

        logLine.accept("[INFO] Target OS: " + target.getOsType() + " | Protocol: SFTP");
        logLine.accept("[INFO] Transfer mode: " + mode.name()
                + " | Direction: " + (inbound ? "INBOUND" : "OUTBOUND"));

        List<SizedCommand> commands = new ArrayList<>();

        if (inbound) {
            java.util.Map<String, RemoteFileStat> remoteStats;
            if (remotePath.endsWith("*") || new File(task.getSourcePath()).isDirectory()
                    || mode == TransferMode.LATEST_ONLY) {
                remoteStats = listAllRemoteFileStatsViaWinScp(target, target.getPassword(), remotePath, logLine);
            } else {
                remoteStats = Collections.emptyMap();
            }

            List<String> remoteFiles;
            if (mode == TransferMode.LATEST_ONLY) {
                logLine.accept("[INFO] Mode: Latest file(s) — non-watcher (INBOUND)");
                if (remoteStats.isEmpty()) {
                    logLine.accept("[ERROR] No files found on remote path: " + remotePath);
                    return false;
                }
                long maxTs = remoteStats.values().stream().mapToLong(s -> s.epochMillis).max().getAsLong();
                remoteFiles = remoteStats.entrySet().stream()
                        .filter(e -> e.getValue().epochMillis == maxTs)
                        .map(java.util.Map.Entry::getKey)
                        .collect(Collectors.toList());
            } else {
                logLine.accept("[INFO] Mode: Entire folder (INBOUND)");
                if (!remoteStats.isEmpty()) {
                    remoteFiles = new ArrayList<>(remoteStats.keySet());
                    Collections.sort(remoteFiles);
                } else if (remotePath.endsWith("*") || new File(task.getSourcePath()).isDirectory()) {
                    logLine.accept("[WARN] No files found on remote path: " + remotePath);
                    return false;
                } else {
                    remoteFiles = Collections.singletonList(remotePath);
                }
            }
            List<String> extraDestFolders = task.getAdditionalTargetPathList();
            if (!extraDestFolders.isEmpty()) {
                logLine.accept("[INFO] Also copying downloaded file(s) to " + extraDestFolders.size()
                        + " additional local destination(s): " + String.join(", ", extraDestFolders));
            }
            List<String> downloadedLocalPaths = new ArrayList<>();
            for (String remoteFile : remoteFiles) {
                String fname    = remoteFile.substring(remoteFile.lastIndexOf('/') + 1);
                String destPath = buildLocalDestinationPath(localPath, fname);
                RemoteFileStat stat = remoteStats.get(remoteFile);
                long size = stat != null ? stat.size : 0L;
                commands.add(new SizedCommand(
                        "get " + escapeWinScpRemotePath(remoteFile) + " " + escapeWinScpPath(destPath), size));
                downloadedLocalPaths.add(destPath);
            }

            boolean ok = runBatchedWinScpCommands(target, target.getPassword(), commands, logLine, task.getId());
            if (ok && !extraDestFolders.isEmpty()) {
                copyDownloadedFilesToExtraFolders(downloadedLocalPaths, extraDestFolders, logLine);
            }
            return ok;
        } else {
            List<File> localFiles;
            File sourceDir = new File(task.getSourcePath());
            if (mode == TransferMode.LATEST_ONLY) {
                logLine.accept("[INFO] Mode: Latest file(s) (OUTBOUND, non-watcher)");
                if (sourceDir.isDirectory()) {
                    File[] files = sourceDir.listFiles(File::isFile);
                    if (files == null || files.length == 0) {
                        logLine.accept("[ERROR] No files found in source folder: " + task.getSourcePath());
                        return false;
                    }
                    long maxTs = Arrays.stream(files).mapToLong(File::lastModified).max().getAsLong();
                    localFiles = Arrays.stream(files).filter(f -> f.lastModified() == maxTs).collect(Collectors.toList());
                    logLine.accept("[INFO] Latest file(s) (timestamp=" + maxTs + ") count=" + localFiles.size());
                } else {
                    localFiles = Collections.singletonList(sourceDir);
                }
            } else {
                logLine.accept("[INFO] Mode: Entire folder (OUTBOUND)");
                if (localPath.endsWith("*") || sourceDir.isDirectory()) {
                    File[] files = sourceDir.listFiles(File::isFile);
                    localFiles = files != null ? Arrays.asList(files) : Collections.emptyList();
                    if (localFiles.isEmpty()) {
                        logLine.accept("[WARN] No files found in source folder: " + task.getSourcePath());
                        return false;
                    }
                } else {
                    localFiles = Collections.singletonList(sourceDir);
                }
            }
            List<String> extraDestFolders = task.getAdditionalTargetPathList();
            if (!extraDestFolders.isEmpty()) {
                logLine.accept("[INFO] Also copying to " + extraDestFolders.size()
                        + " additional destination(s) on the same server: " + String.join(", ", extraDestFolders));
            }
            for (File f : localFiles) {
                String remoteDestPath = prepareRemoteDestination(remotePath, f.getName());
                commands.add(new SizedCommand("put " + escapeWinScpPath(normalizeLocalPath(f.getAbsolutePath()))
                        + " " + escapeWinScpRemotePath(remoteDestPath), f.length()));

                // Copy to every additional destination folder as well (same
                // target server/credential — see ScheduledTask.additionalTargetPaths).
                for (String extraFolder : extraDestFolders) {
                    String extraDestPath = prepareRemoteDestination(normalizeRemotePath(extraFolder), f.getName());
                    commands.add(new SizedCommand("put " + escapeWinScpPath(normalizeLocalPath(f.getAbsolutePath()))
                            + " " + escapeWinScpRemotePath(extraDestPath), f.length()));
                }
            }
        }

        return runBatchedWinScpCommands(target, target.getPassword(), commands, logLine, task.getId());
    }

    /**
     * After a successful INBOUND download, copies each downloaded file from
     * its primary local destination to every additional local destination
     * folder (see {@link model.ScheduledTask#getAdditionalTargetPathList()}).
     * A local file copy, not a second remote download — the file is already
     * on disk after the WinSCP batch completes.
     */
    private void copyDownloadedFilesToExtraFolders(List<String> downloadedLocalPaths,
            List<String> extraDestFolders, Consumer<String> logLine) {
        for (String localPath : downloadedLocalPaths) {
            File source = new File(localPath);
            if (!source.exists()) continue;
            for (String extraFolder : extraDestFolders) {
                try {
                    File destDir = new File(extraFolder);
                    if (!destDir.exists() && !destDir.mkdirs()) {
                        logLine.accept("[WARN] Could not create additional destination folder: " + extraFolder);
                        continue;
                    }
                    Path dest = destDir.toPath().resolve(source.getName());
                    Files.copy(source.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
                    logLine.accept("[INFO] Copied " + source.getName() + " -> " + dest);
                } catch (IOException ex) {
                    logLine.accept("[WARN] Failed to copy " + source.getName()
                            + " to additional destination '" + extraFolder + "': " + ex.getMessage());
                }
            }
        }
    }

    // ─── Exception type ───────────────────────────────────────────────────────

    static class WatcherSkipException extends Exception {
        public WatcherSkipException(String message) { super(message); }
    }


    // ─── Backup (retention-based archiving; local or remote, either side) ────
    //
    // "D" = today. backupRetentionDays files are KEPT in place at the source:
    // D, D-1, ..., D-(retentionDays-1). Everything older is a backup
    // candidate. Unlike previous versions, a run archives EVERY eligible file
    // in one go — there is no more day-bucket/"batch days" limit. A large
    // backlog is instead worked through using the same SIZE-based batching as
    // file transfers (AppSettings.getTransferBatchMaxBytes()), pausing
    // AppSettings.getTransferBatchIntervalSeconds() between batches, so a
    // huge one-time backlog doesn't hammer the link or block for a long
    // unbroken stretch.
    //
    // Either side (source or destination) may be LOCAL or REMOTE:
    //   - LOCAL  side: backupSource/DestinationUsername is blank; the path is
    //     a plain filesystem folder.
    //   - REMOTE side: backupSource/DestinationUsername is set, and the path
    //     is resolved over SFTP using that username's stored credential (the
    //     same creds_<username>.xml mechanism File Transfer tasks use).
    // Both sides being remote at once is not supported (would require a
    // double SFTP hop) and is rejected with a clear error.

    /** A backup-eligible file: its full identity/location, calendar day, and size in bytes. */
    private static final class BackupCandidate {
        final String name;
        final LocalDate day;
        final long size;
        final Path localPath;      // set when source is LOCAL
        final String remotePath;   // set when source is REMOTE (full remote path)
        BackupCandidate(String name, LocalDate day, long size, Path localPath, String remotePath) {
            this.name = name; this.day = day; this.size = size;
            this.localPath = localPath; this.remotePath = remotePath;
        }
    }

    public boolean executeBackup(ScheduledTask task, Consumer<String> logLine) {
        String sourcePathStr = task.getBackupSourcePath();
        String destPathStr   = task.getBackupDestinationPath();

        if (sourcePathStr == null || sourcePathStr.trim().isEmpty()) {
            logLine.accept("[ERROR] Backup source path is not configured.");
            return false;
        }
        if (destPathStr == null || destPathStr.trim().isEmpty()) {
            logLine.accept("[ERROR] Backup destination path is not configured.");
            return false;
        }

        boolean sourceRemote = task.getBackupSourceUsername() != null && !task.getBackupSourceUsername().trim().isEmpty();
        boolean destRemote   = task.getBackupDestinationUsername() != null && !task.getBackupDestinationUsername().trim().isEmpty();

        if (sourceRemote && destRemote) {
            logLine.accept("[ERROR] Backup source and destination cannot both be remote "
                    + "(remote\u2192remote backup is not supported). Make one side local.");
            return false;
        }

        Credential sourceCred = sourceRemote ? resolveNamedCredential(task.getBackupSourceUsername(), "source", logLine) : null;
        if (sourceRemote && sourceCred == null) return false;
        Credential destCred = destRemote ? resolveNamedCredential(task.getBackupDestinationUsername(), "destination", logLine) : null;
        if (destRemote && destCred == null) return false;

        int retentionDays = task.getBackupRetentionDays();
        if (retentionDays < 1) retentionDays = 1;

        LocalDate today = LocalDate.now();
        // Oldest day that is still KEPT in place, e.g. retentionDays=3 → keep D, D-1, D-2.
        LocalDate keepFromDay = today.minusDays(retentionDays - 1);

        logLine.accept("[INFO] Backup source      : " + sourcePathStr + (sourceRemote ? " (REMOTE, " + sourceCred.getHost() + ")" : " (local)"));
        logLine.accept("[INFO] Backup destination : " + destPathStr + (destRemote ? " (REMOTE, " + destCred.getHost() + ")" : " (local)"));
        logLine.accept("[INFO] Today (D)          : " + today);
        logLine.accept("[INFO] Retention          : keep " + retentionDays
                + " day(s) in place (" + keepFromDay + " .. " + today + ")");
        logLine.accept("[INFO] Batching           : entire backlog runs in one go, split into size-based "
                + "batches (~" + humanReadableBytes(AppSettings.getTransferBatchMaxBytes()) + " each, "
                + AppSettings.getTransferBatchIntervalSeconds() + "s between batches).");

        // ── Enumerate backup candidates from the source (local or remote) ─────
        List<BackupCandidate> candidates;
        try {
            candidates = sourceRemote
                    ? enumerateRemoteBackupCandidates(sourceCred, sourcePathStr, keepFromDay, logLine)
                    : enumerateLocalBackupCandidates(sourcePathStr, keepFromDay, logLine);
        } catch (Exception ex) {
            logLine.accept("[ERROR] Failed to list backup source: " + ex.getMessage());
            return false;
        }
        if (candidates == null) return false; // error already logged
        if (candidates.isEmpty()) {
            logLine.accept("[INFO] Nothing to back up — all files fall within the "
                    + retentionDays + "-day retention window.");
            return true;
        }

        long totalBytes = candidates.stream().mapToLong(c -> c.size).sum();
        java.util.Set<LocalDate> days = candidates.stream().map(c -> c.day).collect(Collectors.toCollection(java.util.TreeSet::new));
        logLine.accept("[INFO] " + candidates.size() + " file(s), " + humanReadableBytes(totalBytes)
                + " total, spanning " + days.size() + " day(s) older than the retention window.");

        // ── Move/transfer, in size-based batches ───────────────────────────────
        boolean ok;
        if (!sourceRemote && !destRemote) {
            ok = runLocalToLocalBackup(candidates, destPathStr, logLine);
        } else if (sourceRemote) {
            ok = runRemoteToLocalBackup(task.getId(), sourceCred, candidates, destPathStr, logLine);
        } else {
            ok = runLocalToRemoteBackup(task.getId(), destCred, candidates, destPathStr, logLine);
        }

        logLine.accept((ok ? "[SUCCESS] " : "[WARNING] ") + "Backup run complete.");
        return ok;
    }

    /** Local source: lists regular files older than the retention cutoff, grouped by calendar day. */
    private List<BackupCandidate> enumerateLocalBackupCandidates(
            String sourcePathStr, LocalDate keepFromDay, Consumer<String> logLine) {
        File sourceDir = new File(sourcePathStr);
        if (!sourceDir.isDirectory()) {
            logLine.accept("[ERROR] Backup source folder does not exist: " + sourcePathStr);
            return null;
        }
        File[] rawFiles = sourceDir.listFiles(File::isFile);
        List<BackupCandidate> out = new ArrayList<>();
        if (rawFiles == null) return out;
        for (File f : rawFiles) {
            LocalDate day = Instant.ofEpochMilli(f.lastModified())
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            if (!day.isBefore(keepFromDay)) continue; // within retention window — leave in place
            out.add(new BackupCandidate(f.getName(), day, f.length(), f.toPath(), null));
        }
        return out;
    }

    /** Remote source: lists remote files (over SFTP) older than the retention cutoff, grouped by calendar day. */
    private List<BackupCandidate> enumerateRemoteBackupCandidates(
            Credential sourceCred, String sourcePathStr, LocalDate keepFromDay, Consumer<String> logLine) throws Exception {
        String remotePath = normalizeRemotePath(sourcePathStr);
        String dirPath = remoteListingDirFor(remotePath);
        java.util.Map<String, RemoteFileStat> stats =
                rawRemoteFileStats(sourceCred, sourceCred.getPassword(), dirPath, logLine);
        List<BackupCandidate> out = new ArrayList<>();
        for (java.util.Map.Entry<String, RemoteFileStat> e : stats.entrySet()) {
            String fullPath = e.getKey();
            RemoteFileStat stat = e.getValue();
            LocalDate day = Instant.ofEpochMilli(stat.epochMillis)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            if (!day.isBefore(keepFromDay)) continue;
            String fname = fullPath.substring(fullPath.lastIndexOf('/') + 1);
            out.add(new BackupCandidate(fname, day, stat.size, null, fullPath));
        }
        return out;
    }

    /** Local→local backup: moves files into {@code <destRoot>/<yyyy-MM-dd>/}, in size-based batches. */
    private boolean runLocalToLocalBackup(List<BackupCandidate> candidates, String destPathStr, Consumer<String> logLine) {
        File destRoot = new File(destPathStr);
        if (!destRoot.exists() && !destRoot.mkdirs()) {
            logLine.accept("[ERROR] Cannot create backup destination folder: " + destPathStr);
            return false;
        }
        List<List<BackupCandidate>> batches = chunkBySize(candidates, c -> c.size, AppSettings.getTransferBatchMaxBytes());
        int intervalSeconds = AppSettings.getTransferBatchIntervalSeconds();
        if (batches.size() > 1) {
            logLine.accept("[INFO] Splitting into " + batches.size() + " batch(es).");
        }
        boolean allOk = true;
        int moved = 0;
        for (int i = 0; i < batches.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                logLine.accept("[INFO] Backup cancelled.");
                return false;
            }
            List<BackupCandidate> batch = batches.get(i);
            if (batches.size() > 1) {
                logLine.accept("[INFO] Batch " + (i + 1) + "/" + batches.size() + " (" + batch.size() + " file(s))");
            }
            for (BackupCandidate c : batch) {
                File dayDestDir = new File(destRoot, c.day.toString());
                if (!dayDestDir.exists() && !dayDestDir.mkdirs()) {
                    logLine.accept("[ERROR] Cannot create backup folder for " + c.day + ": " + dayDestDir);
                    allOk = false;
                    continue;
                }
                boolean ok = moveSingleFile(c.localPath, dayDestDir.toPath().resolve(c.name), logLine);
                allOk &= ok;
                if (ok) moved++;
            }
            if (i < batches.size() - 1 && intervalSeconds > 0) sleepBetweenBatches(intervalSeconds, logLine);
        }
        logLine.accept("[INFO] " + moved + " file(s) archived.");
        return allOk;
    }

    /** Remote source → local destination: pulls files via WinSCP `get` into {@code <destRoot>/<yyyy-MM-dd>/}, in size-based batches. */
    private boolean runRemoteToLocalBackup(String taskId, Credential sourceCred, List<BackupCandidate> candidates,
            String destPathStr, Consumer<String> logLine) {
        File destRoot = new File(destPathStr);
        if (!destRoot.exists() && !destRoot.mkdirs()) {
            logLine.accept("[ERROR] Cannot create backup destination folder: " + destPathStr);
            return false;
        }
        List<SizedCommand> commands = new ArrayList<>();
        java.util.Map<String, LocalDate> dayByRemotePath = new java.util.HashMap<>();
        for (BackupCandidate c : candidates) {
            File dayDestDir = new File(destRoot, c.day.toString());
            if (!dayDestDir.exists() && !dayDestDir.mkdirs()) {
                logLine.accept("[ERROR] Cannot create backup folder for " + c.day + ": " + dayDestDir);
                continue;
            }
            String destPath = normalizeLocalPath(new File(dayDestDir, c.name).getAbsolutePath());
            commands.add(new SizedCommand(
                    "get " + escapeWinScpRemotePath(c.remotePath) + " " + escapeWinScpPath(destPath), c.size));
        }
        try {
            boolean ok = runBatchedWinScpCommands(sourceCred, sourceCred.getPassword(), commands, logLine, taskId);
            if (ok) {
                // Remove originals from the remote source now that they've landed locally.
                deleteRemoteFilesViaWinScp(sourceCred, candidates.stream().map(c -> c.remotePath).collect(Collectors.toList()), logLine);
            }
            return ok;
        } catch (Exception ex) {
            logLine.accept("[ERROR] Remote\u2192local backup failed: " + ex.getMessage());
            return false;
        }
    }

    /** Local source → remote destination: pushes files via WinSCP `put` into {@code <destRoot>/<yyyy-MM-dd>/}, in size-based batches. */
    private boolean runLocalToRemoteBackup(String taskId, Credential destCred, List<BackupCandidate> candidates,
            String destPathStr, Consumer<String> logLine) {
        String remoteRoot = normalizeRemotePath(destPathStr);
        String remoteRootDir = remoteRoot.endsWith("/") ? remoteRoot : remoteRoot + "/";

        // One mkdir per distinct day, de-duplicated, run best-effort first (a day
        // folder that already exists from a previous run is not an error).
        java.util.LinkedHashSet<String> mkdirCmds = new java.util.LinkedHashSet<>();
        for (BackupCandidate c : candidates) {
            mkdirCmds.add("mkdir " + escapeWinScpRemotePath((remoteRootDir + c.day)));
        }

        try {
            boolean mkdirOk = runBestEffortWinScpCommands(destCred, mkdirCmds, logLine);
            if (!mkdirOk) {
                logLine.accept("[WARN] Some remote day folders may not have been created (they may already exist) — continuing.");
            }
            List<SizedCommand> putCommands = candidates.stream().map(c -> new SizedCommand(
                    "put " + escapeWinScpPath(normalizeLocalPath(c.localPath.toAbsolutePath().toString()))
                            + " " + escapeWinScpRemotePath(remoteRootDir + c.day + "/" + c.name), c.size))
                    .collect(Collectors.toList());
            boolean ok = runBatchedWinScpCommands(destCred, destCred.getPassword(), putCommands, logLine, taskId);
            if (ok) {
                for (BackupCandidate c : candidates) {
                    try {
                        Files.delete(c.localPath);
                    } catch (IOException ex) {
                        logLine.accept("[WARN] Uploaded but could not remove local original " + c.name + ": " + ex.getMessage());
                    }
                }
            }
            return ok;
        } catch (Exception ex) {
            logLine.accept("[ERROR] Local\u2192remote backup failed: " + ex.getMessage());
            return false;
        }
    }

    /** Runs a small set of WinSCP commands (e.g. mkdir) in one session, tolerating individual failures. */
    private boolean runBestEffortWinScpCommands(Credential target, java.util.Collection<String> cmds, Consumer<String> logLine) throws Exception {
        if (cmds.isEmpty()) return true;
        File scriptFile = File.createTempFile("opstool_mkdir_", ".txt");
        secureTemp(scriptFile);
        try (PrintWriter pw = new PrintWriter(new FileWriter(scriptFile))) {
            pw.println("option batch continue"); // don't abort the whole script if a single mkdir fails (dir exists)
            pw.println("option confirm off");
            pw.println("open sftp://" + escapeUrl(target.getUsername())
                    + ":" + escapeUrl(target.getPassword()) + "@" + target.getHost() + "/ -hostkey=\"*\"");
            for (String c : cmds) pw.println(c);
            pw.println("close");
            pw.println("exit");
        }
        try {
            return runWinScpScript(scriptFile, logLine, "backup-mkdir");
        } finally {
            scriptFile.delete();
        }
    }

    /** Deletes a batch of remote files (after a successful remote→local backup pull). */
    private void deleteRemoteFilesViaWinScp(Credential target, List<String> remotePaths, Consumer<String> logLine) {
        if (remotePaths.isEmpty()) return;
        try {
            File scriptFile = File.createTempFile("opstool_rm_", ".txt");
            secureTemp(scriptFile);
            try (PrintWriter pw = new PrintWriter(new FileWriter(scriptFile))) {
                pw.println("option batch continue");
                pw.println("option confirm off");
                pw.println("open sftp://" + escapeUrl(target.getUsername())
                        + ":" + escapeUrl(target.getPassword()) + "@" + target.getHost() + "/ -hostkey=\"*\"");
                for (String p : remotePaths) pw.println("rm " + escapeWinScpRemotePath(p));
                pw.println("close");
                pw.println("exit");
            }
            try {
                runWinScpScript(scriptFile, logLine, "backup-cleanup");
            } finally {
                scriptFile.delete();
            }
        } catch (Exception ex) {
            logLine.accept("[WARN] Backed up but could not remove some remote originals: " + ex.getMessage());
        }
    }

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
        try {
//            logLine.accept("[DEBUG] OAuth token cache directory: "
//                    + OAuth2TokenService.sharedTokenDir(storage.getDataDir()).toAbsolutePath());
            accessToken = oauthService.getValidAccessToken(mailbox, tenantId, clientId, GRAPH_MAIL_SCOPE);
        } catch (IOException ex) {
            logLine.accept("[ERROR] OAuth2 token refresh failed: " + ex.getMessage());
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

        List<GraphMailService.MailMessage> messages;
        try {
            messages = graphMailService.fetchMessages(
                    accessToken, folder, criteria, mode, maxResults, afterEpoch, logLine);
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

        boolean markRead = task.isMailMarkAsRead();

        long newestEpoch = 0L;
        for (GraphMailService.MailMessage m : messages) {
            logLine.accept("[MAIL MESSAGE] " + m.subject + " | from=" + m.from
                    + " | received=" + m.receivedDateTime);
            if (m.bodyContent == null || m.bodyContent.isEmpty()) {
                logLine.accept("[WARN] No payload returned for message id=" + m.id);
            } else {
                for (String ln : m.bodyContent.split("\r?\n")) logLine.accept(ln);
            }
            logLine.accept("[END MAIL MESSAGE]");

            // Classify once, before naming the file, and reuse for the filename,
            // attachment placement, and the mailbox folder move below, so all
            // three stay consistent.
            String classification = classifyForFolderRouting(m);

            try {
                Path rcvFile = uniqueRcvPath(outputDir, buildRcvFileName(m, classification));
                Files.write(rcvFile, buildRcvFileContent(m, mode, logLine).getBytes(StandardCharsets.UTF_8));
                logLine.accept("[INFO] Wrote " + rcvFile.getFileName());
            } catch (IOException ex) {
                logLine.accept("[WARN] Failed to write .RCV file for '" + m.subject + "': " + ex.getMessage());
            }

            saveAttachmentsToDisk(m, outputDir, classification, logLine);

            try {
                long ts = Instant.parse(m.receivedDateTime).toEpochMilli();
                if (ts > newestEpoch) newestEpoch = ts;
            } catch (Exception ignored) {
                // Unparseable timestamp — skip baseline consideration for this message only.
            }

            // Mark-as-read must happen before move — moving invalidates the message ID.
            if (markRead) {
                try {
                    graphMailService.markAsRead(accessToken, m.id);
                    logLine.accept("[INFO] Marked as read: " + m.subject);
                } catch (Exception ex) {
                    logLine.accept("[WARN] Failed to mark message as read (" + m.subject + "): " + ex.getMessage());
                }
            }

            // Manual "move to a configured folder" is gone — every processed
            // message is now routed automatically by content: LDM/PTM
            // messages go to their respective configured folder, anything
            // else goes to the configured "Others" folder.
            String destinationFolder = resolveMoveFolderName(classification);
            try {
                graphMailService.moveMessage(accessToken, m.id, destinationFolder, logLine);
                logLine.accept("[INFO] Moved to folder '" + destinationFolder + "': " + m.subject);
            } catch (Exception ex) {
                logLine.accept("[WARN] Failed to move message (" + m.subject + "): " + ex.getMessage());
            }
        }

        if (watcherOn && newestEpoch > 0) {
            task.setMailLastKnownEpoch(newestEpoch);
            storage.saveTask(task);
            String readable = Instant.ofEpochMilli(newestEpoch)
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            logLine.accept("[INFO] Mail watcher baseline updated \u2192 epoch=" + newestEpoch
                    + " (" + readable + " local) | task saved, ID=" + task.getId());
        }

        return true;
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
    private void saveAttachmentsToDisk(GraphMailService.MailMessage m, Path outputDir,
                                        String classification, Consumer<String> logLine) {
        if (m.attachmentFiles == null || m.attachmentFiles.isEmpty()) return;

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
            return;
        }

        for (GraphMailService.AttachmentFile file : m.attachmentFiles) {
            String safeName = (file.name == null || file.name.trim().isEmpty() ? "attachment" : file.name.trim())
                    .replaceAll("[\\\\/:*?\"<>|]", "_");
            Path dest = attachmentsDir.resolve(safeName);
            // Guard against two attachments on the SAME message sharing a name.
            int suffix = 1;
            while (Files.exists(dest)) {
                int extDot = safeName.lastIndexOf('.');
                String base = extDot > 0 ? safeName.substring(0, extDot) : safeName;
                String ext  = extDot > 0 ? safeName.substring(extDot) : "";
                dest = attachmentsDir.resolve(base + "_" + (suffix++) + ext);
            }
            try {
                Files.write(dest, file.bytes);
                logLine.accept("[INFO] Saved attachment '" + file.name + "' -> " + dest);
            } catch (IOException ex) {
                logLine.accept("[WARN] Failed to save attachment '" + file.name + "': " + ex.getMessage());
            }
        }
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
        String sourceText = useAttachment ? m.attachmentText : toPlainText(m.bodyContent, m.bodyType);

        if (mode == MailFetchMode.BODY_ONLY) {
            String bannerStripped = removeAutomatedBanners(sourceText);
            MarkerMatch marker = findMessageTypeMarker(bannerStripped);
            String markerBody = marker != null ? bannerStripped.substring(marker.index) : bannerStripped;

            // For LDM/PTM, the literal marker is immediately followed by an
            // "=TEXT" tag line marking the start of the free-text content,
            // matching the =FIELD convention used by the rest of the header.
            if (marker != null && ("LDM".equals(marker.type) || "PTM".equals(marker.type))) {
                markerBody = marker.type + "\n=TEXT" + markerBody.substring(marker.type.length());
            }

            String messageBody = removeBlankLines(stripSignatureAndQuotedContent(markerBody));

            if (marker == null) {
                // Not an LDM/MVT/PTM message — no SITA header applies.
                return messageBody;
            }

            String originCode = null;
            String destinationCode = null;
            if ("LDM".equals(marker.type)) {
                originCode = extractLdmOriginCode(bannerStripped, messageBody);
                destinationCode = extractLdmDestinationCode(messageBody);
            } else if ("PTM".equals(marker.type)) {
                String[] od = extractPtmOriginDestination(messageBody);
                originCode = od[0];
                destinationCode = od[1];
            } else {
                // No extraction rule has been specified yet for MVT —
                // the configured default address is used for both fields.
                logLine.accept("[INFO] No origin/destination extraction rule configured for " + marker.type
                        + " messages yet — falling back to the configured default SITA address.");
            }

            String originAddress = resolveStationAddress(originCode, marker.type, "ORIGIN", logLine);
            String destinationAddress = resolveStationAddress(destinationCode, marker.type, "DESTINATION TYPE B", logLine);

            String header = buildSitaHeaderBlock(marker.type, originAddress, destinationAddress, nextMessageId());
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
        sb.append(removeBlankLines(sourceText));
        return sb.toString();
    }

    // ─── SITA-style header block (LDM / MVT / PTM) ─────────────────────────

    private static final DateTimeFormatter RCV_HEADER_DATE_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    /**
     * Builds the fixed-format routing header prepended to LDM/MVT/PTM
     * message content:
     *   =HEADER
     *   RCV,<yyyy/MM/dd HH:mm>
     *   =PRIORITY
     *   QU (LDM/MVT) or QN (PTM)
     *   =DESTINATION TYPE B
     *   STX,<destination SITA address>
     *   =ORIGIN
     *   <origin SITA address>
     *   =MSGID
     *   <5-digit sequential id>
     *   =SMI
     * The template is identical across all three message types — only the
     * field values differ. Missing origin/destination addresses (extraction
     * or lookup failure) are left blank rather than guessed; a WARN is
     * already logged by the caller in that case.
     */
    private String buildSitaHeaderBlock(String messageType, String originAddress,
                                         String destinationAddress, String msgId) {
        StringBuilder sb = new StringBuilder();
        sb.append("=HEADER\n");
        sb.append("RCV,").append(LocalDateTime.now().format(RCV_HEADER_DATE_FMT)).append("\n");
        sb.append("=PRIORITY\n");
        sb.append("PTM".equals(messageType) ? "QN" : "QU").append("\n");
        sb.append("=DESTINATION TYPE B\n");
        sb.append("STX,").append(destinationAddress != null ? destinationAddress : "").append("\n");
        sb.append("=ORIGIN\n");
        sb.append(originAddress != null ? originAddress : "").append("\n");
        sb.append("=MSGID\n");
        sb.append(msgId).append("\n");
        sb.append("=SMI\n");
        return sb.toString();
    }

    // ─── LDM origin / destination extraction ───────────────────────────────

    // Second line of the raw message packet: a dot, a 7-letter SITA address
    // (3-letter station + 4-letter org code), a space, then a 6-digit
    // date-time group. Captures the 3-letter station code.
    private static final Pattern LDM_ORIGIN_SECOND_LINE =
            Pattern.compile("^\\.([A-Z]{3})[A-Z]{4}\\s+\\d{6}");
    // An isolated line of exactly 3 uppercase letters immediately followed by
    // a baggage (B) or cargo (C) distribution line starting with a dot.
    private static final Pattern LDM_ORIGIN_BEFORE_DISTRIBUTION =
            Pattern.compile("^([A-Z]{3})\\s*$\\R^(?:B|C)\\s+\\.", Pattern.MULTILINE);
    // First run of 3 capital letters right after a '-' on the same line.
    private static final Pattern LDM_DESTINATION_AFTER_DASH =
            Pattern.compile("-\\s*([A-Z]{3})");

    /**
     * Origin station code for an LDM message. Tries the "second line of the
     * packet" SITA-address convention first (against the untouched, pre-
     * truncation body, since that addressing line precedes the LDM marker);
     * falls back to the "isolated line before a B/C distribution line"
     * convention (against the LDM-truncated body) if the first doesn't match.
     */
    private String extractLdmOriginCode(String fullBodyText, String ldmBody) {
        if (fullBodyText != null) {
            String[] lines = fullBodyText.split("\n", -1);
            if (lines.length > 1) {
                Matcher m = LDM_ORIGIN_SECOND_LINE.matcher(lines[1].trim());
                if (m.find()) return m.group(1);
            }
        }
        if (ldmBody != null) {
            Matcher m = LDM_ORIGIN_BEFORE_DISTRIBUTION.matcher(ldmBody);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /**
     * Destination Type B station code for an LDM message: only the first
     * line containing a '-' is checked (per spec) — if that line's dash
     * isn't immediately followed by 3 capital letters, extraction fails
     * rather than scanning further lines.
     */
    private String extractLdmDestinationCode(String ldmBody) {
        if (ldmBody == null) return null;
        for (String line : ldmBody.split("\n", -1)) {
            int dash = line.indexOf('-');
            if (dash < 0) continue;
            Matcher m = LDM_DESTINATION_AFTER_DASH.matcher(line.substring(dash));
            return m.find() ? m.group(1) : null;
        }
        return null;
    }

    // ─── PTM origin / destination extraction ───────────────────────────────

    // Flight designator + departure date line, e.g. "SV753/06AUG HYDJED":
    // 2-3 char airline code + 1-4 digit flight number, "/", 2-digit day +
    // 3-letter month, then a run of 6 capital letters split into two 3-letter
    // station codes — origin (where these passengers boarded) then the
    // immediate/transit destination (where they disembark).
    private static final Pattern PTM_FLIGHT_ORIGIN_DEST =
            Pattern.compile("^[A-Z0-9]{2,3}\\d{1,4}/\\d{2}[A-Z]{3}\\s+([A-Z]{3})([A-Z]{3})", Pattern.MULTILINE);

    /** Returns {origin, destination} station codes for a PTM message, or {null, null} if not found. */
    private String[] extractPtmOriginDestination(String ptmBody) {
        if (ptmBody != null) {
            Matcher m = PTM_FLIGHT_ORIGIN_DEST.matcher(ptmBody);
            if (m.find()) return new String[]{m.group(1), m.group(2)};
        }
        return new String[]{null, null};
    }

    // ─── Station code → full SITA address lookup (app-config.xml / JSON) ──

    private Map<String, Object> stationCodeCache;
    private long stationCodeCacheFileMtime = -1;
    // (defaultStationAddress is no longer cached here — AppSettings.getDefaultStationAddress()
    // already does its own cheap mtime-checked caching and stays live-editable.)

    /**
     * Loads and caches the station-code → 7-character SITA address map from
     * the JSON file whose path is configured in app-config.xml under
     * <sitaMessaging><stationCodesFile>. The cache is invalidated whenever
     * the file's lastModified timestamp changes, so editing the station
     * codes JSON (by hand, or via a future dedicated editor) takes effect on
     * the next message processed — no restart needed, same as the other
     * live settings in {@link AppSettings}. Missing config/file/JSON just
     * yields an empty map (best-effort — lookups then fail through to the
     * configured default rather than the app crashing).
     */
    private synchronized Map<String, Object> loadStationCodes() {
        String path = readAppConfigValue("stationCodesFile");
        if (path == null || path.isEmpty()) return stationCodeCache != null ? stationCodeCache : Collections.emptyMap();
        File f = new File(path);
        long mtime = f.exists() ? f.lastModified() : 0L;
        if (stationCodeCache != null && mtime == stationCodeCacheFileMtime) return stationCodeCache;

        Map<String, Object> loaded = new HashMap<>();
        try {
            if (f.exists()) {
                String json = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                Map<String, Object> parsed = MiniJson.parseObject(json);
                if (parsed != null) loaded.putAll(parsed);
            }
        } catch (Exception ignored) {
            // best-effort; invalid JSON just means codes won't expand this read
        }
        stationCodeCache = loaded;
        stationCodeCacheFileMtime = mtime;
        return stationCodeCache;
    }

    /** Reads a single top-level-named element's text from app-config.xml. */
    private String readAppConfigValue(String tagName) {
        // Delegates to util.AppConfig, which resolves the file next to the
        // running JAR as a fallback when it's not found relative to the
        // process's working directory — a plain new File("app-config.xml")
        // here silently returned null (and therefore no station-code lookup
        // and no default fallback) whenever the process wasn't launched with
        // the install directory as its working directory.
        return AppConfig.readValue(tagName);
    }

    private String getDefaultStationAddress() {
        return AppSettings.getDefaultStationAddress();
    }

    /**
     * Resolves a station code to its full SITA address via the JSON lookup,
     * falling back to the <sitaMessaging><defaultStationAddress> configured
     * in app-config.xml when the code couldn't be extracted from the message
     * at all, or couldn't be found in the lookup — so =ORIGIN and
     * =DESTINATION TYPE B are never left blank as long as a default is set.
     */
    private String resolveStationAddress(String code, String messageType, String fieldName, Consumer<String> logLine) {
        if (code != null) {
            Object v = loadStationCodes().get(code);
            if (v != null) return v.toString();
            logLine.accept("[WARN] " + messageType + ": no SITA address configured for station code '" + code
                    + "' (needed for =" + fieldName + ") — check stationCodesFile in app-config.xml.");
        }
        String fallback = getDefaultStationAddress();
        if (fallback != null && !fallback.isEmpty()) {
            logLine.accept("[INFO] " + messageType + ": using configured default SITA address '" + fallback
                    + "' for =" + fieldName + ".");
            return fallback;
        }
        return null;
    }

    // ─── Sequential 5-digit message id ──────────────────────────────────────

    private final Object msgIdLock = new Object();

    /**
     * Persistent, sequential 5-digit =MSGID, shared across all LDM/MVT/PTM
     * messages regardless of task. Stored as a plain counter file in the
     * app's data directory so it survives restarts; wraps from 99999 back to
     * 00001. Best-effort: if the counter file can't be read/written, a value
     * is still returned for the current message rather than failing the run.
     */
    private String nextMessageId() {
        synchronized (msgIdLock) {
            File counterFile = new File(storage.getDataDir(), "msgid-counter.txt");
            int next = 1;
            try {
                if (counterFile.exists()) {
                    String s = Files.readString(counterFile.toPath(), StandardCharsets.UTF_8).trim();
                    int current = Integer.parseInt(s);
                    next = (current % 99999) + 1;
                }
            } catch (Exception ignored) {
                next = 1;
            }
            try {
                Files.writeString(counterFile.toPath(), String.valueOf(next), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // best-effort persistence; still return a value for this run
            }
            return String.format("%05d", next);
        }
    }

    /**
     * Drops every blank/whitespace-only line so the .RCV file has no gaps
     * between lines of actual content — HTML paragraph/line breaks and the
     * earlier cleanup passes leave behind blank lines (collapsed to at most
     * one), which this removes entirely. Also strips any leading
     * whitespace (spaces/tabs) from the start of each remaining line —
     * HTML-to-plain-text conversion and quoted/indented content upstream
     * can leave lines indented, which this normalizes back to column 0.
     * Trailing whitespace is left alone (not part of this request).
     *
     * <p>Only used for BODY_ONLY/HEADERS_AND_BODY content — never applied
     * to FULL_MESSAGE (raw MIME), where leading whitespace is sometimes
     * structurally significant (RFC 2822 header folding).
     */
    private String removeBlankLines(String text) {
        if (text == null || text.isEmpty()) return "";
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String stripped = stripLeadingWhitespace(line);
            if (sb.length() > 0) sb.append("\n");
            sb.append(stripped);
        }
        return sb.toString();
    }

    /** Removes only leading spaces/tabs from a single line, leaving the rest of the line untouched. */
    private static String stripLeadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return line.substring(i);
    }

    /**
     * Converts a Graph message body to plain text. Graph returns bodyType
     * "html" for the vast majority of real-world mail — writing that raw
     * markup into a .RCV file isn't usable as "body text", so block-level
     * tags are turned into line breaks, all remaining tags are stripped, and
     * the handful of entities that actually show up in mail are decoded.
     * bodyType "text" is returned as-is.
     */
    private String toPlainText(String content, String bodyType) {
        if (content == null) return "";
        if (!"html".equalsIgnoreCase(bodyType)) return content;

        String html = content;
        html = html.replaceAll("(?is)<(script|style|head)[^>]*>.*?</\\1>", "");
        html = html.replaceAll("(?i)<br\\s*/?>", "\n");
        html = html.replaceAll("(?i)</(p|div|tr|li|h[1-6])>", "\n");
        html = html.replaceAll("(?i)<(p|div|li)[^>]*>", "\n");
        html = html.replaceAll("(?s)<[^>]+>", "");
        html = html.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
        html = html.replaceAll("[ \\t]+\\n", "\n");
        html = html.replaceAll("\\n{3,}", "\n\n");
        return html.trim();
    }

    /**
     * Strips automated banners that mail gateways/clients inject into the
     * body — Outlook's "first contact" safety tip and generic external-sender
     * warnings — since they're not part of the actual message. Applied
     * wherever the banner appears in the text, not just at the start, since
     * some gateways insert it inline rather than as a strict prefix.
     */
    private String removeAutomatedBanners(String text) {
        if (text == null || text.isEmpty()) return "";
        String result = text;
        for (Pattern p : BANNER_REMOVAL_PATTERNS) {
            result = p.matcher(result).replaceAll("");
        }
        return result.replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static final Pattern[] BANNER_REMOVAL_PATTERNS = new Pattern[] {
            // Outlook's "You don't often get email from X. Learn why this is
            // important" safety tip — X varies per message, so match the fixed
            // wrapper text around it rather than a literal sender.
            Pattern.compile("(?is)you don't often get email from .*?learn why this is important\\.?"),
            // "** EXTERNAL EMAIL - Please verify the sender ... **" style banners.
            Pattern.compile("(?is)\\*\\*\\s*external email\\s*-.*?\\*\\*"),
            // Common Office 365 default external-sender banner text.
            Pattern.compile("(?is)this message originated from outside your organization\\.?\\s*"
                    + "(do not click links or open attachments unless you recognize the sender[^.\\n]*\\.)?"),
            // "[EXTERNAL]" tag some gateways prepend to the subject/body.
            Pattern.compile("(?i)^\\s*\\[external]\\s*", Pattern.MULTILINE),
            Pattern.compile("(?i)^caution:\\s*this email originated from outside.*$", Pattern.MULTILINE),
    };

    /**
     * Company convention for these mailboxes: the substantive message content
     * begins at a literal "LDM" / "MVT" / "PTM" marker in the body — whichever
     * one starts the actual message — and everything before it (banners,
     * greetings, etc.) is boilerplate that gets discarded. The marker found
     * also identifies the message type, which drives the =PRIORITY value and
     * whether/how origin+destination are extracted. If none of the three
     * markers are present, no marker is returned and the text is left as-is
     * (not an LDM/MVT/PTM message, so no SITA header is generated for it).
     */
    private static final String[] MESSAGE_TYPE_MARKERS = {"LDM", "MVT", "PTM"};

    private static class MarkerMatch {
        final String type;
        final int index;
        MarkerMatch(String type, int index) { this.type = type; this.index = index; }
    }

    private MarkerMatch findMessageTypeMarker(String text) {
        if (text == null) return null;
        MarkerMatch best = null;
        for (String type : MESSAGE_TYPE_MARKERS) {
            int idx = text.indexOf(type);
            if (idx >= 0 && (best == null || idx < best.index)) {
                best = new MarkerMatch(type, idx);
            }
        }
        return best;
    }

    /**
     * Classifies a message for automatic post-processing folder routing:
     * checks Subject, attachment text, and body TOGETHER, case-insensitively,
     * against every configured rule's key (see Settings → Message Routing),
     * in the order those rules are listed — so if a message could match more
     * than one key, whichever rule is listed first wins. The "Others"
     * fallback rule's key is never matched against (it's the catch-all, not
     * a marker to search for).
     *
     * Deliberately separate from findMessageTypeMarker (used for the SITA
     * header): that one is a strict, single-source, case-sensitive match
     * against the structured message content, because getting the header
     * wrong has real consequences. This one is a looser "does this message
     * relate to this classification at all" check across everywhere the
     * marker could appear, since misrouting a message to the wrong folder
     * because of a stray lowercase letter or because the word only appeared
     * in the subject is the actual reported problem.
     *
     * @return the matching rule's key (e.g. "LDM", "PTM", or any custom key
     *         configured in Settings), or {@code null} if nothing matched
     *         (routes to the "Others" folder).
     */
    private String classifyForFolderRouting(GraphMailService.MailMessage m) {
        String subject = m.subject != null ? m.subject : "";
        String attachment = m.attachmentText != null ? m.attachmentText : "";
        String body = toPlainText(m.bodyContent, m.bodyType);

        String combined = (subject + "\n" + attachment + "\n" + body).toUpperCase(Locale.ROOT);
        for (util.MailRoutingRule rule : AppSettings.getMailRoutingRules()) {
            if (rule.isOthers()) continue;
            String key = rule.getKey();
            if (key != null && !key.trim().isEmpty() && combined.contains(key.trim().toUpperCase(Locale.ROOT))) {
                return key.trim();
            }
        }
        return null;
    }

    /**
     * Resolves the destination folder name for automatic post-processing
     * move: looks up the folder configured for this classification (LDM,
     * PTM, or any custom key added in Settings), falling back to the
     * "Others" rule's folder if nothing matches. Folder names come from the
     * live app-settings database (see {@link AppSettings#getMailRoutingRules()})
     * so a change made in the Settings panel takes effect on the very next
     * message processed — no restart needed.
     */
    private String resolveMoveFolderName(String messageType) {
        return AppSettings.resolveRoutingFolder(messageType);
    }

    // Lines matching any of these mark the start of a signature, footer/
    // disclaimer, or quoted reply history — everything from the first match
    // onward is dropped. Best-effort: real-world mail signature formats
    // vary too much to catch every case, but this covers the common ones
    // (Outlook/Gmail/mobile clients, RFC 3676 "-- " delimiter, disclaimers).
    private static final Pattern[] BODY_CUTOFF_PATTERNS = new Pattern[] {
            Pattern.compile("^-- ?$"),
            Pattern.compile("^_{5,}$"),
            Pattern.compile("(?i)^-{3,}\\s*original message\\s*-{3,}$"),
            Pattern.compile("(?i)^on .{5,120} wrote:\\s*$"),
            Pattern.compile("^from:\\s+\\S.*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^>.*$"),
            Pattern.compile("(?i)^sent from my (iphone|android|mobile|samsung|ipad).*$"),
            Pattern.compile("(?i)^get outlook for (ios|android).*$"),
            Pattern.compile("(?i)^(confidentiality notice|disclaimer)\\b.*$"),
            // Common sign-off lines — "Thanks & Regards", "Best Regards,", etc.
            // Anchored to a line containing (at most) a short trailing name/
            // title so it doesn't accidentally match the phrase mid-sentence.
            Pattern.compile("(?i)^thanks\\s*(&|and)\\s*regards\\s*,?\\s*$"),
            Pattern.compile("(?i)^(best|warm|kind|many)\\s+regards\\s*,?\\s*$"),
            Pattern.compile("(?i)^regards\\s*,?\\s*$"),
            Pattern.compile("(?i)^(thank\\s*you|thanks)\\s*,?\\s*$"),
            Pattern.compile("(?i)^(best|sincerely|cheers|respectfully)\\s*,?\\s*$"),
            Pattern.compile("(?i)^this (e-?mail|message)( and any (attachments|files))?"
                    + " (is|are|contains|may contain).*confidential.*$"),
    };

    private String stripSignatureAndQuotedContent(String text) {
        if (text == null || text.isEmpty()) return "";
        String[] lines = text.split("\n", -1);
        int cutoff = lines.length;

        outer:
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            for (Pattern p : BODY_CUTOFF_PATTERNS) {
                if (!p.matcher(line).matches()) continue;
                // A bare "From:" line is ambiguous on its own (could be body text
                // someone typed) — only treat it as a quoted-reply header block
                // when a Sent:/Date: line follows shortly after, matching the
                // Outlook "From: / Sent: / To: / Subject:" quote-block pattern.
                if (line.toLowerCase().startsWith("from:")) {
                    boolean looksLikeQuoteBlock = false;
                    for (int j = i + 1; j < Math.min(i + 4, lines.length); j++) {
                        String next = lines[j].trim().toLowerCase();
                        if (next.startsWith("sent:") || next.startsWith("date:")) {
                            looksLikeQuoteBlock = true;
                            break;
                        }
                    }
                    if (!looksLikeQuoteBlock) continue;
                }
                cutoff = i;
                break outer;
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cutoff; i++) {
            sb.append(lines[i]);
            if (i < cutoff - 1) sb.append("\n");
        }
        return sb.toString().trim();
    }

    // ─── Cancellation ─────────────────────────────────────────────────────────

    /**
     * How many WinSCP/SFTP processes are currently in flight for this task
     * right now. Normally 0 (idle) or 1 (running, concurrency=1). With
     * {@link AppSettings#getTransferBatchConcurrency()} &gt; 1 this can
     * briefly be more than 1 while several batches run in parallel — that's
     * exactly what the setting is for. Cheap (map lookup), safe to poll from
     * the UI on a timer.
     */
    public int getActiveSessionCount(String taskId) {
        if (taskId == null) return 0;
        java.util.Set<Process> procs = activeProcesses.get(taskId);
        return procs == null ? 0 : procs.size();
    }

    /** Total WinSCP/SFTP processes in flight across every task right now, for an at-a-glance overall count. */
    public int getTotalActiveSessionCount() {
        int total = 0;
        for (java.util.Set<Process> procs : activeProcesses.values()) {
            total += procs.size();
        }
        return total;
    }

    public boolean cancelRunningTask(String taskId) {
        if (taskId == null) return false;
        java.util.Set<Process> procs = activeProcesses.remove(taskId);
        if (procs == null || procs.isEmpty()) return false;
        boolean any = false;
        for (Process p : procs) {
            try { p.destroyForcibly(); any = true; }
            catch (Exception ignored) {}
        }
        return any;
    }

    // ─── Credential resolution ────────────────────────────────────────────────

    private Credential resolveTargetCredential(ScheduledTask task, Consumer<String> logLine) {
        String uname = task.getTargetUsername();
        if (uname != null && !uname.isEmpty()) {
            Credential c = storage.loadCredentialByUsername(uname);
            if (c != null) return c;
            logLine.accept("[ERROR] No credential found for username '" + uname
                    + "' in credentials.db (" + storage.getDataDir().getAbsolutePath() + ")");
            return null;
        }
        if (task.getTargetCredentialId() != null && !task.getTargetCredentialId().isEmpty()) {
            return storage.loadAllCredentials().stream()
                    .filter(x -> x.getId().equals(task.getTargetCredentialId()))
                    .findFirst().orElse(null);
        }
        logLine.accept("[ERROR] No target credential configured — local\u2192local transfers are not supported.");
        return null;
    }

    /** Resolves a username to a stored {@link Credential} for a Backup source/destination side, or null if blank/not found. */
    private Credential resolveNamedCredential(String username, String sideLabel, Consumer<String> logLine) {
        if (username == null || username.trim().isEmpty()) return null;
        Credential c = storage.loadCredentialByUsername(username.trim());
        if (c == null) {
            logLine.accept("[ERROR] No credential found for backup " + sideLabel + " username '" + username
                    + "' in credentials.db (" + storage.getDataDir().getAbsolutePath() + ")");
        }
        return c;
    }

    // ─── Local file helpers ───────────────────────────────────────────────────

    private File findLatestFile(File directory) {
        File[] files = directory.listFiles(File::isFile);
        if (files == null || files.length == 0) return null;
        File latest = files[0];
        for (File f : files) if (f.lastModified() > latest.lastModified()) latest = f;
        return latest;
    }

    // ─── Path / escaping helpers ──────────────────────────────────────────────

    private String normalizeLocalPath(String path) {
        if (path == null) return "";
        return path.trim()
                .replace("/", "\\")
                .replaceAll("\\\\{2,}", "\\\\");
    }

    private String normalizeRemotePath(String path) {
        if (path == null) return "";
        String normalized = path.trim().replace("\\", "/");
        normalized = normalized.replaceAll("/+", "/");
        if (normalized.matches("^[A-Za-z]:/.*")) {
            normalized = "/" + normalized;
        }
        return normalized;
    }

    private String escapeWinScpPath(String path) {
        if (path == null) return "\"\"";
        return '"' + path.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private String escapeWinScpRemotePath(String path) {
        if (path == null) return "\"\"";
        return '"' + path.replace("\"", "\\\"") + '"';
    }

    private String escapeUrl(String s) {
        return s.replace(";", "%3B").replace("@", "%40");
    }

    private String maskPasswords(String line)  { return line.replaceAll("(?i)(password[=:])\\S+", "$1****"); }
    private String nvl(String s, String def)   { return (s != null && !s.isEmpty()) ? s : def; }

    private String buildLocalDestinationPath(String dir, String fileName) {
        return normalizeLocalPath(Paths.get(dir, fileName).toString());
    }

    private String prepareRemoteDestination(String remotePath, String fileName) {
        if (remotePath.endsWith("/") || remotePath.endsWith("\\")) return remotePath + fileName;
        String last = remotePath.contains("/")
                ? remotePath.substring(remotePath.lastIndexOf('/') + 1)
                : remotePath.contains("\\")
                ? remotePath.substring(remotePath.lastIndexOf('\\') + 1) : remotePath;
        return last.contains(".") ? remotePath : remotePath + "/" + fileName;
    }

    private void secureTemp(File f) {
        f.setReadable(false, false); f.setReadable(true, true);
        f.setWritable(false, false); f.setWritable(true, true);
    }

    // ─── FIX B: Null-safe logTransferPaths ───────────────────────────────────
    // Previously crashed with NPE when target == null on local→local tasks.

    private void logTransferPaths(ScheduledTask task, Credential target,
                                  Consumer<String> logLine) {
        logLine.accept("[INFO] Transfer direction : " + task.getTransferDirection().name());
        if (task.getTransferDirection() == TransferDirection.INBOUND) {
            logLine.accept("[INFO] Remote/source path : " + task.getTargetPath());
            logLine.accept("[INFO] Local destination  : " + task.getSourcePath());
        } else {
            logLine.accept("[INFO] Local source path  : " + task.getSourcePath());
            logLine.accept("[INFO] Remote target path : " + task.getTargetPath());
        }
    }
}