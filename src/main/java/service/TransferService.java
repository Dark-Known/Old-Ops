package service;

import com.jcraft.jsch.ChannelSftp;
import model.*;
import model.ScheduledTask.TransferDirection;
import model.ScheduledTask.TransferMode;
import service.watch.PersistentSftpConnectionManager;
import util.AppSettings;
import util.MailFetchMode;

import java.io.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
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
 * <p>
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
    // Outlook mail is read via Microsoft Graph (see class docs on executeImapMailTask).
    private static final String GRAPH_MAIL_SCOPE = "https://graph.microsoft.com/Mail.ReadWrite offline_access";
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
    private static final DateTimeFormatter RCV_LDM_PTM_TS_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String GRAPH_APP_ONLY_SCOPE = "https://graph.microsoft.com/.default";
    // ── WinSCP runtime cap and cancel plumbing ──────────────────────────────
    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "winscp-watchdog");
                t.setDaemon(true);
                return t;
            });
    private static final int SFTP_CONNECT_TIMEOUT_MS = 30_000;
    // ── WinSCP password hand-off ────────────────────────────────────────────
    // The password is NOT written to the script file. The script's open line refers to
    // %OPSTOOL_SFTP_PW%, which WinSCP expands from the environment of the one process
    // that runs that script (WinSCP resolves environment variables before parsing a command).
    private static final String PW_ENV = "OPSTOOL_SFTP_PW";
    private final XmlStorageService storage;
    /**
     * LDM/MVT/PTM text handling for mail tasks (header block, routing codes, station lookup, message ids).
     */
    private final MailTextProcessor mailText;
    private final RemoteFileMetadataServiceFactory metadataServiceFactory;
    private final OAuth2TokenService oauthService;
    private final GraphMailService graphMailService = new GraphMailService();
    // Keyed by taskId. A Set rather than a single Process because, with
    // AppSettings.getTransferBatchConcurrency() > 1, more than one WinSCP
    // process can legitimately be in flight for the same task at once (one
    // per concurrently-running batch).
    private final ConcurrentMap<String, java.util.Set<Process>> activeProcesses = new ConcurrentHashMap<>();
    // Kept-warm SFTP connections for watcher-triggered (LATEST_ONLY + watcher
    // enabled) transfers only — see class javadoc on that path. Every other
    // transfer mode still goes through WinSCP.com via runBatchedWinScpCommands,
    // since those aren't on the "instant detection" latency-critical path.
    private final PersistentSftpConnectionManager persistentSftp = new PersistentSftpConnectionManager();
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
    private final ThreadPoolExecutor batchWorkerPool = new ThreadPoolExecutor(
            0, Math.max(1, AppSettings.getTransferBatchConcurrency()),
            BATCH_POOL_IDLE_SECONDS, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            Workers.factory(Workers.Lane.WATCH),
            new ThreadPoolExecutor.CallerRunsPolicy());
    /**
     * When each task was last alerted about missing files (this process only); cleared when a run transfers files again.
     */
    private final AlertThrottle noFilesAlerts = new AlertThrottle();
    /**
     * Guards "pick a free file name, then claim it" so concurrent workers never choose the same name.
     */
    private final Object rcvNameLock = new Object();
    /**
     * Real task id of the transfer running on this thread, so backup helper sessions can be cancelled.
     */
    private final ThreadLocal<String> realTaskId = new ThreadLocal<>();
    private final ConcurrentHashMap<String, java.util.Set<service.sftp.SftpClient>> activeClients =
            new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, String> scriptPasswords =
            new java.util.concurrent.ConcurrentHashMap<>();
    private String winScpPath;

    // ─── Auto-detect source system ───────────────────────────────────────────
    private volatile NoFilesListener noFilesListener;
    private volatile MailEventListener mailEventListener;

    // ─── Main entry point ────────────────────────────────────────────────────

    public TransferService(XmlStorageService storage) {
        this.storage = storage;
        this.mailText = new MailTextProcessor(storage::getDataDir);
        this.metadataServiceFactory = new RemoteFileMetadataServiceFactory(storage);
        this.winScpPath = detectWinScp();
        // Shared dataDir-based path, NOT the per-user-home default — the
        // Daemon runs as SYSTEM (see app-config.xml <runAsSystem>) while the
        // GUI runs as the logged-in user, so a user-home-based token path
        // would put them in two different, mutually invisible directories.
        this.oauthService = new OAuth2TokenService(OAuth2TokenService.sharedTokenDir(storage.getDataDir()));
        GraphCallLog.configure(storage.getDataDir());
        DaemonCallLog.configure(storage.getDataDir());
    }

    public static String getLocalHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return System.getenv("COMPUTERNAME");
        }
    }

    public static String getLocalUsername() {
        return System.getProperty("user.name", System.getenv("USERNAME"));
    }

    // ─── Local file batch helper (used by Backup for a local source/destination) ──

    static String humanReadableBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format(Locale.ROOT, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    /**
     * Milliseconds since {@code startNanos} (a {@code System.nanoTime()} reading) — for the timing breadcrumbs above.
     */
    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * Milliseconds between two {@code System.nanoTime()} readings.
     */
    private static long elapsedMs(long startNanos, long endNanos) {
        return (endNanos - startNanos) / 1_000_000L;
    }

    private static String remoteListingDirFor(String remotePath) {
        return TransferPaths.remoteListingDirFor(remotePath);
    }

    private static <T> List<List<T>> chunkBySize(
            List<T> items, java.util.function.ToLongFunction<T> sizeOf, long maxBytes) {
        return TransferBatching.chunkBySize(items, sizeOf, maxBytes);
    }

    private static List<List<Path>> chunkPathsBySize(List<Path> files) {
        long maxBytes = AppSettings.getTransferBatchMaxBytes();
        return chunkBySize(files, p -> {
            try {
                return Files.size(p);
            } catch (IOException e) {
                return 0L;
            }
        }, maxBytes);
    }

    /**
     * app-config.xml {@code <graphAuthMode>CERTIFICATE</graphAuthMode>}; anything else = signed-in user (default).
     */
    private static boolean graphCertificateMode() {
        String v = util.AppConfig.readValue("graphAuthMode");
        return v != null && v.trim().equalsIgnoreCase("CERTIFICATE");
    }

    /**
     * app-config.xml {@code <winscpMaxRuntimeMinutes>}; 0 or absent = no cap (default).
     */
    private static long winScpMaxRuntimeMinutes() {
        try {
            String v = util.AppConfig.readValue("winscpMaxRuntimeMinutes");
            return v == null ? 0 : Math.max(0, Long.parseLong(v.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ─── Watcher transfer — unified for INBOUND and OUTBOUND ─────────────────

    private static boolean useSftpEngine() {
        String v = util.AppConfig.readValue("transferEngine");
        return v == null || !v.trim().equalsIgnoreCase("winscp");
    }

    /**
     * One line saying why a script session was opened, from what the script does (shown as the session's purpose).
     */
    private static String describeScript(List<String> script, String taskId) {
        int get = 0, put = 0, rm = 0, mv = 0, mkdir = 0;
        for (String l : script) {
            String t = l.trim().toLowerCase();
            if (t.startsWith("get ")) get++;
            else if (t.startsWith("put ")) put++;
            else if (t.startsWith("rm ")) rm++;
            else if (t.startsWith("mv ")) mv++;
            else if (t.startsWith("mkdir ")) mkdir++;
        }
        List<String> parts = new ArrayList<>();
        if (get > 0) parts.add("download " + get + " file(s)");
        if (put > 0) parts.add("upload " + put + " file(s)");
        if (rm > 0) parts.add("delete " + rm + " original(s)");
        if (mv > 0) parts.add("move " + mv + " original(s)");
        if (mkdir > 0) parts.add("create " + mkdir + " folder(s)");
        String what = parts.isEmpty() ? "SFTP commands" : String.join(", ", parts);
        boolean cleanup = taskId != null && taskId.equals("backup-cleanup");
        return (cleanup ? "SFTP cleanup after a successful transfer: " : "SFTP: ") + what;
    }

    /**
     * Reliable (exception-type-based, not text-guessed) classification of the most recent failure on this thread, or null if the last {@link #executeTransfer} call didn't fail via a classified exception.
     */
    public model.TaskRunRecord getLastFailureClassification() {
        return lastFailureClassification.get();
    }

    /**
     * Set on the current thread just before {@link #executeTransfer}; see {@link #detectionCallback}.
     */
    public void setDetectionCallback(Consumer<List<RemoteFileMetadata>> callback) {
        detectionCallback.set(callback);
    }

    // ─── Outbound watcher watch-directory resolution ──────────────────────────

    /**
     * Clears the callback set by {@link #setDetectionCallback} — always call in a {@code finally}.
     */
    public void clearDetectionCallback() {
        detectionCallback.remove();
    }

    /**
     * Exposed so the UI's "Authorize Mailbox" flow can enroll without duplicating OAuth logic.
     */
    public OAuth2TokenService getOAuthService() {
        return oauthService;
    }

    // ─── WinSCP outbound watcher transfer ────────────────────────────────────

    public String getWinScpPath() {
        return winScpPath;
    }

    public void setWinScpPath(String path) {
        this.winScpPath = path;
    }

    /**
     * Closes and forgets any kept-warm watcher SFTP connection for this task —
     * call when a task is deleted, disabled, or its target credential changes.
     */
    public void closeWatcherConnection(String taskId) {
        persistentSftp.close(taskId);
    }

    /**
     * Closes every kept-warm watcher SFTP connection — call on scheduler/app shutdown.
     */
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

    public boolean executeTransfer(ScheduledTask task, Consumer<String> logLine)
            throws WatcherSkipException {
        return executeTransfer(task, logLine, Collections.emptySet());
    }

    // ─── Persistent-SFTP inbound watcher transfer ────────────────────────────

    /**
     * @param eventFileNames filenames the push-watch layer (LocalWatchManager /
     *                       RemotePushWatcher) actually observed changing for this run, or empty if
     *                       this is an ordinary scheduled/manual run or the watcher only knows
     *                       "something changed" without naming it (e.g. an OVERFLOW event). When
     *                       non-empty and the task is watcher-enabled LATEST_ONLY, {@link
     *                       #executeWatcherTransfer} transfers exactly these named files instead of
     *                       deriving the file list from a directory scan filtered against the stored
     *                       baseline — see that method's javadoc for why that distinction matters.
     */
    public boolean executeTransfer(ScheduledTask task, Consumer<String> logLine, Set<String> eventFileNames)
            throws WatcherSkipException {
        realTaskId.set(task.getId());
        try {
            return executeTransferInner(task, logLine, eventFileNames);
        } finally {
            realTaskId.remove();
        }
    }

    // ─── WinSCP script builder (non-watcher remote paths) ────────────────────

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
            lastFailureClassification.set(FailureClassifier.classifyThrowable(e));
            logLine.accept("[ERROR] Transfer failed: " + e.getMessage());
            return false;
        } finally {
            if (scriptFile != null) scriptFile.delete();
        }
    }

    // ─── Legacy WinSCP ls (non-watcher INBOUND LATEST_ONLY fallback) ─────────

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

    /**
     * Original one-at-a-time behavior for a single batch — used when concurrency is 1 (default).
     */
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

    /**
     * Copies a single file, replacing the destination if it already exists.
     */
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

    // ─── WinSCP process runner ────────────────────────────────────────────────

    /**
     * Moves a single file, replacing the destination if it already exists.
     */
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

    private void sleepBetweenBatches(int intervalSeconds, Consumer<String> logLine) {
        try {
            logLine.accept("[INFO] Pausing " + intervalSeconds + "s before next batch...");
            Thread.sleep(intervalSeconds * 1000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

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
                    ChannelSftp channel = persistentSftp.getChannel(task.getId(), target);
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
                persistentSftp.getChannel(task.getId(), target); // ensure slot 0 / the session itself is warm first
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
            ChannelSftp channel = persistentSftp.getChannel(task.getId(), target);
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
                ChannelSftp channel = persistentSftp.acquireChannel(task.getId(), s, target);
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

    private void probeWindowsRemotePaths(com.jcraft.jsch.Session session,
                                         String rawPath,
                                         Consumer<String> logLine) {
        try {
            com.jcraft.jsch.ChannelSftp probe =
                    (com.jcraft.jsch.ChannelSftp) session.openChannel("sftp");
            probe.connect();

            String base = rawPath.replace("\\", "/").replaceAll("/+$", "");
            String noLead = base.replaceFirst("^/", "");
            String asIs = base;
            String withSlash = "/" + noLead;
            String noDrive = base.replaceFirst("^[A-Za-z]:/", "/");
            String cygwin = base.replaceFirst("^([A-Za-z]):/", "/$1/");
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
            ChannelSftp channel = persistentSftp.getChannel(task.getId(), target);
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
                ChannelSftp channel = persistentSftp.acquireChannel(task.getId(), s, target);
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
        for (java.util.concurrent.Future<Long> f : results)
            total += f.get(); // rethrows the first worker's exception, if any
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
        String remotePath = normalizeRemotePath(task.getTargetPath());
        String remoteDir = remotePath.endsWith("/") ? remotePath : remotePath + "/";
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
            persistentSftp.getChannel(task.getId(), target);
            boolean reused = persistentSftp.getSessionCount(task.getId()) == connectsBefore;
            logLine.accept("[INFO] SFTP connection: " + (reused ? "reused existing warm connection"
                    : "connected (session #" + persistentSftp.getSessionCount(task.getId()) + " for this task)")
                    + " (" + elapsedMs(t0) + "ms)");

            int concurrency = computeWatcherConcurrency(files.size());
            long transferStart = System.nanoTime();
            long totalBytes = transferFilesConcurrently(task, target, files, logLine, (channel, meta) -> {
                long fileStart = System.nanoTime();
                String localFile = normalizeLocalPath(
                        Paths.get(localSourceDir, meta.fileName()).toString());
                String remoteFile = remoteDir + meta.fileName();
                channel.put(localFile, remoteFile);

                for (String extraFolder : extraDestFolders) {
                    String extraRemoteDir = normalizeRemotePath(extraFolder);
                    extraRemoteDir = extraRemoteDir.endsWith("/") ? extraRemoteDir : extraRemoteDir + "/";
                    channel.put(localFile, extraRemoteDir + meta.fileName());
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

    /**
     * INBOUND counterpart of {@link #executeWinScpWatcherOutbound} — see its javadoc.
     */
    private boolean executeWinScpWatcherInbound(ScheduledTask task,
                                                Credential target,
                                                List<RemoteFileMetadata> files,
                                                Consumer<String> logLine) {
        String localDestDir = normalizeLocalPath(task.getSourcePath());
        String remotePath = normalizeRemotePath(task.getTargetPath());
        String remoteDir = remotePath.endsWith("/") ? remotePath : remotePath + "/";
        List<String> extraDestFolders = task.getAdditionalTargetPathList();

        int connectsBefore = persistentSftp.getSessionCount(task.getId());
        List<String> downloadedLocalPaths = java.util.Collections.synchronizedList(new ArrayList<>());
        long t0 = System.nanoTime();
        try {
            persistentSftp.getChannel(task.getId(), target);
            boolean reused = persistentSftp.getSessionCount(task.getId()) == connectsBefore;
            logLine.accept("[INFO] SFTP connection: " + (reused ? "reused existing warm connection"
                    : "connected (session #" + persistentSftp.getSessionCount(task.getId()) + " for this task)")
                    + " (" + elapsedMs(t0) + "ms)");

            int concurrency = computeWatcherConcurrency(files.size());
            long transferStart = System.nanoTime();
            long totalBytes = transferFilesConcurrently(task, target, files, logLine, (channel, meta) -> {
                long fileStart = System.nanoTime();
                String remoteFile = remoteDir + meta.fileName();
                String destPath = buildLocalDestinationPath(localDestDir, meta.fileName());
                Path destParent = Paths.get(destPath).getParent();
                if (destParent != null) Files.createDirectories(destParent);
                channel.get(remoteFile, destPath);
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

        String localPath = normalizeLocalPath(task.getSourcePath());
        String remotePath = normalizeRemotePath(task.getTargetPath());

        logLine.accept("[INFO] Target OS: " + target.getOsType() + " | Protocol: SFTP");
        logLine.accept("[INFO] Transfer mode: " + task.getTransferMode().name());

        try (PrintWriter pw = new PrintWriter(new FileWriter(tmpScript))) {
            pw.println("option batch abort");
            pw.println("option confirm off");
            writeOpenLine(pw, target, password, tmpScript);

            boolean inbound = task.getTransferDirection() == TransferDirection.INBOUND;

            if (inbound) {
                logLine.accept("[INFO] Mode: Specific file (INBOUND)");
                String fname = remotePath.substring(remotePath.lastIndexOf('/') + 1);
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

    private java.util.Map<String, RemoteFileStat> rawRemoteFileStats(
            Credential target, String password, String dirPath,
            Consumer<String> logLine) throws Exception {

        if (useSftpEngine()) return listViaSftp(target, password, dirPath, logLine);
        logLine.accept("[INFO] Listing remote directory (WinSCP ls): " + dirPath);

        File listScript = File.createTempFile("opstool_ls_", ".txt");
        secureTemp(listScript);

        try (PrintWriter pw = new PrintWriter(new FileWriter(listScript))) {
            pw.println("option batch abort");
            pw.println("option confirm off");
            writeOpenLine(pw, target, password, listScript);
            pw.println("ls \"" + dirPath + "\"");
            pw.println("close");
            pw.println("exit");
        }

        List<String> rawLines = new ArrayList<>();
        File listLogFile = new File(listScript.getAbsolutePath() + ".log");
        long lsOpenedAt = 0;
        int lsExit = 0;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    winScpPath, "/script=" + listScript.getAbsolutePath(),
                    "/log=" + listLogFile.getAbsolutePath(), "/loglevel=1");
            applyScriptPassword(pb, listScript);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            lsOpenedAt = System.currentTimeMillis();
            SshSessionEvents.opened(null, "WinSCP listing", target.getHost(), target.getUsername());
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
            lsExit = proc.waitFor();
        } finally {
            if (lsOpenedAt != 0) SshSessionEvents.closed(null, "WinSCP listing", target.getHost(), target.getUsername(),
                    lsExit == 0 ? "finished" : "WinSCP exit code " + lsExit, System.currentTimeMillis() - lsOpenedAt);
            listScript.delete();
            try {
                listLogFile.delete();
            } catch (Exception ignored) {
            }
        }
        logLine.accept("[INFO] Received " + rawLines.size() + " lines from remote listing.");

        java.util.Map<String, Integer> MONTH_MAP = new java.util.HashMap<>();
        MONTH_MAP.put("jan", 1);
        MONTH_MAP.put("feb", 2);
        MONTH_MAP.put("mar", 3);
        MONTH_MAP.put("apr", 4);
        MONTH_MAP.put("may", 5);
        MONTH_MAP.put("jun", 6);
        MONTH_MAP.put("jul", 7);
        MONTH_MAP.put("aug", 8);
        MONTH_MAP.put("sep", 9);
        MONTH_MAP.put("oct", 10);
        MONTH_MAP.put("nov", 11);
        MONTH_MAP.put("dec", 12);

        int currentYear = LocalDate.now().getYear();
        java.util.Map<String, RemoteFileStat> fileStats = new java.util.LinkedHashMap<>();

        for (String line : rawLines) {
            String t = line.trim();
            if (t.isEmpty() || !t.startsWith("-")) continue;
            String[] parts = t.split("\\s+", 10);
            if (parts.length < 9) continue;

            long sizeBytes;
            try {
                sizeBytes = Long.parseLong(parts[4]);
            } catch (NumberFormatException e) {
                sizeBytes = 0L;
            }

            String monthStr = parts[5].toLowerCase(java.util.Locale.ENGLISH);
            String dayStr = parts[6].trim();
            String timeOrYear = parts[7];
            String fileName = parts[8];

            Integer monthNum = MONTH_MAP.get(monthStr);
            if (monthNum == null) continue;
            int dayNum;
            try {
                dayNum = Integer.parseInt(dayStr);
            } catch (NumberFormatException e) {
                continue;
            }

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
            } catch (Exception ex) {
                continue;
            }

            fileStats.put(dirPath + fileName, new RemoteFileStat(epochMillis, sizeBytes));
        }

        return fileStats;
    }

    /**
     * Full remote file listing WITH sizes, keyed by full remote path — used for size-based batching.
     */
    private java.util.Map<String, RemoteFileStat> listAllRemoteFileStatsViaWinScp(
            Credential target, String password, String remotePath,
            Consumer<String> logLine) throws Exception {

        String dirPath = remoteListingDirFor(remotePath);
        return rawRemoteFileStats(target, password, dirPath, logLine);
    }

    private boolean runWinScpScript(File scriptFile, Consumer<String> logLine,
                                    String taskId) throws Exception {
        if (useSftpEngine()) return runScriptViaSftp(scriptFile, logLine, taskId);
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
        applyScriptPassword(pb, scriptFile);
        pb.redirectErrorStream(true);
        Process proc = null;
        // Each WinSCP.com process is its own SSH session: report it to the Event Monitor (pseudo task ids
        // such as "backup-mkdir" are not tasks, so those resolve through the calling thread's run).
        String[] who = SshSessionEvents.targetOfWinScpScript(scriptFile);
        String evTask = taskId != null && !taskId.startsWith("backup-") ? taskId : null;
        long sshOpenedAt = 0;
        String sshEnd = "finished";
        String realTaskIdRegistered = null;
        java.util.concurrent.ScheduledFuture<?> watchdog = null;
        final java.util.concurrent.atomic.AtomicBoolean timedOut = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            proc = pb.start();
            sshOpenedAt = System.currentTimeMillis();
            SshSessionEvents.opened(evTask, "WinSCP transfer", who[1], who[0]);
            if (taskId != null) {
                activeProcesses.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).add(proc);
            }
            // Backup helper sessions use pseudo ids ("backup-mkdir"); also register them under the
            // real task so Cancel can stop them.
            realTaskIdRegistered = realTaskId.get();
            if (realTaskIdRegistered != null && !realTaskIdRegistered.equals(taskId)) {
                activeProcesses.computeIfAbsent(realTaskIdRegistered, k -> ConcurrentHashMap.newKeySet()).add(proc);
            }
            final Process watched = proc;
            long maxMin = winScpMaxRuntimeMinutes();
            if (maxMin > 0) {
                watchdog = WATCHDOG.schedule(() -> {
                    timedOut.set(true);
                    try {
                        watched.destroyForcibly();
                    } catch (Exception ignored) {
                    }
                }, maxMin, java.util.concurrent.TimeUnit.MINUTES);
            }

            try (BufferedReader br =
                         new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    logLine.accept(maskPasswords(line));
                    if (Thread.currentThread().isInterrupted()) {
                        try {
                            proc.destroyForcibly();
                        } catch (Exception ignored) {
                        }
                        throw new InterruptedException("Transfer cancelled");
                    }
                }
            }
            int exitCode = proc.waitFor();
            if (timedOut.get()) {
                sshEnd = "timed out";
                logLine.accept("[ERROR] WinSCP was stopped after the configured maximum runtime of "
                        + winScpMaxRuntimeMinutes() + " minute(s) (winscpMaxRuntimeMinutes).");
                return false;
            }
            if (exitCode == 0) {
                logLine.accept("[SUCCESS] Transfer completed successfully.");
                return true;
            } else {
                sshEnd = "WinSCP exit code " + exitCode;
                logLine.accept("[ERROR] WinSCP exited with code: " + exitCode);
                return false;
            }
        } catch (InterruptedException ie) {
            sshEnd = "cancelled";
            logLine.accept("[INFO] Transfer interrupted: " + ie.getMessage());
            if (proc != null) try {
                proc.destroyForcibly();
            } catch (Exception ignored) {
            }
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ex) {
            sshEnd = "error: " + ex.getMessage();
            if (sshOpenedAt == 0) SshSessionEvents.failed(evTask, "WinSCP transfer", who[1], who[0], ex.getMessage());
            throw ex;
        } finally {
            if (sshOpenedAt != 0) SshSessionEvents.closed(evTask, "WinSCP transfer", who[1], who[0], sshEnd,
                    System.currentTimeMillis() - sshOpenedAt);
            if (taskId != null) {
                java.util.Set<Process> set = activeProcesses.get(taskId);
                if (set != null) {
                    set.remove(proc);
                    if (set.isEmpty()) activeProcesses.remove(taskId, set);
                }
            }
            if (watchdog != null) watchdog.cancel(false);
            if (realTaskIdRegistered != null && !realTaskIdRegistered.equals(taskId)) {
                java.util.Set<Process> set = activeProcesses.get(realTaskIdRegistered);
                if (set != null) {
                    set.remove(proc);
                    if (set.isEmpty()) activeProcesses.remove(realTaskIdRegistered, set);
                }
            }
            try {
                logFile.delete();
            } catch (Exception ignored) {
            }
        }
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

    /**
     * Original one-at-a-time behavior — used when concurrency is 1 (default) or there's only one batch.
     */
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

    /**
     * Builds and runs the WinSCP script for a single batch. Safe to call from multiple threads concurrently.
     */
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
                writeOpenLine(pw, target, password, scriptFile);
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

        String localPath = normalizeLocalPath(task.getSourcePath());
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
        final TransferLedger ledger = selection.usesLedger() ? new TransferLedger(storage.getDataDir()) : null;
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
                String fname = remoteFile.substring(remoteFile.lastIndexOf('/') + 1);
                String destPath = buildLocalDestinationPath(localPath, fname);
                RemoteFileStat stat = remoteStats.get(remoteFile);
                long size = stat != null ? stat.size : 0L;
                // "Delete originals": WinSCP removes each file itself, right after that file has downloaded
                // successfully and in the same session - no second connection to the server is needed.
                commands.add(new SizedCommand(
                        "get " + (deleteInSession ? "-delete " : "") + escapeWinScpRemotePath(remoteFile) + " "
                                + escapeWinScpPath(destPath), size));
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

    public void setNoFilesListener(NoFilesListener l) {
        this.noFilesListener = l;
    }

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

    // ─── Exception type ───────────────────────────────────────────────────────

    /**
     * Files were just transferred: restarts the "no new files" clock and, if an alert was outstanding, says files are back.
     */
    private void markArrival(ScheduledTask task, FileSelection selection, Consumer<String> logLine) {
        if (selection.getSilenceMinutes() <= 0) return;
        try {
            boolean wasSilent = new TransferLedger(storage.getDataDir()).markFilesTransferred(task.getId());
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

    /**
     * Every file that matched was sent before: a normal, quiet skip (not a failure).
     */
    private boolean nothingNew(ScheduledTask task, FileSelection selection, String where, int matched,
                               Consumer<String> logLine) throws WatcherSkipException {
        String msg = "Nothing new to send in " + where + ": all " + matched + " matching file(s) were already sent ("
                + selection.getSentPolicy() + ").";
        reportNoFiles(task, selection, where, "All " + matched + " matching file(s) were already sent before ("
                + selection.getSentPolicy() + "), so there is nothing new.", logLine);
        logLine.accept("[INFO] " + msg);
        throw new WatcherSkipException(msg);
    }

    /**
     * Remembers what this run sent. The transfer already happened, so a failure here is only a warning.
     */
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

    /**
     * Local originals (uploads): delete or move them. Never throws; the run stays a success.
     */
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
            if (TransferCleanup.copyIsComplete(Paths.get(localCopies.get(i)), sizes.get(i)))
                safe.add(remoteFiles.get(i));
            else unsafe.add(TransferCleanup.baseName(remoteFiles.get(i)));
        }
        if (!unsafe.isEmpty()) {
            logLine.accept("[WARN] Kept on the server (the copy on this PC is missing or has the wrong size): "
                    + String.join(", ", unsafe));
        }
        if (safe.isEmpty()) return;
        try {
            boolean move = selection.getAfterAction() == FileSelection.AfterAction.MOVE;
            List<String> lines = new ArrayList<>();
            if (move) {
                String dir = normalizeRemotePath(selection.getAfterFolder());
                while (dir.length() > 1 && dir.endsWith("/")) dir = dir.substring(0, dir.length() - 1);
                java.util.LinkedHashSet<String> mkdirs = new java.util.LinkedHashSet<>();
                StringBuilder prefix = new StringBuilder(dir.startsWith("/") ? "/" : "");
                for (String part : dir.split("/")) {
                    if (part.isEmpty()) continue;
                    if (prefix.length() > 0 && !prefix.toString().endsWith("/")) prefix.append('/');
                    prefix.append(part);
                    mkdirs.add("mkdir " + escapeWinScpRemotePath(prefix.toString()));
                }
                runBestEffortWinScpCommands(target, mkdirs, logLine);
                java.util.Set<String> existing = new java.util.HashSet<>();
                try {
                    for (String k : rawRemoteFileStats(target, target.getPassword(), dir, logLine).keySet()) {
                        existing.add(TransferCleanup.baseName(k));
                    }
                } catch (Exception ignored) { /* an empty or new folder: nothing to avoid */ }
                for (TransferCleanup.RemoteMove m : TransferCleanup.planRemoteMoves(safe, dir, existing, LocalDateTime.now())) {
                    lines.add("mv " + escapeWinScpRemotePath(m.from()) + " " + escapeWinScpRemotePath(m.to()));
                }
            } else {
                for (String p : safe) lines.add("rm " + escapeWinScpRemotePath(p));
            }
            File scriptFile = File.createTempFile("opstool_after_", ".txt");
            secureTemp(scriptFile);
            try (PrintWriter pw = new PrintWriter(new FileWriter(scriptFile))) {
                pw.println("option batch abort");
                pw.println("option confirm off");
                writeOpenLine(pw, target, target.getPassword(), scriptFile);
                for (String l : lines) pw.println(l);
                pw.println("close");
                pw.println("exit");
            }
            boolean ok;
            try {
                ok = runWinScpScript(scriptFile, logLine, "backup-cleanup");
            } finally {
                scriptFile.delete();
            }
            if (ok) {
                logLine.accept("[INFO] " + (move ? "Moved " : "Deleted ") + safe.size() + " original file(s) on the server.");
            } else {
                logLine.accept("[WARN] The transfer succeeded, but the cleanup on the server did not finish: some original file(s) are still there.");
            }
        } catch (Exception e) {
            logLine.accept("[WARN] The transfer succeeded, but the original file(s) on the server are still in place: " + e.getMessage());
        }
    }

    /**
     * Watcher: drops changed files that do not satisfy the selection conditions (the pick step does not apply — every new matching file is sent).
     */
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

    /**
     * Watcher fire succeeded: remember the files and run the "after a successful transfer" step.
     */
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
        ChannelSftp ch;
        try {
            ch = persistentSftp.getChannel(id, target);
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
                StringBuilder prefix = new StringBuilder(dir.startsWith("/") ? "/" : "");
                for (String part : dir.split("/")) {
                    if (part.isEmpty()) continue;
                    if (prefix.length() > 0 && !prefix.toString().endsWith("/")) prefix.append('/');
                    prefix.append(part);
                    try {
                        ch.stat(prefix.toString());
                    } catch (com.jcraft.jsch.SftpException missing) {
                        ch.mkdir(prefix.toString());
                        persistentSftp.note(id, "mkdir " + prefix);
                    }
                }
                java.util.Set<String> existing = new java.util.HashSet<>();
                try {
                    for (Object o : ch.ls(dir)) existing.add(((ChannelSftp.LsEntry) o).getFilename());
                } catch (com.jcraft.jsch.SftpException ignored) { /* new/empty folder */ }
                persistentSftp.note(id, "ls " + dir + " \u2192 " + existing.size() + " existing entries (to avoid overwriting)");
                for (TransferCleanup.RemoteMove m : TransferCleanup.planRemoteMoves(safe, dir, existing, LocalDateTime.now())) {
                    ch.rename(m.from(), m.to());
                    persistentSftp.note(id, "mv " + m.from() + " \u2192 " + m.to());
                    done++;
                }
            } else {
                for (String p : safe) {
                    ch.rm(p);
                    persistentSftp.note(id, "rm " + p);
                    done++;
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

    public boolean executeBackup(ScheduledTask task, Consumer<String> logLine) {
        String sourcePathStr = task.getBackupSourcePath();
        String destPathStr = task.getBackupDestinationPath();

        if (sourcePathStr == null || sourcePathStr.trim().isEmpty()) {
            logLine.accept("[ERROR] Backup source path is not configured.");
            return false;
        }
        if (destPathStr == null || destPathStr.trim().isEmpty()) {
            logLine.accept("[ERROR] Backup destination path is not configured.");
            return false;
        }

        boolean sourceRemote = task.getBackupSourceUsername() != null && !task.getBackupSourceUsername().trim().isEmpty();
        boolean destRemote = task.getBackupDestinationUsername() != null && !task.getBackupDestinationUsername().trim().isEmpty();

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

    /**
     * Local source: lists regular files older than the retention cutoff, grouped by calendar day.
     */
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

    // ─── Per-message processing (runs on a mail worker thread) ──────────────

    /**
     * Remote source: lists remote files (over SFTP) older than the retention cutoff, grouped by calendar day.
     */
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

    /**
     * Local→local backup: moves files into {@code <destRoot>/<yyyy-MM-dd>/}, in size-based batches.
     */
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

    /**
     * Remote source → local destination: pulls files via WinSCP `get` into {@code <destRoot>/<yyyy-MM-dd>/}, in size-based batches.
     */
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
                    "get -delete " + escapeWinScpRemotePath(c.remotePath) + " " + escapeWinScpPath(destPath), c.size));
        }
        try {
            boolean ok = runBatchedWinScpCommands(sourceCred, sourceCred.getPassword(), commands, logLine, taskId);
            // Originals are removed by WinSCP itself ("get -delete"), file by file, in the same session as the download.
            if (ok)
                logLine.accept("[INFO] Removed " + candidates.size() + " original file(s) from the remote source in the same session.");
            return ok;
        } catch (Exception ex) {
            logLine.accept("[ERROR] Remote\u2192local backup failed: " + ex.getMessage());
            return false;
        }
    }

    /**
     * Local source → remote destination: pushes files via WinSCP `put` into {@code <destRoot>/<yyyy-MM-dd>/}, in size-based batches.
     */
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

    /**
     * Runs a small set of WinSCP commands (e.g. mkdir) in one session, tolerating individual failures.
     */
    private boolean runBestEffortWinScpCommands(Credential target, java.util.Collection<String> cmds, Consumer<String> logLine) throws Exception {
        if (cmds.isEmpty()) return true;
        File scriptFile = File.createTempFile("opstool_mkdir_", ".txt");
        secureTemp(scriptFile);
        try (PrintWriter pw = new PrintWriter(new FileWriter(scriptFile))) {
            pw.println("option batch continue"); // don't abort the whole script if a single mkdir fails (dir exists)
            pw.println("option confirm off");
            writeOpenLine(pw, target, target.getPassword(), scriptFile);
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

    /**
     * Deletes a batch of remote files (after a successful remote→local backup pull).
     */
    private void deleteRemoteFilesViaWinScp(Credential target, List<String> remotePaths, Consumer<String> logLine) {
        if (remotePaths.isEmpty()) return;
        try {
            File scriptFile = File.createTempFile("opstool_rm_", ".txt");
            secureTemp(scriptFile);
            try (PrintWriter pw = new PrintWriter(new FileWriter(scriptFile))) {
                pw.println("option batch continue");
                pw.println("option confirm off");
                writeOpenLine(pw, target, target.getPassword(), scriptFile);
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

        String folder = nvl(task.getImapFolder(), "INBOX");
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
        final Consumer<String> safeLog = s -> {
            synchronized (logLock) {
                logLine.accept(s);
            }
        };
        // Run key/trigger live in thread-locals of this (task) thread; the mail workers must be handed them.
        final MailRunContext mailCtx = new MailRunContext(task, mailbox, folder,
                RunHistoryService.currentRunKey(), RunHistoryService.currentTrigger());

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
            try {
                oldestUnsaved = Math.min(oldestUnsaved, Instant.parse(m.receivedDateTime).toEpochMilli());
            } catch (Exception ignored) { /* unparseable: ignore */ }
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

    /**
     * Worker threads used per mail task run. Override with {@code mailWorkerThreads} in app-config.xml (1 = strictly sequential).
     */
    private int mailWorkerThreads() {
        int n = 4;
        try {
            String v = util.AppConfig.readValue("mailWorkerThreads");
            if (v != null && !v.trim().isEmpty()) n = Integer.parseInt(v.trim());
        } catch (RuntimeException ignored) { /* bad value -> default */ }
        return Math.max(1, Math.min(n, 8));
    }

    // ─── Attachment download ────────────────────────────────────────────────

    public void setMailEventListener(MailEventListener l) {
        this.mailEventListener = l;
    }

    // ─── .RCV file output ─────────────────────────────────────────────────────

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

    /**
     * Builds and delivers the Events-feed entry for one mail message — a one-line summary plus a full detail block.
     */
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

    /**
     * Saves every downloadable (non-inline, file-type) attachment of a
     * message to disk under:
     * <attachmentBase>/<LDM|PTM|Others>/<message-scoped subfolder>/<original filename>
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
                    String ext = extDot > 0 ? safeName.substring(extDot) : "";
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

    // ─── Cancellation ─────────────────────────────────────────────────────────

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
     * <p>
     * Content source preference: a message's own (non-inline) attachment text
     * takes priority over its body when a parseable text attachment exists
     * (see GraphMailService#fetchTextAttachments) — the structured content
     * these tasks care about is more often carried in an attached .txt/.csv
     * than typed into the body. Falls back to the body when there's no
     * usable attachment.
     * <p>
     * BODY_ONLY and HEADERS_AND_BODY previously produced identical output —
     * both prepended Subject/From/Received regardless of mode, so "BODY_ONLY"
     * never actually meant body-only. Fixed to a real three-tier distinction:
     * BODY_ONLY        → the message content: HTML converted to plain text,
     * automated banners (Outlook "first contact" /
     * external-sender warnings) removed, truncated to
     * start at the LDM/MVT/PTM marker, then
     * signatures/sign-offs, footers/disclaimers, and
     * quoted reply history stripped out (best-effort).
     * When an LDM/MVT/PTM marker is found, a SITA-style
     * =HEADER/=PRIORITY/... block (see
     * buildSitaHeaderBlock) is prepended.
     * HEADERS_AND_BODY → Subject/From/Received + the full content as plain
     * text, untouched (signatures/quotes kept, no SITA
     * header — that block is specific to BODY_ONLY).
     * FULL_MESSAGE     → raw MIME from Graph, as before.
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

    /**
     * Total WinSCP/SFTP processes in flight across every task right now, for an at-a-glance overall count.
     */
    public int getTotalActiveSessionCount() {
        int total = 0;
        for (java.util.Set<Process> procs : activeProcesses.values()) {
            total += procs.size();
        }
        return total;
    }

    /**
     * Snapshot of every task with a genuinely non-zero session count right
     * now, keyed by task id — the cross-process counterpart of {@link
     * #getActiveSessionCount(String)}. Used by {@code TaskSchedulerService}
     * to export live session counts to the shared status file, so a
     * thin-client GUI (which never runs the transfers itself when the
     * Daemon is the active scheduler) can still show a live session badge
     * for whichever process actually launched the WinSCP process — see
     * {@code SchedulerStatusSnapshot.SessionEntry}. Empty tasks are
     * deliberately left out, keeping normal (idle) exports at zero cost.
     */
    public java.util.Map<String, Integer> getActiveSessionCountsByTask() {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (var entry : activeProcesses.entrySet()) {
            int size = entry.getValue().size();
            if (size > 0) counts.put(entry.getKey(), size);
        }
        return counts;
    }

    private service.sftp.SftpScriptRunner.Connector sftpConnector() {
        return (host, port, user, pw) ->
                service.sftp.JschSftpClient.connect(host, port, user, pw, SFTP_CONNECT_TIMEOUT_MS);
    }

    private void registerClient(String id, service.sftp.SftpClient c) {
        if (id != null) activeClients.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(c);
    }

    private void unregisterClient(String id, service.sftp.SftpClient c) {
        if (id == null) return;
        java.util.Set<service.sftp.SftpClient> set = activeClients.get(id);
        if (set != null) {
            set.remove(c);
            if (set.isEmpty()) activeClients.remove(id, set);
        }
    }

    // ── Java SFTP engine (default) ─────────────────────────────────────────
    // The planning code still writes small WinSCP-style scripts; with transferEngine=sftp
    // (default) they are executed by SftpScriptRunner on JSch instead of WinSCP.com.
    // Set <transferEngine>winscp</transferEngine> in app-config.xml to go back to WinSCP.

    private boolean runScriptViaSftp(File scriptFile, Consumer<String> logLine, String taskId) throws Exception {
        String password = scriptPasswords.remove(scriptFile.getAbsolutePath());
        List<String> script = java.nio.file.Files.readAllLines(scriptFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
        String realId = realTaskId.get();
        String[] who = SshSessionEvents.targetOfWinScpScript(scriptFile);
        String evTask = taskId != null && !taskId.startsWith("backup-") ? taskId : null;
        final String purpose = describeScript(script, taskId);
        long openedAt = 0;
        String end = "finished";
        java.util.concurrent.ScheduledFuture<?> watchdog = null;
        try {
            final java.util.concurrent.atomic.AtomicBoolean timedOut = new java.util.concurrent.atomic.AtomicBoolean();
            final java.util.List<service.sftp.SftpClient> mine = new java.util.concurrent.CopyOnWriteArrayList<>();
            service.sftp.SftpScriptRunner runner = new service.sftp.SftpScriptRunner(sftpConnector(),
                    c -> {
                        mine.add(c);
                        registerClient(taskId, c);
                        if (realId != null && !realId.equals(taskId)) registerClient(realId, c);
                    },
                    c -> {
                        mine.remove(c);
                        unregisterClient(taskId, c);
                        if (realId != null && !realId.equals(taskId)) unregisterClient(realId, c);
                    });
            long maxMin = winScpMaxRuntimeMinutes();
            if (maxMin > 0) {
                watchdog = WATCHDOG.schedule(() -> {
                    timedOut.set(true);
                    for (service.sftp.SftpClient c : mine) {
                        try {
                            c.cancel();
                        } catch (Exception ignored) {
                        }
                    }
                }, maxMin, java.util.concurrent.TimeUnit.MINUTES);
            }
            openedAt = System.currentTimeMillis();
            SshSessionEvents.opened(evTask, purpose, who[1], who[0]);
            service.sftp.SftpScriptRunner.Outcome out = runner.run(script, password, logLine);
            service.sftp.SessionLog.currentOrNew().add(out.ok() ? "Script finished: all commands succeeded"
                    : "Script finished: a command failed");
            if (out.ok()) {
                logLine.accept("[SUCCESS] Transfer completed successfully.");
                return true;
            }
            end = "failed";
            logLine.accept("[ERROR] SFTP transfer failed.");
            return false;
        } catch (IOException ex) {
            if (watchdog != null && watchdog.isDone()) {
                logLine.accept("[ERROR] Transfer stopped after the configured maximum runtime of "
                        + winScpMaxRuntimeMinutes() + " minute(s) (winscpMaxRuntimeMinutes).");
                end = "timed out";
                return false;
            }
            end = "error: " + ex.getMessage();
            if (Thread.currentThread().isInterrupted() || String.valueOf(ex.getMessage()).contains("cancelled")) {
                logLine.accept("[INFO] Transfer interrupted: " + ex.getMessage());
                return false;
            }
            logLine.accept("[ERROR] SFTP transfer failed: " + ex.getMessage());
            lastFailureClassification.set(FailureClassifier.classifyThrowable(ex));
            return false;
        } finally {
            if (watchdog != null) watchdog.cancel(false);
            if (openedAt != 0) SshSessionEvents.closed(evTask, purpose, who[1], who[0], end,
                    System.currentTimeMillis() - openedAt);
        }
    }

    private java.util.Map<String, RemoteFileStat> listViaSftp(Credential target, String password, String dirPath,
                                                              Consumer<String> logLine) throws Exception {
        logLine.accept("[INFO] Listing remote directory (SFTP): " + dirPath);
        long openedAt = System.currentTimeMillis();
        final String listPurpose = "SFTP listing of " + dirPath + " (find the files to transfer)";
        SshSessionEvents.opened(null, listPurpose, target.getHost(), target.getUsername());
        String end = "finished";
        try (service.sftp.SftpClient c = service.sftp.JschSftpClient.connect(
                target.getHost(), 22, target.getUsername(), password, SFTP_CONNECT_TIMEOUT_MS)) {
            java.util.Map<String, RemoteFileStat> stats = new java.util.LinkedHashMap<>();
            for (service.sftp.RemoteEntry e : c.list(dirPath)) {
                if (e.directory()) continue;
                stats.put(dirPath + e.name(), new RemoteFileStat(e.modifiedEpochSeconds() * 1000L, e.size()));
            }
            logLine.accept("[INFO] Received " + stats.size() + " file entries from remote listing.");
            return stats;
        } catch (IOException ex) {
            end = "error: " + ex.getMessage();
            throw ex;
        } finally {
            SshSessionEvents.closed(null, listPurpose, target.getHost(), target.getUsername(), end,
                    System.currentTimeMillis() - openedAt);
        }
    }

    public boolean cancelRunningTask(String taskId) {
        if (taskId == null) return false;
        boolean any = false;
        java.util.Set<service.sftp.SftpClient> clients = activeClients.remove(taskId);
        if (clients != null) {
            for (service.sftp.SftpClient c : clients) {
                try {
                    c.cancel();
                    any = true;
                } catch (Exception ignored) {
                }
            }
        }
        java.util.Set<Process> procs = activeProcesses.remove(taskId);
        if (procs == null || procs.isEmpty()) return any;
        for (Process p : procs) {
            try {
                p.destroyForcibly();
                any = true;
            } catch (Exception ignored) {
            }
        }
        return any;
    }

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
        logLine.accept("[ERROR] No target credential configured — local\u2192local transfers are not supported.");
        return null;
    }

    /**
     * Resolves a credential name to a stored {@link Credential} for a Backup source/destination side, or null if blank/not found.
     */
    private Credential resolveNamedCredential(String credentialName, String sideLabel, Consumer<String> logLine) {
        if (credentialName == null || credentialName.trim().isEmpty()) return null;
        Credential c = storage.loadCredentialByName(credentialName.trim());
        if (c == null) {
            logLine.accept("[ERROR] No credential named '" + credentialName + "' for backup " + sideLabel
                    + " in credentials.db (" + storage.getDataDir().getAbsolutePath() + ")");
        }
        return c;
    }

    /**
     * The stored credential with this name, or null (an error line is logged). Used by "Watch a folder" tasks.
     */
    public Credential credentialByName(String name, String sideLabel, Consumer<String> logLine) {
        return resolveNamedCredential(name, sideLabel, logLine);
    }

    private File findLatestFile(File directory) {
        File[] files = directory.listFiles(File::isFile);
        if (files == null || files.length == 0) return null;
        File latest = files[0];
        for (File f : files) if (f.lastModified() > latest.lastModified()) latest = f;
        return latest;
    }

    private String normalizeLocalPath(String path) {
        return TransferPaths.normalizeLocalPath(path);
    }

    private String normalizeRemotePath(String path) {
        return TransferPaths.normalizeRemotePath(path);
    }

    private String escapeWinScpPath(String path) {
        return TransferPaths.escapeWinScpPath(path);
    }

    // ─── Credential resolution ────────────────────────────────────────────────

    private String escapeWinScpRemotePath(String path) {
        return TransferPaths.escapeWinScpRemotePath(path);
    }

    private void writeOpenLine(PrintWriter pw, Credential target, String password, File script) {
        scriptPasswords.put(script.getAbsolutePath(), password == null ? "" : password);
        pw.println("open sftp://" + escapeUrl(target.getUsername()) + "@" + target.getHost()
                + "/ -hostkey=\"*\" -password=\"%" + PW_ENV + "%\"");
    }

    private void applyScriptPassword(ProcessBuilder pb, File script) {
        String pwd = scriptPasswords.remove(script.getAbsolutePath());
        if (pwd != null) pb.environment().put(PW_ENV, pwd.replace("\"", "\"\""));
    }

    // ─── Local file helpers ───────────────────────────────────────────────────

    private String escapeUrl(String s) {
        return s.replace(";", "%3B").replace("@", "%40");
    }

    // ─── Path / escaping helpers ──────────────────────────────────────────────

    private String maskPasswords(String line) {
        return line.replaceAll("(?i)(password[=:])\\S+", "$1****");
    }

    private String nvl(String s, String def) {
        return (s != null && !s.isEmpty()) ? s : def;
    }

    private String buildLocalDestinationPath(String dir, String fileName) {
        return TransferPaths.buildLocalDestinationPath(dir, fileName);
    }

    private String prepareRemoteDestination(String remotePath, String fileName) {
        return TransferPaths.prepareRemoteDestination(remotePath, fileName);
    }

    private void secureTemp(File f) {
        f.setReadable(false, false);
        f.setReadable(true, true);
        f.setWritable(false, false);
        f.setWritable(true, true);
    }

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

    /**
     * Whether a local file batch operation copies (leaves original in place) or moves it.
     */
    private enum LocalFileOp {COPY, MOVE}

    /**
     * A single file's transfer over an already-open {@link ChannelSftp} —
     * the unit of work {@link #transferFilesConcurrently} spreads across
     * however many worker threads/channel slots a given fire warrants.
     * Returns bytes transferred (for the running total).
     */
    @FunctionalInterface
    private interface PerFileTransfer {
        long transfer(ChannelSftp channel, RemoteFileMetadata meta) throws Exception;
    }

    /**
     * Receives the "no files found" alerts of runs whose task asked for them; the scheduler wires this to the Events feed.
     */
    public interface NoFilesListener {
        void noFiles(ScheduledTask task, String summary, String details);

        /**
         * Files are arriving again after a "no new files" alert.
         */
        void resumed(ScheduledTask task, String summary, String details);
    }

    /**
     * Receives one event per mail message a run handled; the scheduler wires this to the Events feed.
     */
    public interface MailEventListener {
        void onMailEvent(ScheduledTask task, boolean saved, String summary, String details,
                         LocalDateTime start, LocalDateTime end, String runKey, String trigger);
    }

    /**
     * A remote file's last-modified epoch (ms) and size (bytes), as parsed from `ls -l`.
     */
    private static final class RemoteFileStat {
        final long epochMillis;
        final long size;

        RemoteFileStat(long epochMillis, long size) {
            this.epochMillis = epochMillis;
            this.size = size;
        }
    }

    /**
     * A WinSCP command line ("put ..."/"get ...") paired with the transferred file's size, for size-based batching.
     */
    private static final class SizedCommand {
        final String command;
        final long size;

        SizedCommand(String command, long size) {
            this.command = command;
            this.size = size;
        }
    }

    static class WatcherSkipException extends Exception {
        public WatcherSkipException(String message) {
            super(message);
        }
    }

    /**
     * A backup-eligible file: its full identity/location, calendar day, and size in bytes.
     */
    private static final class BackupCandidate {
        final String name;
        final LocalDate day;
        final long size;
        final Path localPath;      // set when source is LOCAL
        final String remotePath;   // set when source is REMOTE (full remote path)

        BackupCandidate(String name, LocalDate day, long size, Path localPath, String remotePath) {
            this.name = name;
            this.day = day;
            this.size = size;
            this.localPath = localPath;
            this.remotePath = remotePath;
        }
    }

    // ─── FIX B: Null-safe logTransferPaths ───────────────────────────────────
    // Previously crashed with NPE when target == null on local→local tasks.

    /**
     * What the mail workers need to know about the run they belong to (captured on the task thread).
     */
    private record MailRunContext(ScheduledTask task, String mailbox, String folder, String runKey, String trigger) {
    }
}