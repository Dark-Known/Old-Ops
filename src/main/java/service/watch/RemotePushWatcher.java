package service.watch;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import model.Credential;
import model.ScheduledTask;
import persistence.AppStorage;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * Best-effort push notifications for INBOUND file-transfer watcher tasks.
 *
 * <p>Plain SFTP has no protocol-level "notify me on change" — the only way to
 * learn about a new remote file is to ask the server. This class narrows that
 * gap by relocating the OS-level watch to the remote host itself: for each
 * eligible task it opens one long-lived SSH "exec" channel and runs an
 * OS-appropriate directory-watch command, streaming filenames back over that
 * channel the instant they change. That turns "ask every N minutes" into
 * "the remote box tells us the instant something lands," with none of the
 * recurring cost of a directory listing on an idle poll tick.
 *
 * <p>Two remote OSes are supported, chosen from the resolved
 * {@link Credential#getOsType()}:
 * <ul>
 *   <li><b>LINUX</b> — {@code inotifywait -m}, the same mechanism Linux's own
 *       kernel-level inotify API exposes as a CLI tool.</li>
 *   <li><b>WINDOWS</b> — a small PowerShell script built around
 *       {@code System.IO.FileSystemWatcher}, the same .NET class behind
 *       Windows' own native change-notification API
 *       ({@code ReadDirectoryChangesW}). Sent as a single
 *       {@code -EncodedCommand} (base64 UTF-16LE) rather than an inline
 *       string, which sidesteps SSH/cmd.exe quoting entirely and works
 *       whether the OpenSSH server's configured default shell is
 *       {@code cmd.exe} or PowerShell.</li>
 * </ul>
 *
 * <h2>Requirements (auto-detected per task)</h2>
 * <ul>
 *   <li>The remote host's recorded credential {@code osType} must be
 *       {@code LINUX} or {@code WINDOWS} — anything else (unset, unknown) is
 *       left on the ordinary poll schedule.</li>
 *   <li>Linux: {@code inotify-tools} must be installed.</li>
 *   <li>Windows: {@code powershell.exe} must be on PATH (true by default on
 *       every supported Windows Server/desktop release).</li>
 *   <li>The SSH server must allow an "exec" channel (arbitrary command
 *       execution). Some locked-down/managed SFTP-only servers only expose
 *       the sftp subsystem and refuse this outright, on either OS.</li>
 * </ul>
 *
 * <h2>One listener per folder, shared by every task that watches it</h2>
 * Listeners are keyed by server + user + normalized folder (see {@link #listenerKey}), not by task. Tasks that watch
 * the same folder subscribe to one listener: one SSH connection and one remote process, and each streamed file name
 * is passed to every subscribed task. The listener closes when its last subscriber leaves.
 *
 * <h2>Catching up after a gap</h2>
 * While no listener runs (connection lost, daemon stopped, task just enabled) a new file produces no event, and a task
 * with a live watch is not polled. So every time a listener becomes ready, each subscribed task gets one catch-up
 * wake-up with no file names; the scheduler answers with the same baseline scan a poll would run. Files that landed in
 * the gap are therefore picked up as soon as the listener is back, without remote buffering.
 *
 * <h2>Cleaning up after a lost connection</h2>
 * A Windows listener never ends other processes itself. Once armed it reports its own processes; this class records
 * them ({@link RemoteListenerRegistry}) and, before it arms a listener for the same folder again, ends exactly the
 * recorded ones that are still running ({@link RemoteProcessJanitor}). A listener of another daemon or task was never
 * recorded here, so it is never touched.
 *
 * <p>If any check fails, this class marks the task unsupported (cached, with
 * backoff, so it doesn't hammer a server that just rejected an exec request)
 * and gets out of the way entirely — the task's existing scheduled poll (see
 * {@code TaskSchedulerService#computeNextFireDelayMs}) keeps working exactly
 * as it does today, unmodified. Enabling this class never removes the
 * polling fallback; it only sometimes makes it fire earlier than its next
 * scheduled tick.
 */
public class RemotePushWatcher {

    private static final Logger log = Logger.getLogger(RemotePushWatcher.class.getName());
    private static final long UNSUPPORTED_RETRY_MS = TimeUnit.HOURS.toMillis(1);
    // Quiet period after the last observed remote change before firing —
    // same purpose as LocalWatchManager's settle window. Kept short (1.2s,
    // down from an earlier 2s) so this sits well under the transfer's overall
    // detected-to-started budget.
    private static final long SETTLE_MILLIS = 1200L;
    private static final String UNAVAILABLE_MARKER = "__WATCH_UNAVAILABLE__";
    /** Keep-alive line the remote script prints every few seconds; ignored here. Its real job is
     *  to make the remote process fail a write (and exit) once the SSH channel is gone. */
    private static final String HEARTBEAT = "__HB__";
    /** Final line of the Windows listener: "__END__<why>". */
    private static final String END_PREFIX = "__END__";
    /** Breadcrumb lines the Windows listener prints after each startup phase ("__STEP__listening"). They are logged
     *  in the session transcript, never treated as file events, so an early death can be traced to a phase. */
    private static final String STEP_PREFIX = "__STEP__";
    /** The Windows listener's last startup breadcrumb: the watcher is registered and the event loop is about to run.
     *  Must match {@code Mark 'listening'} in windows/event-loop.ps1 (checked by ListenerScriptsTest). */
    static final String STEP_LISTENING = "listening";
    /** The Linux listener prints no breadcrumbs: this long after its channel opens, inotifywait is certainly watching. */
    private static long LINUX_ARM_DELAY_MS() { return util.Timers.ms("timerLinuxListenerArmDelayMs", 1_500); }

    private final AppStorage storage;
    // Remote processes this installation's listeners have reported (survives a restart of the daemon).
    private final RemoteListenerRegistry processes;
    // taskId, why -> the listener is ready: look for files that arrived while nothing was watching.
    private final BiConsumer<String, String> onCatchUp;
    // taskId, changedFileNames -> wake the task up now, naming exactly the file(s)
    // inotifywait/FileSystemWatcher reported.
    private final BiConsumer<String, Set<String>> onSettled;

    private ExecutorService listenerExecutor;
    private ScheduledExecutorService debounceExecutor;

    // One remote listener per server+user+folder, however many tasks watch that folder.
    private final Map<String, ListenerHandle> listenersByKey = new ConcurrentHashMap<>();
    // The listener each task is subscribed to.
    private final Map<String, ListenerHandle> activeByTaskId = new ConcurrentHashMap<>();
    // Guards changes to the two maps above and to a listener's subscriber set.
    private final Object subscriptionLock = new Object();
    private final Map<String, Long> unsupportedUntil = new ConcurrentHashMap<>();
    // Filenames accumulated for a task's pending fire, merged across every
    // streamed line that arrives before the debounce settles.
    private final Map<String, Set<String>> pendingNamesByTaskId = new ConcurrentHashMap<>();
    // Pending debounce timers, one outstanding "about to fire" per task. Each new
    // streamed line cancels and replaces the previous timer, so a burst of events
    // for one file drop (Windows FileSystemWatcher typically emits Created +
    // several Changed + Renamed) collapses into a SINGLE wake-up that names every
    // file, instead of one wake-up per line where only the first carries names.
    private final Map<String, ScheduledFuture<?>> pendingFires = new ConcurrentHashMap<>();
    // taskId -> a short human-readable reason for the *current* state — "never
    // attempted" vs. "tried and failed, here's why" vs. "connected and
    // listening" are otherwise indistinguishable from the UI's point of view.
    private final Map<String, String> reasonByTaskId = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    public RemotePushWatcher(AppStorage storage, BiConsumer<String, Set<String>> onSettled) {
        this(storage, onSettled, (taskId, why) -> { });
    }

    public RemotePushWatcher(AppStorage storage, BiConsumer<String, Set<String>> onSettled, BiConsumer<String, String> onCatchUp) {
        this(storage, onSettled, onCatchUp, RemoteListenerRegistry.inDataDir(storage == null ? null : storage.getDataDir()));
    }

    RemotePushWatcher(AppStorage storage, BiConsumer<String, Set<String>> onSettled,
                      BiConsumer<String, String> onCatchUp, RemoteListenerRegistry processes) {
        this.storage = storage;
        this.onSettled = onSettled;
        this.onCatchUp = onCatchUp;
        this.processes = processes;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        listenerExecutor = service.Workers.newCachedPool(service.Workers.Lane.WATCH);
        debounceExecutor = service.Workers.newScheduled(service.Workers.Lane.WATCH, 1);
        log.info("RemotePushWatcher started (best-effort remote push — Linux inotify / Windows FileSystemWatcher"
                + " — for eligible INBOUND watcher tasks).");
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        for (ListenerHandle h : new HashSet<>(listenersByKey.values())) h.close();
        listenersByKey.clear();
        activeByTaskId.clear();
        if (listenerExecutor != null) listenerExecutor.shutdownNow();
        if (debounceExecutor != null) debounceExecutor.shutdownNow();
        pendingNamesByTaskId.clear();
        pendingFires.clear();
    }

    /** Same reconcile-driven pattern as {@link LocalWatchManager}: cheap,
     *  idempotent, call on every scheduler reconcile sweep. */
    public void sync(List<ScheduledTask> tasks) {
        if (!running) return;

        Set<String> stillEligible = new HashSet<>();
        for (ScheduledTask t : tasks) {
            if (!isEligible(t)) continue;

            long retryAt = unsupportedUntil.getOrDefault(t.getId(), 0L);
            if (System.currentTimeMillis() < retryAt) continue; // recently failed, back off

            Credential cred = resolveCredential(t);
            RemoteOs os = resolveOs(cred);
            if (os == null) continue; // unset/unrecognised osType — not our problem to solve

            stillEligible.add(t.getId());
            String key = listenerKey(cred, os, t.getTargetPath());
            ListenerHandle existing = activeByTaskId.get(t.getId());
            if (existing != null && existing.key.equals(key) && existing.usable()) continue; // already on a live listener, config unchanged

            subscribe(t, cred, os, key);
        }

        for (String taskId : new ArrayList<>(activeByTaskId.keySet())) {
            if (!stillEligible.contains(taskId)) {
                stopListener(taskId);
            }
        }
    }

    /** True if this task currently has a live remote listener streaming
     *  change events (i.e. it's getting push notifications from the remote
     *  host, not just polling), regardless of remote OS. */
    public boolean isPushActive(String taskId) {
        return activeByTaskId.containsKey(taskId);
    }

    /** True if this task was recently found unsupported (missing tool, exec
     *  refused, connection failure, etc.) and is in its backoff window —
     *  useful for the UI to distinguish "not attempted" from "tried and failed". */
    public boolean isRecentlyUnsupported(String taskId) {
        Long retryAt = unsupportedUntil.get(taskId);
        return retryAt != null && System.currentTimeMillis() < retryAt;
    }

    /** Short human-readable reason for this task's current state — e.g.
     *  "connected, streaming remote change events", "remote host has no
     *  inotifywait", or an SSH/connection exception message. {@code null} if
     *  this task has never been evaluated (not eligible, or sync() hasn't run
     *  yet — e.g. right after the task was created). */
    public String getReason(String taskId) {
        return reasonByTaskId.get(taskId);
    }

    private boolean isEligible(ScheduledTask t) {
        return t.getTaskType() == ScheduledTask.TaskType.FILE_TRANSFER
                && t.isWatcherEnabled()
                && t.getTransferDirection() == ScheduledTask.TransferDirection.INBOUND
                && t.getTransferMode() == ScheduledTask.TransferMode.LATEST_ONLY
                && t.getStatus() != ScheduledTask.TaskStatus.DISABLED
                && t.getTargetPath() != null && !t.getTargetPath().isBlank();
    }

    /**
     * Forces an immediate reconnect attempt for this task — the manual
     * "Reconnect" action from the UI. Clears any active 1-hour "recently
     * unsupported" backoff and drops the current listener (if any), then
     * starts a fresh attempt right away rather than waiting for the next
     * reconcile sweep or the backoff window to expire. The actual SSH
     * connect happens asynchronously on the listener executor, same as a
     * normal sync()-driven start, so this returns immediately — callers
     * (e.g. a UI popup) should re-check {@link #getReason} a couple of
     * seconds later to see the outcome.
     */
    public void forceReconnect(ScheduledTask task) {
        if (!running || task == null) return;
        unsupportedUntil.remove(task.getId());
        dropListenerOf(task.getId());
        if (!isEligible(task)) {
            reasonByTaskId.put(task.getId(),
                    "not eligible for remote push (check watcher enabled, direction is INBOUND, and transfer mode is Latest Only)");
            return;
        }
        Credential cred = resolveCredential(task);
        RemoteOs os = resolveOs(cred);
        if (os == null) {
            reasonByTaskId.put(task.getId(), cred == null
                    ? "no credential resolved for this task's target username/credential"
                    : "remote OS type must be LINUX or WINDOWS (currently: "
                        + (cred.getOsType() == null ? "unset" : cred.getOsType()) + ")");
            return;
        }
        subscribe(task, cred, os, listenerKey(cred, os, task.getTargetPath()));
    }

    /** Unsubscribes a task. Its listener is closed only if nobody else is subscribed to it. */
    private void stopListener(String taskId) {
        ListenerHandle toClose = null;
        synchronized (subscriptionLock) {
            ListenerHandle h = activeByTaskId.remove(taskId);
            if (h != null) {
                h.subscribers.remove(taskId);
                if (h.subscribers.isEmpty()) {
                    listenersByKey.remove(h.key, h);
                    toClose = h;
                }
            }
        }
        cancelPending(taskId);
        if (toClose != null) toClose.close();   // waits briefly for the remote process to exit, so outside the lock
    }

    /**
     * Ends the listener this task is on, for every task sharing it (the manual Reconnect must really reconnect).
     * The other tasks rejoin a new listener on the next {@link #sync}.
     */
    private void dropListenerOf(String taskId) {
        ListenerHandle h = activeByTaskId.get(taskId);
        if (h == null) return;
        List<String> others = new ArrayList<>();
        synchronized (subscriptionLock) {
            for (String id : h.subscribers) {
                activeByTaskId.remove(id, h);
                if (!id.equals(taskId)) others.add(id);
            }
            h.subscribers.clear();
            listenersByKey.remove(h.key, h);
        }
        cancelPending(taskId);
        for (String id : others) {
            cancelPending(id);
            reasonByTaskId.put(id, "the listener shared with another task on this folder was restarted by its Reconnect; rejoining on the next sync");
        }
        h.close();
    }

    private void cancelPending(String taskId) {
        ScheduledFuture<?> pending = pendingFires.remove(taskId);
        if (pending != null) pending.cancel(false);
        pendingNamesByTaskId.remove(taskId);
    }

    /** Subscribes the task to the listener for {@code key}, starting that listener if no live one exists yet. */
    void subscribe(ScheduledTask task, Credential cred, RemoteOs os, String key) {
        String id = task.getId();
        ListenerHandle previous = activeByTaskId.get(id);
        if (previous != null && !previous.key.equals(key)) stopListener(id);   // the task now watches another folder or server
        ListenerHandle toStart = null;
        ListenerHandle h;
        synchronized (subscriptionLock) {
            h = listenersByKey.get(key);
            if (h == null || !h.usable()) {
                h = new ListenerHandle(key, id, task.getTargetPath());
                listenersByKey.put(key, h);
                toStart = h;
            }
            h.subscribers.add(id);
            activeByTaskId.put(id, h);
            if (previous != null && previous != h) previous.subscribers.remove(id);
        }
        if (toStart != null) {
            reasonByTaskId.put(id, "connecting (" + os + ")...");
            launch(toStart, cred, os);
        } else {
            reasonByTaskId.put(id, h.state != null ? h.state : "connecting (" + os + ")...");
            if (h.armed) catchUp(id, "the task joined a listener that was already running");
        }
    }

    /**
     * The listener is now watching the folder. Anything that landed while nothing was watching produced no event, so
     * every subscriber gets one catch-up wake-up (the scheduler decides whether the task needs it).
     */
    void armed(ListenerHandle handle, String why) {
        handle.armed = true;
        for (String id : handle.subscribers) catchUp(id, why);
    }

    private void catchUp(String taskId, String why) {
        try {
            onCatchUp.accept(taskId, why);
        } catch (Exception e) {
            log.warning("Watcher task " + taskId + ": catch-up callback failed: " + e.getMessage());
        }
    }

    /** Starts the connection for a new listener. Separate so tests can run the subscription logic without SSH. */
    void launch(ListenerHandle handle, Credential cred, RemoteOs os) {
        listenerExecutor.submit(() -> runListener(cred, handle.path, os, handle));
    }

    /** Connection attempts before giving up and letting the scheduler fall back to polling. */
    private static final int MAX_RECONNECTS = 3;
    private static final long[] RECONNECT_DELAYS_MS = {2_000, 5_000, 15_000};

    /**
     * Runs the listener and, if its stream ends on its own (network blip, server restart, remote process
     * exit), reconnects in place a few times before giving up. A short glitch therefore no longer flips the task
     * to "polling only" and back; only a connection that stays down does.
     */
    private void runListener(Credential cred, String rawTargetPath, RemoteOs os, ListenerHandle handle) {
        try {
            int attempt = 0;
            while (!handle.closed) {
                boolean retry = runListenerOnce(cred, rawTargetPath, os, handle, attempt);
                if (!retry || handle.closed || attempt >= MAX_RECONNECTS) break;
                setReason(handle, "connection lost - reconnecting (attempt " + (attempt + 1) + " of "
                        + MAX_RECONNECTS + ")...");
                try { Thread.sleep(RECONNECT_DELAYS_MS[attempt]); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                attempt++;
            }
        } finally {
            synchronized (subscriptionLock) {
                listenersByKey.remove(handle.key, handle);
                for (String id : handle.subscribers) activeByTaskId.remove(id, handle);
            }
        }
    }

    /** One connection of the listener. Returns true if it ended unexpectedly and is worth retrying. */
    private boolean runListenerOnce(Credential cred, String rawTargetPath, RemoteOs os,
                                    ListenerHandle handle, int attempt) {
        final String taskId = handle.ownerTaskId;   // the task this connection is logged under; every subscriber gets the events
        Session session = null;
        ChannelExec channel = null;
        boolean opened = false;
        long openedAt = 0;
        String endReason = null;
        boolean retry = false;
        final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        final long[] lastOutputAt = {0};
        final String[] lastStep = {null};      // last breadcrumb the Windows listener printed
        final service.sftp.SessionLog sl = new service.sftp.SessionLog();   // transcript shown in the Event Monitor
        final Object conn = new Object();
        boolean armedHere = false;             // this connection has already reported ready
        final String armedWhy = attempt > 0 ? "the listener reconnected after the connection was lost" : "the listener was armed";
        try {
            JSch jsch = new JSch();
            session = jsch.getSession(cred.getUsername(), cred.getHost(), 22);
            session.setLogger(sl.jschLogger());
            sl.add("Connecting to " + cred.getHost() + ":22 as " + cred.getUsername() + " (remote " + os + ")"
                    + (attempt > 0 ? " - reconnect attempt " + attempt + " of " + MAX_RECONNECTS : ""));
            sl.add("Shared listener for " + rawTargetPath + ": " + handle.subscribers.size() + " task(s) subscribed");
            util.SshAuth.apply(jsch, session, cred.getPassword());
            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);
            util.SshHostKeys.apply(jsch, session);
            // Keep the long-lived channel alive through idle periods / NAT timeouts.
            session.setServerAliveInterval(15_000);
            session.setServerAliveCountMax(4);
            try {
                session.connect(15_000);
            } catch (Exception ex) {
                service.SshSessionEvents.failedDetached(taskId, "remote change listener (" + os + "), stays open while the watcher is armed", cred.getHost(),
                        cred.getUsername(), ex.getMessage(), sl.text());
                throw ex;
            }
            opened = true;
            openedAt = System.currentTimeMillis();
            service.SshSessionEvents.openedDetached(taskId, "remote change listener (" + os + "), stays open while the watcher is armed", cred.getHost(), cred.getUsername(), sl.text());
            handle.session = session;
            if (handle.closed) return false; // stopped/replaced while connecting: never start a remote process

            if (os == RemoteOs.WINDOWS) {
                endPreviousProcesses(session, handle, sl);
                if (handle.closed) return false;
            }

            channel = (ChannelExec) session.openChannel("exec");
            handle.channel = channel;
            String remoteCommand = os == RemoteOs.WINDOWS
                    ? buildWindowsCommand(rawTargetPath)
                    : buildLinuxCommand(rawTargetPath);
            sl.add("Starting remote change listener: " + (remoteCommand.length() > 300
                    ? remoteCommand.substring(0, 300) + "\u2026" : remoteCommand));
            channel.setCommand(remoteCommand);
            if (os != RemoteOs.WINDOWS) channel.setInputStream(handle.lease); // Linux: a byte every 10 s, end-of-input when we close; the Windows script does not read stdin
            channel.setErrStream(errBuf);       // what the remote process complained about, shown if the stream ends

            InputStream in = channel.getInputStream();
            channel.connect(15_000);
            if (handle.closed) return false; // finally{} tears the channel + session down
            sl.add("Listener running; waiting for change events");
            handle.connection = conn;
            if (os != RemoteOs.WINDOWS) {
                ScheduledExecutorService later = debounceExecutor;
                if (later != null) {
                    try {
                        later.schedule(() -> {
                            if (!handle.closed && handle.connection == conn) armed(handle, armedWhy);
                        }, LINUX_ARM_DELAY_MS(), TimeUnit.MILLISECONDS);
                    } catch (RejectedExecutionException stopping) {
                        // the watcher is being stopped: nothing to catch up for
                    }
                }
            }
            setReason(handle, "connected (" + os + "), waiting for first change event");

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            boolean sawAnyLine = false;
            String endNote = null;
            String line;
            while (!handle.closed && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith(STEP_PREFIX)) {      // progress marker, not a file event and not "output" for the checks below
                    lastStep[0] = line.substring(STEP_PREFIX.length());
                    sl.add("Remote listener step: " + lastStep[0]);
                    if (!armedHere && STEP_LISTENING.equals(lastStep[0])) {
                        armedHere = true;
                        armed(handle, armedWhy);
                    }
                    continue;
                }
                if (line.startsWith(RemoteProcessJanitor.PROC_PREFIX)) {   // the listener's own processes: remember them for the next arm
                    List<RemoteListenerRegistry.Proc> reported = RemoteProcessJanitor.parseReport(
                            line, handle.key, cred.getHost(), cred.getUsername(), System.currentTimeMillis());
                    if (reported.isEmpty()) {
                        sl.add("Remote listener sent a process report that could not be read: " + line);
                    } else {
                        processes.add(reported);
                        sl.add("Remote listener processes recorded: " + reported.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(", ")));
                        if (!armedHere) {   // the report is printed after the watcher is registered
                            armedHere = true;
                            armed(handle, armedWhy);
                        }
                    }
                    continue;
                }
                sawAnyLine = true;
                lastOutputAt[0] = System.currentTimeMillis();
                if (line.equals(HEARTBEAT)) continue;
                if (line.startsWith(END_PREFIX)) { endNote = line.substring(END_PREFIX.length()); continue; }
                if (line.contains(UNAVAILABLE_MARKER)) {
                    // Windows side now appends ":<real PS error text>" after the
                    // marker (path-not-found message, or $_.Exception.Message
                    // from the catch block) — surface that instead of guessing,
                    // so "why is this unsupported" has a real answer instead of
                    // a generic "missing/inaccessible, or exec refused".
                    int markerIdx = line.indexOf(UNAVAILABLE_MARKER);
                    String extra = line.substring(markerIdx + UNAVAILABLE_MARKER.length());
                    if (extra.startsWith(":")) extra = extra.substring(1);
                    extra = extra.trim();
                    String reason;
                    if (os == RemoteOs.WINDOWS) {
                        reason = extra.isEmpty()
                                ? "remote PowerShell couldn't watch the path (missing/inaccessible, or exec refused)"
                                : "remote PowerShell reported: " + extra;
                    } else {
                        reason = "remote host has no inotifywait installed (or exec was refused)";
                    }
                    sl.add("Remote reported: " + reason);
                    log.info("Watcher task " + taskId + ": " + reason + "; falling back to scheduled polling only for "
                            + (UNSUPPORTED_RETRY_MS / 60000) + " min.");
                    markUnsupported(handle, reason);
                    break;
                }
                // Each line IS the changed filename (inotifywait's --format '%f',
                // or the PowerShell script's $e.SourceEventArgs.Name) — pass it
                // straight through instead of discarding it, so the transfer step
                // can fetch exactly this file instead of re-deriving "what's new"
                // from a baseline-filtered directory scan.
                setReason(handle, "connected (" + os + "), streaming remote change events");
                sl.add("Remote says changed: " + line);
                fanOut(handle, line);
            }
            long upNow = System.currentTimeMillis() - openedAt;
            // Silence from the very start means the server refused the exec channel. A stream that ended later
            // (even before any file changed) was a working listener that got cut - that is a drop, not "unsupported".
            if ((sawAnyLine || upNow >= 10_000) && !handle.closed && !handle.unsupported) {
                // The remote side ended the stream by itself. Say exactly what we know about why.
                long up = System.currentTimeMillis() - openedAt;
                long quiet = lastOutputAt[0] == 0 ? up : System.currentTimeMillis() - lastOutputAt[0];
                int exit = channel.getExitStatus();
                String err = plainStderr(errBuf.toString(StandardCharsets.UTF_8).trim());
                if (err.length() > 600) err = err.substring(0, 600) + "\u2026";
                endReason = "the remote listener ended after " + (up / 1000) + " s (last output " + (quiet / 1000) + " s ago, "
                        + (exit >= 0 ? "exit status " + exit
                           : session.isConnected() ? "no exit status yet, but the SSH session is still connected, so the listener stopped by itself"
                           : "no exit status - the connection was cut")
                        + (endNote == null ? "" : ", the listener's last words: \"" + endNote + "\"")
                        + (err.isEmpty() ? "" : ", it said: " + err) + ")";
                sl.add("Stream ended: " + endReason + "; session " + (session.isConnected() ? "still connected" : "disconnected"));
                retry = true;
            }
            if (!sawAnyLine && upNow < 10_000 && !handle.closed) {
                // The stream ended with no event and no heartbeat. Two very different causes: the server refused the
                // exec channel (the script never started), or the script started and its process was ended early.
                // The listener's breadcrumbs tell them apart.
                for (int i = 0; i < 20 && !channel.isClosed(); i++) Thread.sleep(50);  // exit status can trail the end of the stream
                String err0 = plainStderr(errBuf.toString(StandardCharsets.UTF_8).trim());
                if (err0.length() > 600) err0 = err0.substring(0, 600) + "\u2026";
                int exit0 = channel.getExitStatus();
                boolean connected = session.isConnected();
                String reason;
                if (lastStep[0] != null) {
                    reason = "remote listener (" + os + ") started but its process ended after " + (upNow / 1000) + " s, last step reached: \""
                            + lastStep[0] + "\"" + (exit0 >= 0 ? ", exit status " + exit0 : ", no exit status")
                            + ", SSH session " + (connected ? "still connected" : "disconnected")
                            + (err0.isEmpty() ? ", nothing on stderr" : ", the remote shell said: " + err0)
                            + ". Something on the server ended it: another listener for the same folder, antivirus/EDR, or an error";
                } else {
                    reason = "remote exec (" + os + ") produced no output within " + (upNow / 1000) + " s"
                            + (exit0 >= 0 ? " (exit status " + exit0 + ")" : "")
                            + (err0.isEmpty() ? " and wrote nothing to stderr - the SSH server may have refused the exec channel (some SFTP-only servers do)"
                                              : "; the remote shell said: " + err0);
                }
                log.info("Watcher task " + taskId + ": " + reason + "; falling back to scheduled polling only.");
                sl.add(reason);
                markUnsupported(handle, reason);
                endReason = lastStep[0] != null ? "remote listener ended early after step \"" + lastStep[0] + "\"" : "remote exec produced no output";
            }
        } catch (Exception e) {
            endReason = "error: " + e.getMessage();
            sl.add("Error: " + e.getMessage());
            if (!handle.closed) {
                if (opened && attempt < MAX_RECONNECTS) {
                    retry = true;       // a live connection dropped: try again before declaring polling-only
                } else {
                    String reason = "connection failed (" + os + "): " + e.getMessage();
                    log.info("Watcher task " + taskId + ": remote push listener unavailable (" + e.getMessage()
                            + "); falling back to scheduled polling only.");
                    markUnsupported(handle, reason);
                }
            }
        } finally {
            handle.armed = false;
            if (handle.connection == conn) handle.connection = null;
            if (channel != null && channel.isConnected()) channel.disconnect();
            if (session != null && session.isConnected()) session.disconnect();
            if (opened) {
                service.SshSessionEvents.closedDetached(taskId, "remote change listener (" + os + "), stays open while the watcher is armed", cred.getHost(),
                        cred.getUsername(),
                        (handle.closed ? "watcher stopped or reconfigured"
                                : endReason != null ? endReason : "remote side ended the stream")
                                + (retry && !handle.closed && attempt < MAX_RECONNECTS
                                        ? " \u2014 reconnecting (attempt " + (attempt + 1) + " of " + MAX_RECONNECTS + ")" : ""),
                        System.currentTimeMillis() - openedAt, sl.text());
            }
        }
        return retry && !handle.closed;
    }

    /**
     * Windows: before arming, ends the listener processes an earlier connection for this folder left behind. Only
     * processes this service recorded are touched. Never keeps the listener from arming.
     */
    private void endPreviousProcesses(Session session, ListenerHandle handle, service.sftp.SessionLog sl) {
        try {
            RemoteProcessJanitor.endPrevious(processes, handle.key, (command, timeoutMs) -> runRemote(session, command, timeoutMs),
                    sl::add, 10_000);
        } catch (Exception e) {
            sl.add("Checking for earlier listener processes failed: " + e.getMessage());
        }
    }

    /** Runs one command on the server over this session and returns what it printed; fails if it takes longer than {@code timeoutMs}. */
    private static List<String> runRemote(Session session, String command, long timeoutMs) throws Exception {
        ChannelExec ch = (ChannelExec) session.openChannel("exec");
        try {
            ch.setCommand(command);
            ch.setErrStream(new ByteArrayOutputStream());
            InputStream in = ch.getInputStream();
            ch.connect(10_000);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                while (in.available() > 0) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    out.write(buf, 0, n);
                }
                if (ch.isClosed()) {
                    while (in.available() > 0) {
                        int n = in.read(buf);
                        if (n < 0) break;
                        out.write(buf, 0, n);
                    }
                    break;
                }
                if (System.currentTimeMillis() > deadline) throw new TimeoutException("no answer within " + (timeoutMs / 1000) + " s");
                Thread.sleep(50);
            }
            return Arrays.asList(out.toString(StandardCharsets.UTF_8).split("\\r?\\n"));
        } finally {
            if (ch.isConnected()) ch.disconnect();
        }
    }

    // ─── Command builders ───────────────────────────────────────────────────

    /** Linux: guarded single command (see {@code resources/scripts/watcher/linux/listener.sh}). */
    private String buildLinuxCommand(String rawTargetPath) {
        return ListenerScripts.linux(normalizeLinuxPath(rawTargetPath), HEARTBEAT, UNAVAILABLE_MARKER);
    }

    /** Windows: PowerShell around System.IO.FileSystemWatcher, sent with -EncodedCommand (see {@code resources/scripts/watcher/windows/}). */
    private String buildWindowsCommand(String rawTargetPath) {
        return ListenerScripts.windows(normalizeWindowsPath(rawTargetPath), HEARTBEAT, END_PREFIX, STEP_PREFIX,
                RemoteProcessJanitor.PROC_PREFIX, UNAVAILABLE_MARKER);
    }

    /** Windows PowerShell writes progress and errors to stderr as CLIXML; keep only the error text, drop progress records. */
    static String plainStderr(String raw) {
        if (raw == null || !raw.startsWith("#< CLIXML")) return raw == null ? "" : raw;
        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<S S=\"Error\">(.*?)</S>", java.util.regex.Pattern.DOTALL).matcher(raw);
        while (m.find()) sb.append(m.group(1).replace("_x000D__x000A_", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").trim()).append(' ');
        return sb.toString().trim();
    }

    /** The listener cannot work here: every task on it falls back to polling for the backoff window. */
    void markUnsupported(ListenerHandle handle, String reason) {
        handle.unsupported = true;
        handle.state = reason;
        long until = System.currentTimeMillis() + UNSUPPORTED_RETRY_MS;
        for (String id : handle.subscribers) {
            unsupportedUntil.put(id, until);
            reasonByTaskId.put(id, reason);
        }
    }

    private void setReason(ListenerHandle handle, String reason) {
        handle.state = reason;
        for (String id : handle.subscribers) reasonByTaskId.put(id, reason);
    }

    /** A changed file name arrived on a shared listener: every subscribed task is woken for it (each filters for itself). */
    void fanOut(ListenerHandle handle, String fileName) {
        for (String id : handle.subscribers) scheduleFire(id, fileName);
    }

    private void scheduleFire(String taskId, String fileName) {
        // Merge filenames across the whole debounce window the same way
        // LocalWatchManager does, so several rapid remote changes fire once
        // with every name attached rather than clobbering each other.
        if (fileName != null && !fileName.isEmpty()) {
            pendingNamesByTaskId.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).add(fileName);
        }
        if (debounceExecutor == null || debounceExecutor.isShutdown()) return;
        // Cancel the previous timer and start a fresh one: exactly one wake-up,
        // SETTLE_MILLIS after the *last* event in the burst.
        pendingFires.compute(taskId, (k, prev) -> {
            if (prev != null) prev.cancel(false);
            return debounceExecutor.schedule(() -> {
                pendingFires.remove(taskId);
                Set<String> fired = pendingNamesByTaskId.remove(taskId);
                // Nothing left to report (e.g. the listener was stopped mid-window):
                // never wake the task with an empty name set — that would force a
                // baseline scan that can only find "nothing new" and log a skip.
                if (fired == null || fired.isEmpty()) return;
                try {
                    onSettled.accept(taskId, fired);
                } catch (Exception e) {
                    log.warning("Watcher task " + taskId + ": onSettled callback failed: " + e.getMessage());
                }
            }, SETTLE_MILLIS, TimeUnit.MILLISECONDS);
        });
    }

    Credential resolveCredential(ScheduledTask task) {
        String uname = task.getTargetUsername();
        if (uname != null && !uname.isEmpty()) {
            return storage.loadCredentialByName(uname);
        }
        if (task.getTargetCredentialId() != null && !task.getTargetCredentialId().isEmpty()) {
            return storage.loadAllCredentials().stream()
                    .filter(x -> x.getId().equals(task.getTargetCredentialId()))
                    .findFirst().orElse(null);
        }
        return null;
    }

    enum RemoteOs { LINUX, WINDOWS }

    static RemoteOs resolveOs(Credential cred) {
        if (cred == null || cred.getOsType() == null) return null;
        String os = cred.getOsType().trim().toUpperCase(Locale.ROOT);
        if (os.equals("LINUX")) return RemoteOs.LINUX;
        if (os.equals("WINDOWS")) return RemoteOs.WINDOWS;
        return null;
    }

    // ─── Path helpers ───────────────────────────────────────────────────────

    /** POSIX-style path for the Linux/inotifywait branch (mirrors
     *  RemoteFileMetadataServiceFactory's SFTP path normalization). */
    static String normalizeLinuxPath(String path) {
        if (path == null) return "/";
        String normalized = path.trim().replace("\\", "/").replaceAll("/+", "/");
        if (normalized.matches("^[A-Za-z]:/.*")) normalized = "/" + normalized;
        if (normalized.endsWith("*")) normalized = normalized.substring(0, normalized.lastIndexOf('/') + 1);
        return normalized.isEmpty() ? "/" : normalized;
    }

    /** Native Windows-style path (backslashes, drive letter as-is — NOT the
     *  SFTP-subsystem "/C:/..." form the Linux/SFTP paths use) for the
     *  PowerShell branch, since this runs as a plain shell command, not
     *  through the sftp subsystem.
     *
     *  <p>Critically, {@code task.getTargetPath()} is very likely already in
     *  that SFTP-subsystem form — the "Browse..." button in the task dialog
     *  goes through {@code SftpBrowseService}, which talks to the remote
     *  over the sftp subsystem, and Win32-OpenSSH's sftp-server represents
     *  Windows paths with a leading slash before the drive letter
     *  ("/C:/Daily Changes"), matching what
     *  {@code RemoteFileMetadataServiceFactory} already normalizes to for
     *  real transfers. A plain PowerShell command isn't going through the
     *  sftp subsystem, so that leading slash has to come back off first —
     *  otherwise "/C:/Daily Changes" becomes the invalid "\C:\Daily Changes"
     *  after the slash-to-backslash conversion below, {@code Test-Path}
     *  correctly reports it doesn't exist, and every single Windows INBOUND
     *  watcher task ends up POLLING_ONLY_UNSUPPORTED regardless of whether
     *  the directory is real. */
    static String normalizeWindowsPath(String path) {
        if (path == null) return "";
        String normalized = path.trim();
        if (normalized.matches("^/[A-Za-z]:(/.*)?$")) {
            normalized = normalized.substring(1); // drop the sftp-subsystem leading slash
        }
        normalized = normalized.replace("/", "\\").replaceAll("\\\\+", "\\\\");
        if (normalized.endsWith("*")) {
            int idx = normalized.lastIndexOf('\\');
            normalized = idx >= 0 ? normalized.substring(0, idx) : normalized;
        }
        if (normalized.length() > 3 && normalized.endsWith("\\")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * Identity of a remote listener: two tasks with the same key share one. Same OS, server, user and folder, where the
     * folder is compared the way the OS does (Windows ignores case and slash direction, a trailing separator or a
     * trailing wildcard; Linux ignores a trailing slash); the SSH port is fixed at 22 everywhere in this class.
     */
    static String listenerKey(Credential cred, RemoteOs os, String rawTargetPath) {
        boolean windows = os == RemoteOs.WINDOWS;
        String path = windows ? normalizeWindowsPath(rawTargetPath).toLowerCase(Locale.ROOT) : normalizeLinuxPath(rawTargetPath);
        if (!windows && path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String host = cred.getHost() == null ? "" : cred.getHost().trim().toLowerCase(Locale.ROOT);
        String user = cred.getUsername() == null ? "" : cred.getUsername().trim();
        if (windows) user = user.toLowerCase(Locale.ROOT);
        return os + "|" + host + "|22|" + user + "|" + path;
    }

    /** The remote listener's stdin: one newline every 10 s (its lease), then end-of-input once closed. */
    static final class LeaseStream extends InputStream {
        private final Object lock = new Object();
        private volatile boolean ended;

        @Override public int read(byte[] b, int off, int len) {
            synchronized (lock) {
                if (!ended) {
                    try { lock.wait(10_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return -1; }
                }
            }
            if (ended) return -1;
            b[off] = '\n';
            return 1;
        }

        @Override public int read() {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0];
        }

        void end() { synchronized (lock) { ended = true; lock.notifyAll(); } }
    }

    /** One remote listener (and its SSH connection), shared by every task in {@link #subscribers}. */
    static final class ListenerHandle {
        final String key;
        final String ownerTaskId;   // first subscriber: the task this connection's session events are logged under
        final String path;          // folder as the owner wrote it; the remote command is built from it
        final Set<String> subscribers = ConcurrentHashMap.newKeySet();
        final LeaseStream lease = new LeaseStream();
        volatile boolean closed = false;
        volatile boolean unsupported = false;
        volatile boolean armed = false;     // watching right now (false while connecting or between connections)
        volatile Object connection;         // identifies the current connection, so a delayed arm cannot belong to an older one
        volatile String state;              // latest human-readable state, given to tasks that join later
        volatile Session session;
        volatile ChannelExec channel;

        ListenerHandle(String key, String ownerTaskId, String path) {
            this.key = key;
            this.ownerTaskId = ownerTaskId;
            this.path = path;
        }

        /** Can a new subscriber rely on this listener? */
        boolean usable() {
            return !closed && !unsupported;
        }

        void close() {
            closed = true;
            // Tell the remote process to stop FIRST (end-of-input), and give it a moment to exit and take its
            // cmd.exe/shell parent with it; only then drop the connection. Disconnecting straight away is what
            // used to leave powershell.exe / cmd.exe / inotifywait running on the server.
            lease.end();
            try {
                long until = System.currentTimeMillis() + 1500;
                while (channel != null && !channel.isClosed() && System.currentTimeMillis() < until) Thread.sleep(50);
            } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            try {
                if (channel != null && channel.isConnected()) channel.disconnect();
            } catch (Exception ignored) {}
            try {
                if (session != null && session.isConnected()) session.disconnect();
            } catch (Exception ignored) {}
        }
    }
}
