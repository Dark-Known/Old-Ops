package service.watch;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import model.Credential;
import model.ScheduledTask;
import service.XmlStorageService;

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

    private final XmlStorageService storage;
    // taskId, changedFileNames -> wake the task up now, naming exactly the file(s)
    // inotifywait/FileSystemWatcher reported.
    private final BiConsumer<String, Set<String>> onSettled;

    private ExecutorService listenerExecutor;
    private ScheduledExecutorService debounceExecutor;

    private final Map<String, ListenerHandle> activeByTaskId = new ConcurrentHashMap<>();
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

    public RemotePushWatcher(XmlStorageService storage, BiConsumer<String, Set<String>> onSettled) {
        this.storage = storage;
        this.onSettled = onSettled;
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
        for (ListenerHandle h : activeByTaskId.values()) h.close();
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
            ListenerHandle existing = activeByTaskId.get(t.getId());
            if (existing != null && existing.matches(t)) continue; // already listening, config unchanged

            startListener(t, cred, os);
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
        stopListener(task.getId());
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
        startListener(task, cred, os);
    }

    private void stopListener(String taskId) {
        ListenerHandle h = activeByTaskId.remove(taskId);
        if (h != null) h.close();
        ScheduledFuture<?> pending = pendingFires.remove(taskId);
        if (pending != null) pending.cancel(false);
        pendingNamesByTaskId.remove(taskId);
    }

    private void startListener(ScheduledTask task, Credential cred, RemoteOs os) {
        stopListener(task.getId());
        ListenerHandle handle = new ListenerHandle(task.getId(), task.getTargetPath());
        activeByTaskId.put(task.getId(), handle);
        reasonByTaskId.put(task.getId(), "connecting (" + os + ")...");
        listenerExecutor.submit(() -> runListener(task.getId(), cred, task.getTargetPath(), os, handle));
    }

    /** Connection attempts before giving up and letting the scheduler fall back to polling. */
    private static final int MAX_RECONNECTS = 3;
    private static final long[] RECONNECT_DELAYS_MS = {2_000, 5_000, 15_000};

    /**
     * Runs the listener and, if its stream ends on its own (network blip, server restart, remote process
     * exit), reconnects in place a few times before giving up. A short glitch therefore no longer flips the task
     * to "polling only" and back; only a connection that stays down does.
     */
    private void runListener(String taskId, Credential cred, String rawTargetPath, RemoteOs os, ListenerHandle handle) {
        try {
            int attempt = 0;
            while (!handle.closed) {
                boolean retry = runListenerOnce(taskId, cred, rawTargetPath, os, handle, attempt);
                if (!retry || handle.closed || attempt >= MAX_RECONNECTS) break;
                reasonByTaskId.put(taskId, "connection lost - reconnecting (attempt " + (attempt + 1) + " of "
                        + MAX_RECONNECTS + ")...");
                try { Thread.sleep(RECONNECT_DELAYS_MS[attempt]); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                attempt++;
            }
        } finally {
            activeByTaskId.remove(taskId, handle);
        }
    }

    /** One connection of the listener. Returns true if it ended unexpectedly and is worth retrying. */
    private boolean runListenerOnce(String taskId, Credential cred, String rawTargetPath, RemoteOs os,
                                    ListenerHandle handle, int attempt) {
        Session session = null;
        ChannelExec channel = null;
        boolean opened = false;
        long openedAt = 0;
        String endReason = null;
        boolean retry = false;
        final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        final long[] lastOutputAt = {0};
        final service.sftp.SessionLog sl = new service.sftp.SessionLog();   // transcript shown in the Event Monitor
        try {
            JSch jsch = new JSch();
            session = jsch.getSession(cred.getUsername(), cred.getHost(), 22);
            session.setLogger(sl.jschLogger());
            sl.add("Connecting to " + cred.getHost() + ":22 as " + cred.getUsername() + " (remote " + os + ")"
                    + (attempt > 0 ? " - reconnect attempt " + attempt + " of " + MAX_RECONNECTS : ""));
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
            reasonByTaskId.put(taskId, "connected (" + os + "), waiting for first change event");

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            boolean sawAnyLine = false;
            String endNote = null;
            String line;
            while (!handle.closed && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
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
                    markUnsupported(taskId, reason);
                    break;
                }
                // Each line IS the changed filename (inotifywait's --format '%f',
                // or the PowerShell script's $e.SourceEventArgs.Name) — pass it
                // straight through instead of discarding it, so the transfer step
                // can fetch exactly this file instead of re-deriving "what's new"
                // from a baseline-filtered directory scan.
                reasonByTaskId.put(taskId, "connected (" + os + "), streaming remote change events");
                sl.add("Remote says changed: " + line);
                scheduleFire(taskId, line);
            }
            long upNow = System.currentTimeMillis() - openedAt;
            // Silence from the very start means the server refused the exec channel. A stream that ended later
            // (even before any file changed) was a working listener that got cut - that is a drop, not "unsupported".
            if ((sawAnyLine || upNow >= 10_000) && !handle.closed && !unsupportedBackoffActive(taskId)) {
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
                // Channel closed with no output at all — most likely exec was
                // refused outright (e.g. an SFTP-subsystem-only server).
                String err0 = plainStderr(errBuf.toString(StandardCharsets.UTF_8).trim());
                if (err0.length() > 600) err0 = err0.substring(0, 600) + "\u2026";
                int exit0 = channel.getExitStatus();
                String reason = "remote exec (" + os + ") produced no output within " + (upNow / 1000) + " s"
                        + (exit0 >= 0 ? " (exit status " + exit0 + ")" : "")
                        + (err0.isEmpty() ? " and wrote nothing to stderr - the SSH server may have refused the exec channel (some SFTP-only servers do)"
                                          : "; the remote shell said: " + err0);
                log.info("Watcher task " + taskId + ": " + reason + "; falling back to scheduled polling only.");
                sl.add(reason);
                markUnsupported(taskId, reason);
                endReason = "remote exec produced no output";
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
                    markUnsupported(taskId, reason);
                }
            }
        } finally {
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

    private boolean unsupportedBackoffActive(String taskId) {
        Long until = unsupportedUntil.get(taskId);
        return until != null && System.currentTimeMillis() < until;
    }

    // ─── Command builders ───────────────────────────────────────────────────

    /** Linux: guarded single command (see {@code resources/scripts/watcher/linux/listener.sh}). */
    private String buildLinuxCommand(String rawTargetPath) {
        return ListenerScripts.linux(normalizeLinuxPath(rawTargetPath), HEARTBEAT, UNAVAILABLE_MARKER);
    }

    /** Windows: PowerShell around System.IO.FileSystemWatcher, sent with -EncodedCommand (see {@code resources/scripts/watcher/windows/}). */
    private String buildWindowsCommand(String rawTargetPath) {
        return ListenerScripts.windows(normalizeWindowsPath(rawTargetPath), HEARTBEAT, END_PREFIX, UNAVAILABLE_MARKER);
    }

    /** Windows PowerShell writes progress and errors to stderr as CLIXML; keep only the error text, drop progress records. */
    static String plainStderr(String raw) {
        if (raw == null || !raw.startsWith("#< CLIXML")) return raw == null ? "" : raw;
        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<S S=\"Error\">(.*?)</S>", java.util.regex.Pattern.DOTALL).matcher(raw);
        while (m.find()) sb.append(m.group(1).replace("_x000D__x000A_", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").trim()).append(' ');
        return sb.toString().trim();
    }

    private void markUnsupported(String taskId, String reason) {
        unsupportedUntil.put(taskId, System.currentTimeMillis() + UNSUPPORTED_RETRY_MS);
        reasonByTaskId.put(taskId, reason);
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

    private Credential resolveCredential(ScheduledTask task) {
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

    private enum RemoteOs { LINUX, WINDOWS }

    private static RemoteOs resolveOs(Credential cred) {
        if (cred == null || cred.getOsType() == null) return null;
        String os = cred.getOsType().trim().toUpperCase(Locale.ROOT);
        if (os.equals("LINUX")) return RemoteOs.LINUX;
        if (os.equals("WINDOWS")) return RemoteOs.WINDOWS;
        return null;
    }

    // ─── Path helpers ───────────────────────────────────────────────────────

    /** POSIX-style path for the Linux/inotifywait branch (mirrors
     *  RemoteFileMetadataServiceFactory's SFTP path normalization). */
    private static String normalizeLinuxPath(String path) {
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
    private static String normalizeWindowsPath(String path) {
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

    private static final class ListenerHandle {
        final String taskId;
        final String path;
        volatile boolean closed = false;
        volatile Session session;
        volatile ChannelExec channel;
        final LeaseStream lease = new LeaseStream();

        ListenerHandle(String taskId, String path) {
            this.taskId = taskId;
            this.path = path;
        }

        boolean matches(ScheduledTask t) {
            return path.equals(t.getTargetPath());
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
