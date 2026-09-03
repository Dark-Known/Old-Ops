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
import java.util.function.Consumer;
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
    private static final long SETTLE_MILLIS = 2000L;
    private static final String UNAVAILABLE_MARKER = "__WATCH_UNAVAILABLE__";

    private final XmlStorageService storage;
    private final Consumer<String> onSettled; // taskId -> wake the task up now

    private ExecutorService listenerExecutor;
    private ScheduledExecutorService debounceExecutor;

    private final Map<String, ListenerHandle> activeByTaskId = new ConcurrentHashMap<>();
    private final Map<String, Long> unsupportedUntil = new ConcurrentHashMap<>();
    // taskId -> a short human-readable reason for the *current* state — "never
    // attempted" vs. "tried and failed, here's why" vs. "connected and
    // listening" are otherwise indistinguishable from the UI's point of view.
    private final Map<String, String> reasonByTaskId = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    public RemotePushWatcher(XmlStorageService storage, Consumer<String> onSettled) {
        this.storage = storage;
        this.onSettled = onSettled;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        listenerExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "remote-push-listener");
            t.setDaemon(true);
            return t;
        });
        debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "remote-push-debounce");
            t.setDaemon(true);
            return t;
        });
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
    }

    private void startListener(ScheduledTask task, Credential cred, RemoteOs os) {
        stopListener(task.getId());
        ListenerHandle handle = new ListenerHandle(task.getId(), task.getTargetPath());
        activeByTaskId.put(task.getId(), handle);
        reasonByTaskId.put(task.getId(), "connecting (" + os + ")...");
        listenerExecutor.submit(() -> runListener(task.getId(), cred, task.getTargetPath(), os, handle));
    }

    private void runListener(String taskId, Credential cred, String rawTargetPath, RemoteOs os, ListenerHandle handle) {
        Session session = null;
        ChannelExec channel = null;
        try {
            JSch jsch = new JSch();
            session = jsch.getSession(cred.getUsername(), cred.getHost(), 22);
            session.setPassword(cred.getPassword());
            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);
            // Keep the long-lived channel alive through idle periods / NAT timeouts.
            session.setServerAliveInterval(15_000);
            session.setServerAliveCountMax(4);
            session.connect(15_000);
            handle.session = session;

            channel = (ChannelExec) session.openChannel("exec");
            handle.channel = channel;
            channel.setCommand(os == RemoteOs.WINDOWS
                    ? buildWindowsCommand(rawTargetPath)
                    : buildLinuxCommand(rawTargetPath));
            channel.setInputStream(null);
            channel.setErrStream(new ByteArrayOutputStream()); // discard; failures surface via the marker or a closed stream

            InputStream in = channel.getInputStream();
            channel.connect(15_000);
            reasonByTaskId.put(taskId, "connected (" + os + "), waiting for first change event");

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            boolean sawAnyLine = false;
            String line;
            while (!handle.closed && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                sawAnyLine = true;
                if (line.contains(UNAVAILABLE_MARKER)) {
                    String reason = os == RemoteOs.WINDOWS
                            ? "remote PowerShell couldn't watch the path (missing/inaccessible, or exec refused)"
                            : "remote host has no inotifywait installed (or exec was refused)";
                    log.info("Watcher task " + taskId + ": " + reason + "; falling back to scheduled polling only for "
                            + (UNSUPPORTED_RETRY_MS / 60000) + " min.");
                    markUnsupported(taskId, reason);
                    break;
                }
                // Any filename line = something changed; debounce + wake the task.
                reasonByTaskId.put(taskId, "connected (" + os + "), streaming remote change events");
                scheduleFire(taskId);
            }
            if (!sawAnyLine && !handle.closed) {
                // Channel closed with no output at all — most likely exec was
                // refused outright (e.g. an SFTP-subsystem-only server).
                String reason = "remote exec (" + os + ") produced no output — most likely the SSH server refused"
                        + " the exec channel (some SFTP-only servers do)";
                log.info("Watcher task " + taskId + ": " + reason + "; falling back to scheduled polling only.");
                markUnsupported(taskId, reason);
            }
        } catch (Exception e) {
            if (!handle.closed) {
                String reason = "connection failed (" + os + "): " + e.getMessage();
                log.info("Watcher task " + taskId + ": remote push listener unavailable (" + e.getMessage()
                        + "); falling back to scheduled polling only.");
                markUnsupported(taskId, reason);
            }
        } finally {
            if (channel != null && channel.isConnected()) channel.disconnect();
            if (session != null && session.isConnected()) session.disconnect();
            activeByTaskId.remove(taskId, handle);
        }
    }

    // ─── Command builders ───────────────────────────────────────────────────

    /** Linux: guarded single command — fails closed with the sentinel if
     *  inotifywait isn't installed, rather than letting the shell's own
     *  "command not found" text on stderr look like a filename. */
    private String buildLinuxCommand(String rawTargetPath) {
        String remoteDir = normalizeLinuxPath(rawTargetPath);
        return "sh -c \"command -v inotifywait >/dev/null 2>&1 && "
                + "exec inotifywait -m -q -e create -e close_write -e moved_to "
                + "--format '%f' " + shellQuoteSingle(remoteDir)
                + " || echo " + UNAVAILABLE_MARKER + "\"";
    }

    /**
     * Windows: a small PowerShell loop around {@link java.nio.file}'s remote
     * cousin, {@code System.IO.FileSystemWatcher}. Sent via
     * {@code -EncodedCommand} (base64 of the UTF-16LE script) instead of an
     * inline string — this is the standard way to hand PowerShell a
     * multi-line script through a transport (like SSH exec) that only
     * carries a single command-line string, and it avoids the otherwise
     * painful problem of a Windows OpenSSH server passing the command
     * through {@code cmd.exe} first (its default) before PowerShell ever
     * sees it, which would otherwise mangle quoting.
     *
     * <p>The script watches for Created/Changed/Renamed and prints just the
     * changed file's name per event — the Java side treats any line the
     * same way it treats an inotifywait line: "something changed, go check."
     */
    private String buildWindowsCommand(String rawTargetPath) {
        String dir = normalizeWindowsPath(rawTargetPath);
        String script =
                "$ErrorActionPreference = 'Stop'\n" +
                "try {\n" +
                "  $p = '" + psQuoteSingle(dir) + "'\n" +
                "  if (-not (Test-Path -LiteralPath $p -PathType Container)) {\n" +
                "    Write-Output '" + UNAVAILABLE_MARKER + "'\n" +
                "    exit\n" +
                "  }\n" +
                "  $w = New-Object System.IO.FileSystemWatcher $p\n" +
                "  $w.IncludeSubdirectories = $false\n" +
                "  $w.EnableRaisingEvents = $true\n" +
                "  Register-ObjectEvent -InputObject $w -EventName Created -SourceIdentifier PushCreate | Out-Null\n" +
                "  Register-ObjectEvent -InputObject $w -EventName Changed -SourceIdentifier PushChange | Out-Null\n" +
                "  Register-ObjectEvent -InputObject $w -EventName Renamed -SourceIdentifier PushRename | Out-Null\n" +
                "  while ($true) {\n" +
                "    $e = Wait-Event -SourceIdentifier PushCreate,PushChange,PushRename\n" +
                "    Write-Output $e.SourceEventArgs.Name\n" +
                "    Remove-Event -EventIdentifier $e.EventIdentifier\n" +
                "  }\n" +
                "} catch {\n" +
                "  Write-Output '" + UNAVAILABLE_MARKER + "'\n" +
                "}\n";

        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        // -NonInteractive + -NoProfile keep this fast and free of profile-script
        // noise on stdout; -ExecutionPolicy Bypass is scoped to this process only
        // (does not alter the host's persistent policy).
        return "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand " + encoded;
    }

    private void markUnsupported(String taskId, String reason) {
        unsupportedUntil.put(taskId, System.currentTimeMillis() + UNSUPPORTED_RETRY_MS);
        reasonByTaskId.put(taskId, reason);
    }

    private void scheduleFire(String taskId) {
        debounceExecutor.schedule(() -> {
            try {
                onSettled.accept(taskId);
            } catch (Exception e) {
                log.warning("Watcher task " + taskId + ": onSettled callback failed: " + e.getMessage());
            }
        }, SETTLE_MILLIS, TimeUnit.MILLISECONDS);
    }

    private Credential resolveCredential(ScheduledTask task) {
        String uname = task.getTargetUsername();
        if (uname != null && !uname.isEmpty()) {
            return storage.loadCredentialByUsername(uname);
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
     *  through the sftp subsystem. */
    private static String normalizeWindowsPath(String path) {
        if (path == null) return "";
        String normalized = path.trim().replace("/", "\\").replaceAll("\\\\+", "\\\\");
        if (normalized.endsWith("*")) {
            int idx = normalized.lastIndexOf('\\');
            normalized = idx >= 0 ? normalized.substring(0, idx) : normalized;
        }
        if (normalized.length() > 3 && normalized.endsWith("\\")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String shellQuoteSingle(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    /** Escapes a value for embedding inside a PowerShell single-quoted string
     *  literal — PowerShell's own quoting rule is simply "double any single quote". */
    private static String psQuoteSingle(String value) {
        return value.replace("'", "''");
    }

    private static final class ListenerHandle {
        final String taskId;
        final String path;
        volatile boolean closed = false;
        volatile Session session;
        volatile ChannelExec channel;

        ListenerHandle(String taskId, String path) {
            this.taskId = taskId;
            this.path = path;
        }

        boolean matches(ScheduledTask t) {
            return path.equals(t.getTargetPath());
        }

        void close() {
            closed = true;
            try {
                if (channel != null && channel.isConnected()) channel.disconnect();
            } catch (Exception ignored) {}
            try {
                if (session != null && session.isConnected()) session.disconnect();
            } catch (Exception ignored) {}
        }
    }
}
