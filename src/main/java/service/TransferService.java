package service;

import persistence.TransferLedger;
import model.FailureClassifier;
import persistence.AppStorage;
import service.sftp.SftpClient;
import model.Credential;
import model.FileSelection;
import model.RemoteFileException;
import model.RemoteFileMetadata;
import model.ScheduledTask;
import model.ScheduledTask.TransferDirection;
import model.ScheduledTask.TransferMode;
import service.mail.MailIngestService;
import service.transfer.EngineSettings;
import service.transfer.RemoteFileStat;
import service.transfer.ScriptFiles;
import service.backup.BackupService;
import service.transfer.RemoteDirs;
import service.transfer.ByteFormat;
import service.transfer.LocalFileOps;
import service.transfer.SessionRegistry;
import service.transfer.SizedCommand;
import service.transfer.TransferCommand;
import service.transfer.TransferJob;
import service.transfer.SftpEngine;
import service.transfer.SwitchingEngine;
import service.transfer.LocalAwareEngine;
import service.transfer.TransferEngine;
import service.transfer.WinScpEngine;
import service.watch.PersistentSftpConnectionManager;
import service.watch.WatcherConnections;
import util.AppSettings;

import java.io.*;
import java.net.InetAddress;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Executes file transfers via WinSCP's scripting interface (winscp.com).
 * Local→local copies are done by picking the built-in "Local (this computer)"
 * credential as the target; every other FILE_TRANSFER target is a remote host.
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

    private final AppStorage storage;
    private final RemoteFileMetadataServiceFactory metadataServiceFactory;
    private String winScpPath;

    private final OAuth2TokenService oauthService;
    /** Mail tasks (Microsoft Graph) are handled by their own service, not by this class. */
    private final MailIngestService mailIngest;
    private final BackupService backup;
    private final RemoteDirs remoteDirs;

    // Kept-warm SFTP connections for watcher-triggered (LATEST_ONLY + watcher
    // enabled) transfers only — see class javadoc on that path. Every other
    // transfer mode still goes through WinSCP.com via runBatchedWinScpCommands,
    // since those aren't on the "instant detection" latency-critical path.
    private final WatcherConnections persistentSftp;

    // How remote servers are actually talked to (WinSCP or in-process SFTP) lives behind TransferEngine;
    // this class only plans the work, as TransferJobs.
    private final SessionRegistry sessions = new SessionRegistry();
    private final TransferEngine engine;

    // Optional hook: fired the moment a watcher fire's files are confirmed
    // found — i.e. right as the "Watcher found N new/updated file(s)" log
    // line is emitted — before any transfer attempt starts. Lets the caller
    // (TaskSchedulerService) record the detection itself as its own
    // run-history row, distinct from the transfer's own outcome row, so
    // "files were seen" and "files were moved" are two separate, separately
    // timestamped events in the Event Monitor rather than one merged one —
    // deliberately a ThreadLocal rather than a widened executeTransfer(...)
    // signature, since only TaskSchedulerService's single call site needs it
    // and every other caller (backup mkdir/cleanup, etc.) is unaffected.
    private final ThreadLocal<Consumer<List<RemoteFileMetadata>>> detectionCallback = new ThreadLocal<>();

    /**
     * The most specific classification available for the current thread's
     * in-progress {@link #executeTransfer} call, if its outer catch blocks
     * classified one — see {@link #getLastFailureClassification()}. Same
     * ThreadLocal-per-call pattern as {@link #detectionCallback} just
     * above, for the same reason: {@code executeTransfer} returns a plain
     * {@code boolean}, so there's no return-value channel back to the
     * caller for "and here's specifically why it failed" without either
     * widening that return type everywhere it's used or threading an
     * out-parameter through every call in the chain — a thread-local set
     * right where the exception is still in hand is the smallest change
     * that gets {@link TaskSchedulerService} a reliable classification
     * instead of making it re-derive one from text after the fact.
     */
    private final ThreadLocal<model.TaskRunRecord> lastFailureClassification = new ThreadLocal<>();

    /** Reliable (exception-type-based, not text-guessed) classification of the most recent failure on this thread, or null if the last {@link #executeTransfer} call didn't fail via a classified exception. */
    public model.TaskRunRecord getLastFailureClassification() {
        return lastFailureClassification.get();
    }

    /** Set on the current thread just before {@link #executeTransfer}; see {@link #detectionCallback}. */
    public void setDetectionCallback(Consumer<List<RemoteFileMetadata>> callback) {
        detectionCallback.set(callback);
    }

    /** Clears the callback set by {@link #setDetectionCallback} — always call in a {@code finally}. */
    public void clearDetectionCallback() {
        detectionCallback.remove();
    }

    /**
     * The one and only pool of "extra" worker threads used to parallelize a
     * single watcher fire's files across multiple SFTP channels (see
     * {@link #transferFilesConcurrently}) — shared across every task, not
     * one freshly-created-and-destroyed pool per fire. This is the concrete
     * shape of "a pool of threads executes the task: one thread for low
     * load, multiple threads + batches for a big burst":
     * <ul>
     *   <li>A quiet task naming 1-2 files never touches this pool at all —
     *       {@link #transferFilesConcurrently} takes a direct single-thread,
     *       single-channel path with zero pool overhead.</li>
     *   <li>A large burst splits into batches (see
     *       {@link util.AppSettings#getWatcherFilesPerWorkerThread()}) and
     *       those batches run as tasks submitted <i>here</i> — one shared,
     *       bounded pool for the whole app.</li>
     * </ul>
     * Deliberately elastic rather than a plain fixed-size pool:
     * {@code corePoolSize=0} means no threads exist at all while every task
     * is quiet, threads are created on demand up to the ceiling below, and
     * one that's gone unused for {@link #BATCH_POOL_IDLE_SECONDS} dies again
     * rather than sitting around forever after one big burst. The ceiling
     * itself ({@link util.AppSettings#getTransferBatchConcurrency()}) is
     * re-applied on every fire (see {@link #transferFilesConcurrently}), so
     * — unlike a plain fixed pool sized once at startup — raising that
     * setting takes effect on the very next transfer, no restart needed.
     * The hand-off queue is a {@link SynchronousQueue} (no queueing — either
     * a thread is free/spun up immediately, or the ceiling is reached) paired
     * with {@link ThreadPoolExecutor.CallerRunsPolicy}: if every task
     * currently bursting has genuinely saturated the ceiling, the excess
     * batch just runs inline on whichever thread submitted it rather than
     * queueing indefinitely or being rejected — a graceful, self-throttling
     * degradation instead of unbounded thread growth.
     */
    private static final int BATCH_POOL_IDLE_SECONDS = 60;
    private final ThreadPoolExecutor batchWorkerPool = new ThreadPoolExecutor(
            0, Math.max(1, AppSettings.getTransferBatchConcurrency()),
            BATCH_POOL_IDLE_SECONDS, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            Workers.factory(Workers.Lane.WATCH),
            new ThreadPoolExecutor.CallerRunsPolicy());

    public TransferService(AppStorage storage) {
        this(storage, new PersistentSftpConnectionManager());
    }

    /** @param watcherConnections how the folder watcher keeps its server connections (JSch by default). */
    public TransferService(AppStorage storage, WatcherConnections watcherConnections) {
        this.persistentSftp         = watcherConnections;
        this.storage                = storage;
        this.metadataServiceFactory = new RemoteFileMetadataServiceFactory(storage);
        this.winScpPath             = detectWinScp();
        SftpEngine sftpEngine       = new SftpEngine(sessions, lastFailureClassification::set);
        this.engine                 = new LocalAwareEngine(sftpEngine, new SwitchingEngine(EngineSettings::useSftpEngine,
                sftpEngine, new WinScpEngine(() -> winScpPath, new ScriptFiles(), sessions)));
        // Shared dataDir-based path, NOT the per-user-home default — the
        // Daemon runs as SYSTEM (see app-config.xml <runAsSystem>) while the
        // GUI runs as the logged-in user, so a user-home-based token path
        // would put them in two different, mutually invisible directories.
        this.oauthService           = new OAuth2TokenService(OAuth2TokenService.sharedTokenDir(storage.getDataDir()));
        this.mailIngest             = new MailIngestService(storage, oauthService, new GraphMailService());
        this.remoteDirs             = new RemoteDirs(engine);
        this.backup                 = new BackupService(this::resolveNamedCredential, engine, remoteDirs, this::runBatchedWinScpCommands,
                new BackupService.BatchSettings() {
                    @Override public long batchMaxBytes() { return AppSettings.getTransferBatchMaxBytes(); }
                    @Override public int batchIntervalSeconds() { return AppSettings.getTransferBatchIntervalSeconds(); }
                });
        GraphCallLog.configure(storage.getDataDir());
        DaemonCallLog.configure(storage.getDataDir());
    }

    /** Exposed so the UI's "Authorize Mailbox" flow can enroll without duplicating OAuth logic. */
    public OAuth2TokenService getOAuthService() { return oauthService; }

    /** The service that runs mail tasks and reports their per-message events. */
    public MailIngestService mailIngest() { return mailIngest; }

    /** The service that runs backup tasks. */
    public BackupService backup() { return backup; }

    public void   setWinScpPath(String path) { this.winScpPath = path; }
    public String getWinScpPath()            { return winScpPath; }

    /** Closes and forgets any kept-warm watcher SFTP connection for this task —
     *  call when a task is deleted, disabled, or its target credential changes. */
    public void closeWatcherConnection(String taskId) { persistentSftp.close(taskId); }

    /** Closes every kept-warm watcher SFTP connection — call on scheduler/app shutdown. */
    public void shutdownWatcherConnections() {
        persistentSftp.shutdown();
        batchWorkerPool.shutdownNow();
    }

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
        sessions.bindRealTask(task.getId());
        try {
            return executeTransferInner(task, logLine, eventFileNames);
        } finally {
            sessions.unbindRealTask();
        }
    }

    private boolean executeTransferInner(ScheduledTask task, Consumer<String> logLine, Set<String> eventFileNames)
            throws WatcherSkipException {

        lastFailureClassification.remove(); // fresh for this call — see its own doc comment

        Credential target = resolveTargetCredential(task, logLine);
        logTransferPaths(task, target, logLine);

        // ── Watcher path: fires for BOTH directions when LATEST_ONLY + watcher enabled
        if (task.isWatcherEnabled()
                && task.getTransferMode() == TransferMode.LATEST_ONLY) {
            return executeWatcherTransfer(task, target, logLine, eventFileNames);
        }

        // ── Non-watcher remote path ───────────────────────────────────────────
        // The target is either a remote credential or the built-in Local one.
        if (target == null) {
            logLine.accept("[ERROR] No target credential resolved. Choose a credential for this task "
                    + "(or \"" + Credential.LOCAL_NAME + "\" to copy between two local folders).");
            return false;
        }

        TransferMode mode = task.getTransferMode() != null ? task.getTransferMode() : TransferMode.ENTIRE_FOLDER;
        if (mode != TransferMode.SPECIFIC_FILE) {
            try {
                return executeBatchedRemoteTransfer(target, task, logLine);
            } catch (WatcherSkipException skip) {
                throw skip; // "no file matched the selection rules" is a skipped run, not a failed one
            } catch (Exception e) {
                lastFailureClassification.set(FailureClassifier.classifyThrowable(e));
                logLine.accept("[ERROR] Transfer failed: " + e.getMessage());
                return false;
            }
        }

        if (task.getFileSelection().isActive()) {
            logLine.accept("[INFO] File selection rules are not used in SPECIFIC_FILE mode (it transfers the one named file).");
        }

        try {
            TransferJob job = buildSingleFileJob(target, target.getPassword(), task, logLine);
            logLine.accept("[INFO] Transfer prepared. Starting transfer...");
            boolean ok = engine.run(job, logLine, task.getId());

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
            lastFailureClassification.set(FailureClassifier.classifyThrowable(e));
            logLine.accept("[ERROR] Transfer failed: " + e.getMessage());
            return false;
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
                Workers.newPool(Workers.Lane.TRANSFER, Math.min(concurrency, batch.size()));
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(0);
        boolean allOk = true;
        try {
            List<java.util.concurrent.Callable<Boolean>> tasks = new ArrayList<>();
            for (Path src : batch) {
                tasks.add(() -> {
                    try (BackgroundJobs.Job j = BackgroundJobs.begin((op == LocalFileOp.MOVE ? "Move " : "Copy ") + src.getFileName(), false)) {
                    boolean ok = op == LocalFileOp.MOVE
                            ? moveSingleFile(src, destDir.resolve(src.getFileName()), logLine)
                            : copySingleFile(src, destDir.resolve(src.getFileName()), logLine);
                    if (ok) done.incrementAndGet();
                    if (!ok) j.fail("file operation reported failure");
                    return ok;
                    }
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
                    // Unwrap: Future.get() wraps whatever the worker threw in
                    // an ExecutionException, so classifying the wrapper
                    // itself would always fall through to UNKNOWN — the
                    // real, classifiable exception is its cause.
                    Throwable real = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null
                            ? e.getCause() : e;
                    lastFailureClassification.set(FailureClassifier.classifyThrowable(real));
                    allOk = false;
                }
            }
        } finally {
            Workers.shutdownNow(pool);
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
        return LocalFileOps.moveReplacing(src, dest, logLine);
    }

    private void sleepBetweenBatches(int intervalSeconds, Consumer<String> logLine) {
        LocalFileOps.pauseBetweenBatches(intervalSeconds, logLine);
    }

    static String humanReadableBytes(long bytes) { return ByteFormat.humanReadable(bytes); }

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

        final boolean namedByEvent = newFiles != null;
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

            newFiles = applySelectionToCandidates(task, newFiles, logLine); // before the first-run "newest only" cut
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
            // INBOUND: scan the remote source directory over SFTP, reusing the
            // same kept-warm connection the transfer itself will use (see
            // executeWinScpWatcherInbound / PersistentSftpConnectionManager)
            // instead of opening-and-closing a brand-new SSH session just for
            // this listing on every single watcher fire.
            long listStart = System.nanoTime();
            try {
                long connStart = System.nanoTime();
                SftpClient channel = persistentSftp.getClient(task.getId(), target);
                RemoteFileMetadataService svc = new SftpRemoteFileMetadataService(channel);
                String watchDir = resolveRemoteWatchDirectoryFor(task);
                logLine.accept("[INFO] Metadata service: SFTP (kept-warm connection, " + elapsedMs(connStart)
                        + "ms) | watch directory: " + watchDir);

                if (lastKnownEpochMillis > 0 && task.getLastKnownRemoteFileSize() >= 0) {
                    Instant modifiedAfterInclusive = Instant.ofEpochMilli(lastKnownEpochMillis - 1);
                    List<RemoteFileMetadata> candidates =
                            svc.getFilesModifiedAfter(watchDir, modifiedAfterInclusive);
                    long baselineSize = task.getLastKnownRemoteFileSize();
                    newFiles = candidates.stream()
                            .filter(f -> f.lastModified().isAfter(modifiedAfter)
                                    || (f.lastModified().equals(modifiedAfter) && f.size() != baselineSize))
                            .collect(Collectors.toList());
                } else {
                    newFiles = svc.getFilesModifiedAfter(watchDir, modifiedAfter);
                }
                logLine.accept("[INFO] Remote directory listing took " + elapsedMs(listStart) + "ms.");

            } catch (RemoteFileException ex) {
                logLine.accept("[ERROR] Failed to query file metadata: " + ex.getMessage());
                persistentSftp.close(task.getId());
                return false;
            } catch (Exception ex) {
                logLine.accept("[ERROR] Unexpected error opening metadata service: " + ex.getMessage());
                persistentSftp.close(task.getId());
                return false;
            }

            newFiles = applySelectionToCandidates(task, newFiles, logLine); // before the first-run "newest only" cut
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

        if (namedByEvent) newFiles = applySelectionToCandidates(task, newFiles, logLine);

        // ── Skip check ────────────────────────────────────────────────────────
        if (newFiles.isEmpty()) {
            String skipMsg = "Watcher skipped: no files modified after "
                    + modifiedAfter + " found in watch directory.";
            logLine.accept("[INFO] " + skipMsg);
            // No transfer is going to happen this fire — close now rather
            // than leaving a session open (possibly opened just moments ago,
            // during the INBOUND listing/stat above) with nothing left to do
            // on it. Harmless no-op for OUTBOUND, which never opens one here.
            persistentSftp.close(task.getId());
            throw new WatcherSkipException(skipMsg);
        }

        // Deliberately NOT logged via logLine here (which feeds both the
        // per-task Logs panel and the eventual transfer run's own history
        // "details") — the per-file breakdown belongs only in the Event
        // Monitor's separate detection row, via the callback below, never
        // duplicated into the raw per-task log under any circumstance. (The
        // transfer event's own popup still gets file names/sizes
        // independently, from the "Queued outbound/inbound: ..." lines
        // logged per file during the actual transfer step further down.)
        Consumer<List<RemoteFileMetadata>> onDetected = detectionCallback.get();
        if (onDetected != null) {
            onDetected.accept(newFiles);
        }
        // No "else" fallback log line on purpose — TaskSchedulerService
        // always wires this callback before calling executeTransfer() for a
        // FILE_TRANSFER task (see its onTaskDue), so in normal operation this
        // is never null; if some other caller invokes executeTransfer
        // without setting one, detection simply isn't recorded anywhere for
        // that call rather than leaking into the log stream as a fallback.

        RemoteFileMetadata newest = newFiles.stream()
                .max((a, b) -> a.lastModified().compareTo(b.lastModified()))
                .orElse(null);
        if (newest == null) {
            String skipMsg = "Watcher skipped: no files to process after filtering.";
            logLine.accept("[INFO] " + skipMsg);
            persistentSftp.close(task.getId());
            throw new WatcherSkipException(skipMsg);
        }

        // ── Transfer ──────────────────────────────────────────────────────────
        boolean success;
        if (isOutbound) {
            success = executeWinScpWatcherOutbound(task, target, newFiles, logLine);
        } else {
            success = executeWinScpWatcherInbound(task, target, newFiles, logLine);
        }
        try {
            if (success) afterWatcherSuccess(task, target, newFiles, isOutbound, logLine);
        } finally {
            persistentSftp.note(task.getId(), success ? "Fire finished: transfer and cleanup done, closing the session"
                    : "Fire finished: the transfer failed, closing the session");
            persistentSftp.close(task.getId(), success ? "fire finished: " + newFiles.size() + " file(s) transferred"
                    : "fire finished: transfer failed");
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
            if (!storage.saveTaskRunState(task)) {
                // This was previously fire-and-forget. If this write doesn't
                // persist, the watcher baseline never actually advances, so the
                // NEXT run will see these same files as still-new and re-transfer
                // them — a silent duplicate-transfer bug, not just a missing log
                // line. Reported via the task's own run log (same channel as
                // every other line here) since that's what's in scope at this
                // point in the transfer, not a UI dialog.
                logLine.accept("[WARN] Failed to persist updated watcher baseline (epoch=" + newEpoch
                        + ") — the next run may re-process these same file(s).");
            }
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
     * Resolves {@code names} to their live {@link RemoteFileMetadata} via a
     * direct {@code stat} per named file (local for OUTBOUND, SFTP for
     * INBOUND) — used by the event-driven fast path in
     * {@link #executeWatcherTransfer} instead of a baseline-filtered scan.
     * One round trip per named file rather than a full directory listing:
     * for a small watch directory the difference is negligible, but for a
     * large one (tens of thousands of files) a full listing can take tens
     * of seconds all by itself — pure overhead when the watcher already told
     * us exactly which file(s) to check. Returns an empty list if none of
     * the named files currently exist, or {@code null} on a hard
     * connection/stat failure — the caller treats both the same way (fall
     * back to the ordinary baseline scan below), in keeping with this
     * class's existing "push is an optimization, the baseline scan is
     * always the safety net" pattern elsewhere.
     */
    private List<RemoteFileMetadata> resolveEventNamedFiles(ScheduledTask task,
                                                             Credential target,
                                                             boolean isOutbound,
                                                             Set<String> names,
                                                             Consumer<String> logLine) {
        long statStart = System.nanoTime();
        try {
            List<RemoteFileMetadata> matched;
            if (isOutbound) {
                // Local filesystem stat is already effectively free (no network
                // round trip) — parallelizing it wouldn't meaningfully help, so
                // this stays a simple sequential loop.
                String watchDir = resolveOutboundWatchDirectory(task);
                RemoteFileMetadataService svc = new LocalFileMetadataService();
                matched = new ArrayList<>();
                for (String name : names) {
                    RemoteFileMetadata meta = svc.statFile(watchDir, name);
                    if (meta != null) {
                        matched.add(meta);
                    } else {
                        logLine.accept("[INFO]   (event-named file no longer present, skipping: " + name + ")");
                    }
                }
            } else {
                // Remote (SFTP) stat is a real network round trip per file —
                // spread the named files across multiple channel slots on the
                // task's one kept-warm session (same pattern, and same shared
                // batchWorkerPool, as the actual transfer step below) so N
                // named files cost roughly one round-trip's worth of wall time
                // instead of N sequential ones.
                long connStart = System.nanoTime();
                persistentSftp.getClient(task.getId(), target); // ensure slot 0 / the session itself is warm first
                logLine.accept("[INFO] SFTP connection for stat: acquired in " + elapsedMs(connStart) + "ms");
                String watchDir = resolveRemoteWatchDirectoryFor(task);
                matched = statNamedFilesConcurrently(task, target, watchDir, names, logLine);
            }
            logLine.accept("[INFO] Stat'd " + names.size() + " named file(s) (" + matched.size()
                    + " found) in " + elapsedMs(statStart) + "ms.");
            return matched;
        } catch (RemoteFileException ex) {
            logLine.accept("[ERROR] Failed to resolve event-named file(s): " + ex.getMessage());
            if (!isOutbound) persistentSftp.close(task.getId());
            return null;
        } catch (Exception ex) {
            logLine.accept("[ERROR] Unexpected error resolving event-named file(s): " + ex.getMessage());
            if (!isOutbound) persistentSftp.close(task.getId());
            return null;
        }
    }

    /**
     * Stats {@code names} against the remote {@code watchDir}, spread across
     * up to {@link #computeWatcherConcurrency(int)} channel slots on the
     * task's single kept-warm SSH session — the same density-scaled,
     * shared-pool concurrency {@link #transferFilesConcurrently} uses for the
     * actual transfer, applied here to the stat round trips instead. A
     * handful of named files (the common case) still runs on slot 0 alone;
     * only a genuinely large burst of named files spins up extra channels.
     */
    private List<RemoteFileMetadata> statNamedFilesConcurrently(ScheduledTask task, Credential target,
                                                                 String watchDir, Set<String> names,
                                                                 Consumer<String> logLine) throws Exception {
        List<String> nameList = new ArrayList<>(names);
        int concurrency = computeWatcherConcurrency(nameList.size());

        if (concurrency <= 1) {
            SftpClient channel = persistentSftp.getClient(task.getId(), target);
            RemoteFileMetadataService svc = new SftpRemoteFileMetadataService(channel);
            List<RemoteFileMetadata> matched = new ArrayList<>();
            for (String name : nameList) {
                RemoteFileMetadata meta = svc.statFile(watchDir, name);
                if (meta != null) matched.add(meta);
                else logLine.accept("[INFO]   (event-named file no longer present, skipping: " + name + ")");
            }
            return matched;
        }

        logLine.accept("[INFO] " + nameList.size() + " named file(s) to check — using " + concurrency
                + " worker thread(s) on the same SFTP session to stat them concurrently.");

        List<List<String>> chunks = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) chunks.add(new ArrayList<>());
        for (int i = 0; i < nameList.size(); i++) chunks.get(i % concurrency).add(nameList.get(i));

        batchWorkerPool.setMaximumPoolSize(Math.max(1, AppSettings.getTransferBatchConcurrency()));
        List<java.util.concurrent.Callable<List<RemoteFileMetadata>>> tasks = new ArrayList<>();
        for (int slot = 0; slot < concurrency; slot++) {
            final int s = slot;
            final List<String> chunk = chunks.get(slot);
            tasks.add(() -> {
                if (chunk.isEmpty()) return List.<RemoteFileMetadata>of();
                SftpClient channel = persistentSftp.acquireClient(task.getId(), s, target);
                RemoteFileMetadataService svc = new SftpRemoteFileMetadataService(channel);
                List<RemoteFileMetadata> found = new ArrayList<>();
                for (String name : chunk) {
                    RemoteFileMetadata meta = svc.statFile(watchDir, name);
                    if (meta != null) found.add(meta);
                    else logLine.accept("[INFO]   (event-named file no longer present, skipping: " + name + ")");
                }
                return found;
            });
        }
        List<java.util.concurrent.Future<List<RemoteFileMetadata>>> results = batchWorkerPool.invokeAll(tasks);
        List<RemoteFileMetadata> matched = new ArrayList<>();
        for (java.util.concurrent.Future<List<RemoteFileMetadata>> f : results) matched.addAll(f.get());
        return matched;
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

    /**
     * Remote watch directory for an INBOUND task — mirrors
     * {@code RemoteFileMetadataServiceFactory#resolveRemoteWatchDirectory},
     * duplicated locally so the persistent-connection watcher path
     * (which bypasses that factory entirely, see {@link #executeWatcherTransfer})
     * doesn't need to depend on it.
     */
    private String resolveRemoteWatchDirectoryFor(ScheduledTask task) {
        String path = normalizeRemotePath(task.getTargetPath());
        if (path.isEmpty()) return "/";
        if (path.endsWith("*")) {
            path = path.substring(0, path.lastIndexOf('/') + 1);
        } else if (!path.endsWith("/")) {
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash >= 0 && path.substring(lastSlash + 1).contains(".")) {
                path = path.substring(0, lastSlash + 1);
            } else {
                path = path + "/";
            }
        }
        return path;
    }

    // ─── WinSCP outbound watcher transfer ────────────────────────────────────

    /** Milliseconds since {@code startNanos} (a {@code System.nanoTime()} reading) — for the timing breadcrumbs above. */
    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** Milliseconds between two {@code System.nanoTime()} readings. */
    private static long elapsedMs(long startNanos, long endNanos) {
        return (endNanos - startNanos) / 1_000_000L;
    }

    /**
     * How many worker threads/channel slots a watcher fire naming
     * {@code fileCount} files should use — scales with burst size rather
     * than jumping straight to the configured concurrency ceiling the
     * moment there's more than one file. See
     * {@link util.AppSettings#getWatcherFilesPerWorkerThread()}.
     */
    private int computeWatcherConcurrency(int fileCount) {
        int filesPerThread = AppSettings.getWatcherFilesPerWorkerThread();
        int wanted = (int) Math.ceil(fileCount / (double) filesPerThread);
        return Math.max(1, Math.min(AppSettings.getTransferBatchConcurrency(), wanted));
    }

    /**
     * A single file's transfer over an already-open {@link SftpClient} —
     * the unit of work {@link #transferFilesConcurrently} spreads across
     * however many worker threads/channel slots a given fire warrants.
     * Returns bytes transferred (for the running total).
     */
    @FunctionalInterface
    private interface PerFileTransfer {
        long transfer(SftpClient channel, RemoteFileMetadata meta) throws Exception;
    }

    /**
     * Transfers {@code files} using {@code op}, spreading them across up to
     * {@link util.AppSettings#getTransferBatchConcurrency()} concurrent SFTP
     * channel slots on the task's single kept-warm session (see
     * {@link PersistentSftpConnectionManager}'s class javadoc) instead of one
     * file at a time on one thread. A fire naming only one or two files (the
     * common case) never spins up more than slot 0 — extra worker threads are
     * only used, on the same session, when a burst actually has enough files
     * queued up to benefit from it.
     */
    private long transferFilesConcurrently(ScheduledTask task, Credential target, List<RemoteFileMetadata> files,
                                            Consumer<String> logLine, PerFileTransfer op) throws Exception {
        int concurrency = computeWatcherConcurrency(files.size());

        if (concurrency <= 1) {
            SftpClient channel = persistentSftp.getClient(task.getId(), target);
            long bytes = 0L;
            for (RemoteFileMetadata meta : files) bytes += op.transfer(channel, meta);
            return bytes;
        }

        logLine.accept("[INFO] " + files.size() + " file(s) in this fire — using " + concurrency
                + " worker thread(s) on the same SFTP session to transfer them concurrently.");

        List<List<RemoteFileMetadata>> chunks = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) chunks.add(new ArrayList<>());
        for (int i = 0; i < files.size(); i++) chunks.get(i % concurrency).add(files.get(i));

        List<java.util.concurrent.Callable<Long>> tasks = new ArrayList<>();
        for (int slot = 0; slot < concurrency; slot++) {
            final int s = slot;
            final List<RemoteFileMetadata> chunk = chunks.get(slot);
            tasks.add(() -> {
                if (chunk.isEmpty()) return 0L;
                SftpClient channel = persistentSftp.acquireClient(task.getId(), s, target);
                long bytes = 0L;
                for (RemoteFileMetadata meta : chunk) bytes += op.transfer(channel, meta);
                return bytes;
            });
        }
        // Re-applied on every call (cheap) rather than only at construction —
        // this is what makes the ceiling live-adjustable: raising the
        // setting takes effect on the very next fire, no restart needed.
        // setMaximumPoolSize requires >= corePoolSize (0 here), so this is
        // always safe regardless of ordering.
        batchWorkerPool.setMaximumPoolSize(Math.max(1, AppSettings.getTransferBatchConcurrency()));

        // Submitted to the one shared, app-wide batchWorkerPool rather than a
        // pool created (and torn down) for this fire alone — see its javadoc.
        // invokeAll blocks this calling thread until every batch finishes, so
        // pool size directly bounds how many of these run at once across the
        // whole app; CallerRunsPolicy means asking for more than that still
        // works correctly (just runs some batches inline) rather than
        // deadlocking or being rejected.
        List<java.util.concurrent.Future<Long>> results = batchWorkerPool.invokeAll(tasks);
        long total = 0L;
        for (java.util.concurrent.Future<Long> f : results) total += f.get(); // rethrows the first worker's exception, if any
        return total;
    }

    /**
     * OUTBOUND watcher-triggered transfer — pushes each file directly over a
     * kept-warm SFTP connection ({@link #persistentSftp}) instead of spawning
     * a fresh WinSCP.com process (and paying a fresh SSH handshake) for every
     * single watcher fire. See {@link PersistentSftpConnectionManager}'s
     * class javadoc for why this is the fix for "detected instantly, takes
     * ~30s to actually transfer": detection was already fast, the repeated
     * per-event connection setup was not.
     */
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
        List<String> extraDestFolders = task.getAdditionalTargetPathList();
        if (!extraDestFolders.isEmpty()) {
            logLine.accept("[INFO] Also copying to " + extraDestFolders.size()
                    + " additional destination(s) on the same server: " + String.join(", ", extraDestFolders));
        }

        int connectsBefore = persistentSftp.getSessionCount(task.getId());
        long t0 = System.nanoTime();
        try {
            // Warms slot 0 up front purely so the log line below can report
            // reused-vs-connected accurately even when concurrency > 1 (extra
            // slots opened inside transferFilesConcurrently are cheap
            // channel-only opens on this same, already-live session).
            persistentSftp.getClient(task.getId(), target);
            boolean reused = persistentSftp.getSessionCount(task.getId()) == connectsBefore;
            logLine.accept("[INFO] SFTP connection: " + (reused ? "reused existing warm connection"
                    : "connected (session #" + persistentSftp.getSessionCount(task.getId()) + " for this task)")
                    + " (" + elapsedMs(t0) + "ms)");

            int concurrency = computeWatcherConcurrency(files.size());
            long transferStart = System.nanoTime();
            long totalBytes = transferFilesConcurrently(task, target, files, logLine, (channel, meta) -> {
                long fileStart = System.nanoTime();
                String localFile  = normalizeLocalPath(
                        Paths.get(localSourceDir, meta.fileName()).toString());
                String remoteFile = remoteDir + meta.fileName();
                uploadOne(channel, localFile, remoteFile);

                for (String extraFolder : extraDestFolders) {
                    String extraRemoteDir = normalizeRemotePath(extraFolder);
                    extraRemoteDir = extraRemoteDir.endsWith("/") ? extraRemoteDir : extraRemoteDir + "/";
                    uploadOne(channel, localFile, extraRemoteDir + meta.fileName());
                }
                logLine.accept("[INFO] Queued outbound: " + localFile + " → " + remoteFile
                        + " (" + elapsedMs(fileStart) + "ms, " + humanReadableBytes(Math.max(meta.size(), 0)) + ")");
                return Math.max(meta.size(), 0);
            });

            logLine.accept("[SUCCESS] Transfer completed successfully.");
            logLine.accept("[INFO] Transfer summary: " + files.size() + " file(s), "
                    + humanReadableBytes(totalBytes) + " total, 1 batch(es), 1 session(s), " + concurrency
                    + " worker thread(s). Timing — connect: " + elapsedMs(t0, transferStart) + "ms, transfer: "
                    + elapsedMs(transferStart) + "ms, total: " + elapsedMs(t0) + "ms.");
            // Session lifetime is scoped to this one fire, not kept warm for
            // the next one — see PersistentSftpConnectionManager's javadoc:
            // an idle SFTP session isn't free on the server side (each one
            // keeps its own sftp-server process alive under sshd), so close
            // it now rather than leaving that cost sitting there until the
            // idle reaper eventually gets to it. The next fire simply opens
            // a fresh one; that's now a normal, fast SSH handshake, not the
            // bottleneck it once looked like (see SftpRemoteFileMetadataService#statFile).
            persistentSftp.close(task.getId());
            return true;
        } catch (Exception ex) {
            logLine.accept("[ERROR] Outbound watcher SFTP transfer failed after " + elapsedMs(t0) + "ms: " + ex.getMessage());
            // Force a clean reconnect on the next fire rather than reusing a
            // connection that just proved broken.
            persistentSftp.close(task.getId());
            return false;
        }
    }

    // ─── Persistent-SFTP inbound watcher transfer ────────────────────────────

    /** INBOUND counterpart of {@link #executeWinScpWatcherOutbound} — see its javadoc. */
    private boolean executeWinScpWatcherInbound(ScheduledTask task,
                                                Credential target,
                                                List<RemoteFileMetadata> files,
                                                Consumer<String> logLine) {
        String localDestDir = normalizeLocalPath(task.getSourcePath());
        String remotePath   = normalizeRemotePath(task.getTargetPath());
        String remoteDir    = remotePath.endsWith("/") ? remotePath : remotePath + "/";
        List<String> extraDestFolders = task.getAdditionalTargetPathList();

        int connectsBefore = persistentSftp.getSessionCount(task.getId());
        List<String> downloadedLocalPaths = java.util.Collections.synchronizedList(new ArrayList<>());
        long t0 = System.nanoTime();
        try {
            persistentSftp.getClient(task.getId(), target);
            boolean reused = persistentSftp.getSessionCount(task.getId()) == connectsBefore;
            logLine.accept("[INFO] SFTP connection: " + (reused ? "reused existing warm connection"
                    : "connected (session #" + persistentSftp.getSessionCount(task.getId()) + " for this task)")
                    + " (" + elapsedMs(t0) + "ms)");

            int concurrency = computeWatcherConcurrency(files.size());
            long transferStart = System.nanoTime();
            long totalBytes = transferFilesConcurrently(task, target, files, logLine, (channel, meta) -> {
                long fileStart = System.nanoTime();
                String remoteFile = remoteDir + meta.fileName();
                String destPath   = buildLocalDestinationPath(localDestDir, meta.fileName());
                Path destParent = Paths.get(destPath).getParent();
                if (destParent != null) Files.createDirectories(destParent);
                channel.downloadPlain(remoteFile, new java.io.File(destPath));
                downloadedLocalPaths.add(destPath);
                persistentSftp.note(task.getId(), "get " + remoteFile + " \u2192 " + destPath + " ("
                        + Math.max(meta.size(), 0) + " bytes in " + elapsedMs(fileStart) + " ms)");
                logLine.accept("[INFO] Queued inbound: " + remoteFile + " → " + destPath
                        + " (" + elapsedMs(fileStart) + "ms, " + humanReadableBytes(Math.max(meta.size(), 0)) + ")");
                return Math.max(meta.size(), 0);
            });

            if (!extraDestFolders.isEmpty()) {
                logLine.accept("[INFO] Also copying downloaded file(s) to " + extraDestFolders.size()
                        + " additional local destination(s): " + String.join(", ", extraDestFolders));
                copyDownloadedFilesToExtraFolders(downloadedLocalPaths, extraDestFolders, logLine);
            }
            logLine.accept("[SUCCESS] Transfer completed successfully.");
            logLine.accept("[INFO] Transfer summary: " + files.size() + " file(s), "
                    + humanReadableBytes(totalBytes) + " total, 1 batch(es), 1 session(s), " + concurrency
                    + " worker thread(s). Timing — connect: " + elapsedMs(t0, transferStart) + "ms, transfer: "
                    + elapsedMs(transferStart) + "ms, total: " + elapsedMs(t0) + "ms.");
            // The session stays open for the "after a successful transfer" step (delete/move originals), which
            // runs on this same connection; executeTransfer closes it, with the reason, once that step is done.
            return true;
        } catch (Exception ex) {
            logLine.accept("[ERROR] Inbound watcher SFTP transfer failed after " + elapsedMs(t0) + "ms: " + ex.getMessage());
            persistentSftp.close(task.getId());
            return false;
        }
    }

    // ─── Single-file job builder (non-watcher remote paths) ──────────────────

    /**
     * Plans a single-file transfer. Only used for SPECIFIC_FILE mode —
     * ENTIRE_FOLDER and LATEST_ONLY go through {@link #executeBatchedRemoteTransfer}
     * instead, since those can involve more than one file and need batching.
     */
    private TransferJob buildSingleFileJob(Credential target, String password,
                                           ScheduledTask task,
                                           Consumer<String> logLine) {
        String localPath  = normalizeLocalPath(task.getSourcePath());
        String remotePath = normalizeRemotePath(task.getTargetPath());

        logLine.accept("[INFO] Target OS: " + target.getOsType() + " | Protocol: SFTP");
        logLine.accept("[INFO] Transfer mode: " + task.getTransferMode().name());

        List<TransferCommand> commands = new ArrayList<>();
        if (task.getTransferDirection() == TransferDirection.INBOUND) {
            logLine.accept("[INFO] Mode: Specific file (INBOUND)");
            String fname    = remotePath.substring(remotePath.lastIndexOf('/') + 1);
            commands.add(new TransferCommand.Download(remotePath, buildLocalDestinationPath(localPath, fname), false));
        } else {
            logLine.accept("[INFO] Mode: Specific file (OUTBOUND)");
            String fileName = new File(localPath).getName();
            commands.add(new TransferCommand.Upload(localPath, prepareRemoteDestination(remotePath, fileName)));

            List<String> extraDestFolders = task.getAdditionalTargetPathList();
            if (!extraDestFolders.isEmpty()) {
                logLine.accept("[INFO] Also copying to " + extraDestFolders.size()
                        + " additional destination(s) on the same server: " + String.join(", ", extraDestFolders));
            }
            for (String extraFolder : extraDestFolders) {
                commands.add(new TransferCommand.Upload(localPath,
                        prepareRemoteDestination(normalizeRemotePath(extraFolder), fileName)));
            }
        }
        return TransferJob.strict(target, password, commands);
    }

    private static String remoteListingDirFor(String remotePath) { return TransferPaths.remoteListingDirFor(remotePath); }

    /** Full remote file listing WITH sizes, keyed by full remote path — used for size-based batching. */
    private java.util.Map<String, RemoteFileStat> listAllRemoteFileStatsViaWinScp(
            Credential target, String password, String remotePath,
            Consumer<String> logLine) throws Exception {

        String dirPath = remoteListingDirFor(remotePath);
        return engine.listFiles(target, password, dirPath, logLine);
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

    private static <T> List<List<T>> chunkBySize(
            List<T> items, java.util.function.ToLongFunction<T> sizeOf, long maxBytes) {
        return TransferBatching.chunkBySize(items, sizeOf, maxBytes);
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
        long totalBytes = sizedCommands.stream().mapToLong(sc -> sc.size).sum();
        int threadsUsed = Math.max(1, Math.min(concurrency, batches.size()));

        if (batches.size() > 1) {
            logLine.accept("[INFO] " + sizedCommands.size() + " file(s), " + humanReadableBytes(totalBytes)
                    + " total — splitting into " + batches.size() + " batch(es), capped at ~"
                    + humanReadableBytes(maxBytes) + " per batch (configurable in Settings)."
                    + (concurrency > 1 ? " Running up to " + concurrency + " batch(es) at a time." : ""));
        }

        boolean allOk = (concurrency <= 1 || batches.size() <= 1)
                ? runBatchesSequentially(target, password, batches, intervalSeconds, logLine, taskId)
                : runBatchesConcurrently(target, password, batches, concurrency, intervalSeconds, logLine, taskId);

        // Uniform one-line summary regardless of which path ran — this is what
        // the Event Monitor's per-run detail popup parses to show files/bytes/
        // batches/sessions/threads for this run, so every transfer path (watcher
        // or not, sequential or concurrent) always emits exactly this shape.
        logLine.accept("[INFO] Transfer summary: " + sizedCommands.size() + " file(s), "
                + humanReadableBytes(totalBytes) + " total, " + batches.size() + " batch(es), "
                + batches.size() + " session(s), " + threadsUsed + " worker thread(s).");
        return allOk;
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
                Workers.newPool(Workers.Lane.TRANSFER, Math.min(concurrency, total));
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
                    waveTasks.add(() -> {
                        try (BackgroundJobs.Job j = BackgroundJobs.begin("Transfer batch " + (idx + 1) + "/" + total + " (task " + taskId + ")", false)) {
                            boolean ok = runOneBatch(target, password, batches.get(idx), idx, total, logLine, taskId);
                            if (!ok) j.fail("batch " + (idx + 1) + " reported failure");
                            return ok;
                        }
                    });
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
            Workers.shutdownNow(pool);
        }
        return allOk;
    }

    /** Runs one batch as a single session. Safe to call from multiple threads concurrently. */
    private boolean runOneBatch(Credential target, String password, List<SizedCommand> batch,
            int index, int totalBatches, Consumer<String> logLine, String taskId) {
        if (totalBatches > 1) {
            long batchBytes = batch.stream().mapToLong(sc -> sc.size).sum();
            logLine.accept("[INFO] Batch " + (index + 1) + "/" + totalBatches
                    + " (" + batch.size() + " file(s), " + humanReadableBytes(batchBytes) + ")");
        }
        TransferJob job = TransferJob.strict(target, password,
                batch.stream().map(sc -> sc.command).collect(Collectors.toList()));
        boolean ok;
        try {
            ok = engine.run(job, logLine, taskId);
        } catch (Exception ex) {
            logLine.accept("[ERROR] Batch " + (index + 1) + "/" + totalBatches + " failed: " + ex.getMessage());
            ok = false;
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

        // Optional name/size/age conditions + pick, on top of the mode (see model.FileSelection).
        final FileSelection selection = task.getFileSelection();
        final boolean useRules = selection.isActive();
        if (useRules) logLine.accept("[INFO] File selection rules: " + selection.describe());

        // "Skip files already sent": what this task sent before (a read error stops the run, so nothing is sent twice by accident).
        final TransferLedger ledger = selection.usesLedger() ? storage.ledger() : null;
        final TransferLedger.Snapshot sentBefore = ledger != null ? ledger.snapshot(task.getId()) : null;
        List<TransferLedger.Entry> toRecord = new ArrayList<>();          // what this run sends, recorded only after success
        List<String> inboundRemoteFiles = new ArrayList<>();
        List<String> inboundLocalCopies = new ArrayList<>();
        List<Long> inboundSizes = new ArrayList<>();
        List<File> outboundFiles = new ArrayList<>();

        if (inbound) {
            java.util.Map<String, RemoteFileStat> remoteStats;
            if (remotePath.endsWith("*") || new File(task.getSourcePath()).isDirectory()
                    || mode == TransferMode.LATEST_ONLY || useRules) {
                remoteStats = listAllRemoteFileStatsViaWinScp(target, target.getPassword(), remotePath, logLine);
            } else {
                remoteStats = Collections.emptyMap();
            }

            List<String> remoteFiles;
            if (useRules) {
                logLine.accept("[INFO] Mode: " + mode.name() + " with file selection rules (INBOUND)");
                List<java.util.Map.Entry<String, RemoteFileStat>> picked = selection.select(
                        remoteStats.entrySet(),
                        e -> e.getKey(), e -> e.getValue().size, e -> e.getValue().epochMillis, mode);
                logLine.accept("[INFO] " + picked.size() + " of " + remoteStats.size()
                        + " remote file(s) selected.");
                if (picked.isEmpty()) {
                    return noFileMatched(task, selection, remotePath, remoteStats.size(), logLine);
                }
                if (sentBefore != null) {
                    int before = picked.size();
                    picked = picked.stream()
                            .filter(e -> !sentBefore.wasSent(TransferCleanup.baseName(e.getKey()),
                                    e.getValue().size, e.getValue().epochMillis, selection.getSentPolicy()))
                            .collect(Collectors.toList());
                    if (picked.size() < before) {
                        logLine.accept("[INFO] Skipped " + (before - picked.size()) + " file(s) already sent ("
                                + selection.getSentPolicy() + ").");
                    }
                    if (picked.isEmpty()) return nothingNew(task, selection, remotePath, before, logLine);
                }
                remoteFiles = picked.stream().map(java.util.Map.Entry::getKey).collect(Collectors.toList());
                if (selection.getPick() == FileSelection.Pick.ALL
                        || (selection.getPick() == FileSelection.Pick.BY_MODE && mode != TransferMode.LATEST_ONLY)) {
                    Collections.sort(remoteFiles);
                }
            } else if (mode == TransferMode.LATEST_ONLY) {
                logLine.accept("[INFO] Mode: Latest file(s) — non-watcher (INBOUND)");
                if (remoteStats.isEmpty()) {
                    logLine.accept("[ERROR] No files found on remote path: " + remotePath);
                    reportNoFiles(task, selection, remotePath, "Nothing was found on the server.", logLine);
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
                    reportNoFiles(task, selection, remotePath, "Nothing was found on the server.", logLine);
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
            final boolean deleteInSession = selection.getAfterAction() == FileSelection.AfterAction.DELETE;
            for (String remoteFile : remoteFiles) {
                String fname    = remoteFile.substring(remoteFile.lastIndexOf('/') + 1);
                String destPath = buildLocalDestinationPath(localPath, fname);
                RemoteFileStat stat = remoteStats.get(remoteFile);
                long size = stat != null ? stat.size : 0L;
                // "Delete originals": WinSCP removes each file itself, right after that file has downloaded
                // successfully and in the same session - no second connection to the server is needed.
                commands.add(new SizedCommand(
                        new TransferCommand.Download(remoteFile, destPath, deleteInSession), size));
                downloadedLocalPaths.add(destPath);
                inboundRemoteFiles.add(remoteFile);
                inboundLocalCopies.add(destPath);
                inboundSizes.add(stat != null ? stat.size : -1L);
                if (stat != null) toRecord.add(new TransferLedger.Entry(fname, stat.size, stat.epochMillis));
            }

            boolean ok = runBatchedWinScpCommands(target, target.getPassword(), commands, logLine, task.getId());
            if (ok && !extraDestFolders.isEmpty()) {
                copyDownloadedFilesToExtraFolders(downloadedLocalPaths, extraDestFolders, logLine);
            }
            if (ok) {
                noFilesAlerts.reset(task.getId());
                markArrival(task, selection, logLine);
                recordSent(ledger, task, toRecord, logLine);
                if (deleteInSession) {
                    logLine.accept("[INFO] Deleted " + inboundRemoteFiles.size()
                            + " original file(s) on the server in the same session as the download.");
                } else {
                    afterSuccessRemote(target, selection, inboundRemoteFiles, inboundLocalCopies, inboundSizes, logLine);
                }
            }
            return ok;
        } else {
            List<File> localFiles;
            File sourceDir = new File(task.getSourcePath());
            if (useRules) {
                logLine.accept("[INFO] Mode: " + mode.name() + " with file selection rules (OUTBOUND)");
                List<File> candidates;
                if (sourceDir.isDirectory()) {
                    File[] files = sourceDir.listFiles(File::isFile);
                    candidates = files != null ? Arrays.asList(files) : Collections.emptyList();
                } else {
                    candidates = sourceDir.isFile() ? Collections.singletonList(sourceDir) : Collections.emptyList();
                }
                localFiles = selection.select(candidates, File::getName, File::length, File::lastModified, mode);
                logLine.accept("[INFO] " + localFiles.size() + " of " + candidates.size()
                        + " local file(s) selected.");
                if (localFiles.isEmpty()) {
                    return noFileMatched(task, selection, task.getSourcePath(), candidates.size(), logLine);
                }
                if (sentBefore != null) {
                    int before = localFiles.size();
                    localFiles = localFiles.stream()
                            .filter(f -> !sentBefore.wasSent(f.getName(), f.length(), f.lastModified(), selection.getSentPolicy()))
                            .collect(Collectors.toList());
                    if (localFiles.size() < before) {
                        logLine.accept("[INFO] Skipped " + (before - localFiles.size()) + " file(s) already sent ("
                                + selection.getSentPolicy() + ").");
                    }
                    if (localFiles.isEmpty()) return nothingNew(task, selection, task.getSourcePath(), before, logLine);
                }
                if (selection.getPick() == FileSelection.Pick.ALL
                        || (selection.getPick() == FileSelection.Pick.BY_MODE && mode != TransferMode.LATEST_ONLY)) {
                    localFiles = new ArrayList<>(localFiles);
                    localFiles.sort(Comparator.comparing(File::getName));
                }
            } else if (mode == TransferMode.LATEST_ONLY) {
                logLine.accept("[INFO] Mode: Latest file(s) (OUTBOUND, non-watcher)");
                if (sourceDir.isDirectory()) {
                    File[] files = sourceDir.listFiles(File::isFile);
                    if (files == null || files.length == 0) {
                        logLine.accept("[ERROR] No files found in source folder: " + task.getSourcePath());
                        reportNoFiles(task, selection, task.getSourcePath(), "The source folder has no files.", logLine);
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
                        reportNoFiles(task, selection, task.getSourcePath(), "The source folder has no files.", logLine);
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
                outboundFiles.add(f);
                toRecord.add(new TransferLedger.Entry(f.getName(), f.length(), f.lastModified()));
                String remoteDestPath = prepareRemoteDestination(remotePath, f.getName());
                commands.add(new SizedCommand(
                        new TransferCommand.Upload(normalizeLocalPath(f.getAbsolutePath()), remoteDestPath), f.length()));

                // Copy to every additional destination folder as well (same
                // target server/credential — see ScheduledTask.additionalTargetPaths).
                for (String extraFolder : extraDestFolders) {
                    String extraDestPath = prepareRemoteDestination(normalizeRemotePath(extraFolder), f.getName());
                    commands.add(new SizedCommand(
                            new TransferCommand.Upload(normalizeLocalPath(f.getAbsolutePath()), extraDestPath), f.length()));
                }
            }
        }

        boolean ok = runBatchedWinScpCommands(target, target.getPassword(), commands, logLine, task.getId());
        if (ok) {
            noFilesAlerts.reset(task.getId());
            markArrival(task, selection, logLine);
            recordSent(ledger, task, toRecord, logLine);
            if (selection.hasAfterAction()) {
                List<TransferCleanup.LocalOriginal> originals = new ArrayList<>();
                for (int i = 0; i < outboundFiles.size(); i++) {
                    TransferLedger.Entry e = toRecord.get(i);
                    originals.add(new TransferCleanup.LocalOriginal(outboundFiles.get(i).toPath(), e.size(), e.mtimeMillis()));
                }
                cleanupLocalOriginals(selection, originals, logLine);
            }
        }
        return ok;
    }

    /**
     * Nothing in the folder satisfied the file selection rules. By default that is a normal, quiet
     * outcome (the run is recorded as skipped, not failed — e.g. today's file has not arrived yet);
     * with "fail if nothing matches" ticked it is a failed run so retries / alerts apply.
     */
    private boolean noFileMatched(ScheduledTask task, FileSelection selection, String where, int candidates,
                                  Consumer<String> logLine) throws WatcherSkipException {
        String msg = "No file matched the file selection rules in " + where + " (" + candidates
                + " file(s) looked at). Rules: " + selection.describe();
        reportNoFiles(task, selection, where, candidates == 0
                ? "The folder is empty." : candidates + " file(s) are there, but none matches the file selection rules.", logLine);
        if (selection.isFailIfNoMatch()) {
            logLine.accept("[ERROR] " + msg);
            return false;
        }
        logLine.accept("[INFO] " + msg);
        throw new WatcherSkipException(msg);
    }

    /** Receives the "no files found" alerts of runs whose task asked for them; the scheduler wires this to the Events feed. */
    public interface NoFilesListener {
        void noFiles(ScheduledTask task, String summary, String details);
        /** Files are arriving again after a "no new files" alert. */
        void resumed(ScheduledTask task, String summary, String details);
    }

    private volatile NoFilesListener noFilesListener;
    public void setNoFilesListener(NoFilesListener l) { this.noFilesListener = l; }

    /** When each task was last alerted about missing files (this process only); cleared when a run transfers files again. */
    private final AlertThrottle noFilesAlerts = new AlertThrottle();

    /**
     * A run found nothing to send or receive. If the task asked for it (File Selection → Alerts), raises one alert
     * with where it looked and why, but not again within the task's repeat time. Never throws: an alert problem must
     * not change the outcome of the run.
     */
    private void reportNoFiles(ScheduledTask task, FileSelection selection, String where, String why,
                               Consumer<String> logLine) {
        try {
            if (!selection.isAlertNoFiles() || noFilesListener == null) return;
            long now = System.currentTimeMillis();
            long every = selection.getAlertRepeatMinutes() * 60_000L;
            if (!noFilesAlerts.tryRaise(task.getId(), every, now)) {
                logLine.accept("[INFO] No-files alert already raised " + noFilesAlerts.minutesSince(task.getId(), now)
                        + " min ago; not repeating yet.");
                return;
            }
            boolean inbound = task.getTransferDirection() == TransferDirection.INBOUND;
            String summary = (inbound ? "No files received from " : "No files to send from ") + where;
            String mode = task.getTransferMode() != null ? task.getTransferMode().name() : "ENTIRE_FOLDER";
            String details = "Task:        " + task.getName() + "\n"
                    + "Direction:   " + (inbound ? "INBOUND (download from the server)" : "OUTBOUND (upload from this PC)") + "\n"
                    + (inbound ? "Looked on the server: " : "Looked in this PC's folder: ") + where + "\n"
                    + "Mode:        " + mode + "\n"
                    + "Rules:       " + selection.describe() + "\n"
                    + "What happened: " + why + "\n"
                    + "Time:        " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "\n\n"
                    + "You can turn this alert off in the task: File Selection tab → Alerts.";
            noFilesListener.noFiles(task, summary, details);
            logLine.accept("[WARN] Alert raised: " + summary);
        } catch (Exception e) {
            logLine.accept("[WARN] Could not raise the no-files alert: " + e.getMessage());
        }
    }

    /** Files were just transferred: restarts the "no new files" clock and, if an alert was outstanding, says files are back. */
    private void markArrival(ScheduledTask task, FileSelection selection, Consumer<String> logLine) {
        if (selection.getSilenceMinutes() <= 0) return;
        try {
            boolean wasSilent = storage.ledger().markFilesTransferred(task.getId());
            NoFilesListener l = noFilesListener;
            if (wasSilent && l != null) {
                String when = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                l.resumed(task, "Files are arriving again", "Task:  " + task.getName() + "\nFiles were transferred at " + when
                        + ", ending the \"no new files\" alert.");
            }
        } catch (Exception e) {
            if (logLine != null) logLine.accept("[WARN] Could not record the file arrival time: " + e.getMessage());
        }
    }

    /** Every file that matched was sent before: a normal, quiet skip (not a failure). */
    private boolean nothingNew(ScheduledTask task, FileSelection selection, String where, int matched,
                               Consumer<String> logLine) throws WatcherSkipException {
        String msg = "Nothing new to send in " + where + ": all " + matched + " matching file(s) were already sent ("
                + selection.getSentPolicy() + ").";
        reportNoFiles(task, selection, where, "All " + matched + " matching file(s) were already sent before ("
                + selection.getSentPolicy() + "), so there is nothing new.", logLine);
        logLine.accept("[INFO] " + msg);
        throw new WatcherSkipException(msg);
    }

    /** Remembers what this run sent. The transfer already happened, so a failure here is only a warning. */
    private void recordSent(TransferLedger ledger, ScheduledTask task, List<TransferLedger.Entry> entries,
                            Consumer<String> logLine) {
        if (ledger == null || entries.isEmpty()) return;
        try {
            ledger.record(task.getId(), entries);
            logLine.accept("[INFO] Remembered " + entries.size() + " sent file(s) for this task.");
        } catch (Exception e) {
            logLine.accept("[WARN] Transfer succeeded but the sent files could not be remembered, so a later run may send them again: "
                    + e.getMessage());
        }
    }

    /** Local originals (uploads): delete or move them. Never throws; the run stays a success. */
    private void cleanupLocalOriginals(FileSelection selection, List<TransferCleanup.LocalOriginal> originals,
                                       Consumer<String> logLine) {
        if (!selection.hasAfterAction() || originals.isEmpty()) return;
        try {
            Path archive = selection.getAfterAction() == FileSelection.AfterAction.MOVE
                    ? Paths.get(selection.getAfterFolder()) : null;
            TransferCleanup.cleanupLocal(originals, selection.getAfterAction(), archive, logLine, LocalDateTime.now());
        } catch (Exception e) {
            logLine.accept("[WARN] The transfer succeeded, but the original file(s) are still in place: " + e.getMessage());
        }
    }

    /**
     * Server originals (downloads): only files whose local copy exists at the right size are touched.
     * One script deletes or moves them; any problem is a warning and the run stays a success.
     */
    private void afterSuccessRemote(Credential target, FileSelection selection, List<String> remoteFiles,
                                    List<String> localCopies, List<Long> sizes, Consumer<String> logLine) {
        if (!selection.hasAfterAction() || remoteFiles.isEmpty()) return;
        List<String> safe = new ArrayList<>();
        List<String> unsafe = new ArrayList<>();
        for (int i = 0; i < remoteFiles.size(); i++) {
            if (TransferCleanup.copyIsComplete(Paths.get(localCopies.get(i)), sizes.get(i))) safe.add(remoteFiles.get(i));
            else unsafe.add(TransferCleanup.baseName(remoteFiles.get(i)));
        }
        if (!unsafe.isEmpty()) {
            logLine.accept("[WARN] Kept on the server (the copy on this PC is missing or has the wrong size): "
                    + String.join(", ", unsafe));
        }
        if (safe.isEmpty()) return;
        try {
            boolean move = selection.getAfterAction() == FileSelection.AfterAction.MOVE;
            List<TransferCommand> cleanup = new ArrayList<>();
            if (move) {
                String dir = normalizeRemotePath(selection.getAfterFolder());
                while (dir.length() > 1 && dir.endsWith("/")) dir = dir.substring(0, dir.length() - 1);
                java.util.LinkedHashSet<String> mkdirs = new java.util.LinkedHashSet<>();
                StringBuilder prefix = new StringBuilder(dir.startsWith("/") ? "/" : "");
                for (String part : dir.split("/")) {
                    if (part.isEmpty()) continue;
                    if (prefix.length() > 0 && !prefix.toString().endsWith("/")) prefix.append('/');
                    prefix.append(part);
                    mkdirs.add(prefix.toString());
                }
                remoteDirs.ensure(target, mkdirs, logLine);
                java.util.Set<String> existing = new java.util.HashSet<>();
                try {
                    for (String k : engine.listFiles(target, target.getPassword(), dir, logLine).keySet()) {
                        existing.add(TransferCleanup.baseName(k));
                    }
                } catch (Exception ignored) { /* an empty or new folder: nothing to avoid */ }
                for (TransferCleanup.RemoteMove m : TransferCleanup.planRemoteMoves(safe, dir, existing, LocalDateTime.now())) {
                    cleanup.add(new TransferCommand.MoveRemote(m.from(), m.to()));
                }
            } else {
                for (String p : safe) cleanup.add(new TransferCommand.DeleteRemote(p));
            }
            boolean ok = engine.run(TransferJob.strict(target, target.getPassword(), cleanup), logLine, "backup-cleanup");
            if (ok) {
                logLine.accept("[INFO] " + (move ? "Moved " : "Deleted ") + safe.size() + " original file(s) on the server.");
            } else {
                logLine.accept("[WARN] The transfer succeeded, but the cleanup on the server did not finish: some original file(s) are still there.");
            }
        } catch (Exception e) {
            logLine.accept("[WARN] The transfer succeeded, but the original file(s) on the server are still in place: " + e.getMessage());
        }
    }

    /** Watcher: drops changed files that do not satisfy the selection conditions (the pick step does not apply — every new matching file is sent). */
    private List<RemoteFileMetadata> applySelectionToCandidates(ScheduledTask task, List<RemoteFileMetadata> files,
                                                                 Consumer<String> logLine) {
        FileSelection sel = task.getFileSelection();
        if (files == null || files.isEmpty() || !sel.hasFilters()) return files;
        List<RemoteFileMetadata> kept = sel.hasFilters()
                ? sel.filterOnly(files, RemoteFileMetadata::fileName,
                        RemoteFileMetadata::size, f -> f.lastModified().toEpochMilli())
                : files;
        if (kept.size() != files.size()) {
            logLine.accept("[INFO] File selection rules kept " + kept.size() + " of " + files.size()
                    + " changed file(s). Rules: " + sel.describe());
        }
        return kept;
    }

    /** Watcher fire succeeded: remember the files and run the "after a successful transfer" step. */
    private void afterWatcherSuccess(ScheduledTask task, Credential target, List<RemoteFileMetadata> files,
                                     boolean outbound, Consumer<String> logLine) {
        FileSelection sel = task.getFileSelection();
        markArrival(task, sel, null);
        // The watcher's own baseline already prevents re-sending, so the sent-files memory is not used here.
        if (!sel.hasAfterAction()) return;
        if (outbound) {
            File dir = new File(resolveOutboundWatchDirectory(task));
            List<TransferCleanup.LocalOriginal> originals = new ArrayList<>();
            for (RemoteFileMetadata f : files) {
                originals.add(new TransferCleanup.LocalOriginal(new File(dir, f.fileName()).toPath(),
                        f.size(), f.lastModified().toEpochMilli()));
            }
            cleanupLocalOriginals(sel, originals, logLine);
        } else {
            String remotePath = normalizeRemotePath(task.getTargetPath());
            String remoteDir = remotePath.endsWith("/") ? remotePath : remotePath + "/";
            String localDest = normalizeLocalPath(task.getSourcePath());
            List<String> remote = new ArrayList<>(), local = new ArrayList<>();
            List<Long> sizes = new ArrayList<>();
            for (RemoteFileMetadata f : files) {
                remote.add(remoteDir + f.fileName());
                local.add(buildLocalDestinationPath(localDest, f.fileName()));
                sizes.add(f.size());
            }
            if (!afterSuccessRemoteOnOpenSession(task, target, sel, remote, local, sizes, logLine)) {
                afterSuccessRemote(target, sel, remote, local, sizes, logLine);   // fallback: its own connection
            }
        }
    }

    /**
     * The "after a successful transfer" step for watcher downloads, run on the SSH session the download used:
     * no new connection. Only originals whose local copy is complete are touched. Returns false only if the open
     * session could not be used at all (the caller then falls back to a separate connection).
     */
    private boolean afterSuccessRemoteOnOpenSession(ScheduledTask task, Credential target, FileSelection sel,
                                                    List<String> remoteFiles, List<String> localCopies,
                                                    List<Long> sizes, Consumer<String> logLine) {
        final String id = task.getId();
        SftpClient ch;
        try {
            ch = persistentSftp.getClient(id, target);
        } catch (Exception e) {
            logLine.accept("[WARN] Could not reuse the SSH session for the cleanup step (" + e.getMessage()
                    + "); using a separate connection.");
            return false;
        }
        boolean move = sel.getAfterAction() == FileSelection.AfterAction.MOVE;
        persistentSftp.note(id, "Cleanup requested: " + (move ? "move originals to " + sel.getAfterFolder() : "delete originals")
                + " after a successful transfer (" + remoteFiles.size() + " file(s))");
        List<String> safe = new ArrayList<>();
        for (int i = 0; i < remoteFiles.size(); i++) {
            boolean complete = TransferCleanup.copyIsComplete(Paths.get(localCopies.get(i)), sizes.get(i));
            if (complete) safe.add(remoteFiles.get(i));
            else {
                String m = "Kept " + TransferCleanup.baseName(remoteFiles.get(i)) + " on the server: the copy on this PC is missing or has the wrong size";
                persistentSftp.note(id, m);
                logLine.accept("[WARN] " + m);
            }
        }
        int done = 0;
        try {
            if (move) {
                String dir = normalizeRemotePath(sel.getAfterFolder());
                while (dir.length() > 1 && dir.endsWith("/")) dir = dir.substring(0, dir.length() - 1);
                ch.mkdirs(dir);
                java.util.Set<String> existing = new java.util.HashSet<>();
                try {
                    for (service.sftp.RemoteEntry e : ch.list(dir)) existing.add(e.name());
                } catch (java.io.IOException ignored) { /* new/empty folder */ }
                for (TransferCleanup.RemoteMove m : TransferCleanup.planRemoteMoves(safe, dir, existing, LocalDateTime.now())) {
                    ch.rename(m.from(), m.to());
                    done++;
                }
            } else {
                for (String p : safe) {
                    if (ch.delete(p)) done++;
                    else persistentSftp.note(id, "rm " + p + " \u2192 already gone");
                }
            }
            if (done > 0) logLine.accept("[INFO] " + (move ? "Moved " : "Deleted ") + done
                    + " original file(s) on the server, in the same SSH session as the download.");
        } catch (Exception e) {
            persistentSftp.note(id, "Cleanup failed after " + done + " file(s): " + e.getMessage());
            logLine.accept("[WARN] The transfer succeeded, but the cleanup on the server did not finish ("
                    + e.getMessage() + "): some original file(s) are still there.");
        }
        return true;
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


    // ─── Cancellation and live session counts (state lives in SessionRegistry) ─

    /**
     * How many remote sessions (WinSCP processes or in-process SFTP clients) are in flight for this task
     * right now. Normally 0 (idle) or 1, more than 1 only while concurrent batches run
     * (AppSettings#getTransferBatchConcurrency). Cheap, safe to poll from the UI on a timer.
     */
    public int getActiveSessionCount(String taskId) { return sessions.activeSessionCount(taskId); }

    /** Total sessions in flight across every task, for an at-a-glance overall count. */
    public int getTotalActiveSessionCount() { return sessions.totalActiveSessionCount(); }

    /**
     * Every task with a non-zero session count, keyed by task id. {@code TaskSchedulerService} exports
     * it to the shared status file so a thin-client GUI can show a live session badge for work the
     * Daemon launched. Idle tasks are left out so normal exports cost nothing.
     */
    public java.util.Map<String, Integer> getActiveSessionCountsByTask() { return sessions.activeSessionCountsByTask(); }

    /** Stops every session running for the task. Returns whether anything was stopped. */
    public boolean cancelRunningTask(String taskId) { return sessions.cancel(taskId); }


    // ─── Credential resolution ────────────────────────────────────────────────

    private Credential resolveTargetCredential(ScheduledTask task, Consumer<String> logLine) {
        String uname = task.getTargetUsername();
        if (uname != null && !uname.isEmpty()) {
            Credential c = storage.loadCredentialByName(uname);
            if (c != null) return c;
            logLine.accept("[ERROR] No credential named '" + uname
                    + "' in credentials.db (" + storage.getDataDir().getAbsolutePath() + ")");
            return null;
        }
        if (task.getTargetCredentialId() != null && !task.getTargetCredentialId().isEmpty()) {
            return storage.loadAllCredentials().stream()
                    .filter(x -> x.getId().equals(task.getTargetCredentialId()))
                    .findFirst().orElse(null);
        }
        logLine.accept("[ERROR] No target credential configured — choose one, or \"" + Credential.LOCAL_NAME + "\" for local folders.");
        return null;
    }

    /** Resolves a credential name to a stored {@link Credential} for a Backup source/destination side, or null if blank/not found. */
    private Credential resolveNamedCredential(String credentialName, String sideLabel, Consumer<String> logLine) {
        if (credentialName == null || credentialName.trim().isEmpty()) return null;
        if (Credential.isLocalName(credentialName)) return null;   // built-in Local = a local side
        Credential c = storage.loadCredentialByName(credentialName.trim());
        if (c == null) {
            logLine.accept("[ERROR] No credential named '" + credentialName + "' for backup " + sideLabel
                    + " in credentials.db (" + storage.getDataDir().getAbsolutePath() + ")");
        }
        return c;
    }

    /** The stored credential with this name, or null (an error line is logged). Used by "Watch a folder" tasks. */
    public Credential credentialByName(String name, String sideLabel, Consumer<String> logLine) {
        return resolveNamedCredential(name, sideLabel, logLine);
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

    /** Writes one file with the configured watcher upload strategy (atomic by default; see {@link service.sftp.UploadStrategy}). */
    private static void uploadOne(SftpClient client, String localFile, String remoteFile) throws java.io.IOException {
        service.sftp.UploadStrategy.Mode mode =
                service.sftp.UploadStrategy.Mode.parse(util.AppSettings.get(util.AppSettings.KEY_WATCHER_UPLOAD_MODE));
        client.upload(new java.io.File(localFile), remoteFile, mode);
    }

    private String normalizeLocalPath(String path) { return TransferPaths.normalizeLocalPath(path); }

    private String normalizeRemotePath(String path) { return TransferPaths.normalizeRemotePath(path); }

    private String nvl(String s, String def)   { return (s != null && !s.isEmpty()) ? s : def; }

    private String buildLocalDestinationPath(String dir, String fileName) { return TransferPaths.buildLocalDestinationPath(dir, fileName); }

    private String prepareRemoteDestination(String remotePath, String fileName) { return TransferPaths.prepareRemoteDestination(remotePath, fileName); }

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