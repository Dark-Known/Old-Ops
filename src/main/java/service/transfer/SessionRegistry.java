package service.transfer;

import service.sftp.SftpClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Which remote sessions are running for which task, so a task can be cancelled and the UI can show
 * live session counts. Shared by every engine.
 *
 * <p>A task can have several sessions at once (one per batch when batches run concurrently). Backup
 * helper sessions run under pseudo task ids such as {@code "backup-mkdir"}; engines also register them
 * under the real task's id (see {@link #bindRealTask}) so that Cancel stops them too.
 */
public final class SessionRegistry {

    private final ConcurrentMap<String, Set<Process>> processes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<SftpClient>> clients = new ConcurrentHashMap<>();
    private final ThreadLocal<String> realTaskId = new ThreadLocal<>();

    // ── Which task this thread is working for ───────────────────────────────

    /** Marks the current thread as running the real task {@code taskId}. Pair with {@link #unbindRealTask()} in a finally block. */
    public void bindRealTask(String taskId) { realTaskId.set(taskId); }

    public void unbindRealTask() { realTaskId.remove(); }

    /** The real task id this thread is running, or null. */
    public String realTaskId() { return realTaskId.get(); }

    // ── Registering sessions ────────────────────────────────────────────────

    public void addProcess(String taskId, Process p) {
        if (taskId != null) processes.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).add(p);
    }

    public void removeProcess(String taskId, Process p) {
        if (taskId == null || p == null) return;   // p is null when the process never started
        Set<Process> set = processes.get(taskId);
        if (set != null) {
            set.remove(p);
            if (set.isEmpty()) processes.remove(taskId, set);
        }
    }

    public void addClient(String taskId, SftpClient c) {
        if (taskId != null) clients.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).add(c);
    }

    public void removeClient(String taskId, SftpClient c) {
        if (taskId == null) return;
        Set<SftpClient> set = clients.get(taskId);
        if (set != null) {
            set.remove(c);
            if (set.isEmpty()) clients.remove(taskId, set);
        }
    }

    // ── Cancelling ──────────────────────────────────────────────────────────

    /** Stops every session running for {@code taskId}. Returns whether anything was stopped. */
    public boolean cancel(String taskId) {
        if (taskId == null) return false;
        boolean any = false;
        Set<SftpClient> cs = clients.remove(taskId);
        if (cs != null) {
            for (SftpClient c : cs) {
                try { c.cancel(); any = true; } catch (Exception ignored) {}
            }
        }
        Set<Process> procs = processes.remove(taskId);
        if (procs == null || procs.isEmpty()) return any;
        for (Process p : procs) {
            try { p.destroyForcibly(); any = true; }
            catch (Exception ignored) {}
        }
        return any;
    }

    // ── Counting ────────────────────────────────────────────────────────────
    // A session is either an external process (WinSCP) or an in-process SFTP client, so both count.
    // A backup helper is registered under its pseudo id and the real task's id; the total counts it once.

    /** Sessions in flight for {@code taskId}; cheap, safe to poll from the UI. */
    public int activeSessionCount(String taskId) {
        if (taskId == null) return 0;
        Set<Process> procs = processes.get(taskId);
        Set<SftpClient> cs = clients.get(taskId);
        return (procs == null ? 0 : procs.size()) + (cs == null ? 0 : cs.size());
    }

    /** Sessions in flight across every task, each session counted once. */
    public int totalActiveSessionCount() {
        Set<Object> distinct = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Set<Process> procs : processes.values()) distinct.addAll(procs);
        for (Set<SftpClient> cs : clients.values()) distinct.addAll(cs);
        return distinct.size();
    }

    /** Every task with a non-zero session count, for export to the shared status file. */
    public Map<String, Integer> activeSessionCountsByTask() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String id : processes.keySet()) counts.put(id, activeSessionCount(id));
        for (String id : clients.keySet()) counts.put(id, activeSessionCount(id));
        counts.values().removeIf(n -> n <= 0);
        return counts;
    }
}
