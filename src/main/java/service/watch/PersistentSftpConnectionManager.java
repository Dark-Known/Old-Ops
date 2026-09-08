package service.watch;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import model.Credential;

import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Manages the one SSH/SFTP session a watcher-triggered fire needs for its
 * duration — opened when the fire starts needing to talk to the server
 * (a stat check, a listing, or the transfer itself), reused across every
 * step <i>within that one fire</i> (stat → transfer, or multiple concurrent
 * batches each on their own channel — see below), and closed as soon as
 * that fire is done with it, successful or not.
 *
 * <p>This used to be a longer-lived, kept-warm-across-fires design — one
 * session held open per task, reused by every subsequent fire until an idle
 * timeout reaped it. That traded server-side cost for avoiding a repeated
 * handshake on every fire. It turned out to be the wrong trade: an idle SFTP
 * session isn't free on the <i>server</i> side — each one keeps its own
 * {@code sftp-server} child process alive under {@code sshd} for as long as
 * the session lives, and across several watcher-enabled tasks each holding
 * their own idle session, that adds up to real, needless memory on the
 * server. Now that the actual cause of slow watcher-triggered transfers (a
 * full directory listing on every fire) is fixed — see
 * {@code SftpRemoteFileMetadataService#statFile} — a fresh reconnect per
 * fire is just a normal, fast SSH handshake, so there's no longer a good
 * reason to hold a connection (and the server-side process behind it) open
 * between fires "just in case."
 *
 * <p>Callers are expected to call {@link #close(String)} themselves once a
 * fire is fully done with a task's session (success, failure, or "nothing to
 * do this time" all count) — see {@code TransferService}'s watcher transfer
 * methods. The idle-reap mechanism below still exists purely as a defensive
 * safety net for a code path that doesn't get to that {@code close()} call
 * (an unexpected exception, a crash mid-fire), not as the primary cleanup
 * mechanism anymore.
 *
 * <p><b>Multiple channels, one session:</b> when a single fire names enough
 * files to be worth parallelizing (see
 * {@code TransferService#executeWinScpWatcherOutbound} / {@code ...Inbound}),
 * each concurrent worker thread gets its own SFTP "slot" — a
 * {@link ChannelSftp} opened on that fire's one SSH connection's SFTP
 * subsystem — via {@link #acquireChannel(String, int, Credential)}. The SSH
 * protocol supports any number of channels multiplexed over a single
 * connection, so this scales worker-thread concurrency up within one fire
 * without opening a second SSH connection (a second handshake, a second
 * login, a second server-side process) per extra thread — still exactly one
 * session per fire, just more channels riding on it for that fire's
 * duration. Slot 0 is the same channel {@link #getChannel(String, Credential)}
 * returns, so a single-file fire (the common case) never pays for a slot it
 * doesn't use.
 *
 * <p>Thread-safe. A credential change (host/username/password edited in the
 * task) is detected and forces a clean reconnect rather than reusing a
 * session against the old target.
 */
public class PersistentSftpConnectionManager {

    private static final Logger log = Logger.getLogger(PersistentSftpConnectionManager.class.getName());

    /** How long a connection may sit completely unused before being proactively
     *  closed — a defensive safety net, not the primary cleanup mechanism; see
     *  the class javadoc. Callers are expected to {@link #close(String)}
     *  their own session once a fire is done with it, so in normal operation
     *  this reaper should rarely find anything left to reap. */
    private static final long IDLE_CLOSE_MILLIS = 10 * 60 * 1000L; // 10 minutes

    /** Connect timeout for establishing (or re-establishing) a connection, and
     *  for opening each additional channel on it. Paid once per task (plus
     *  once per additional concurrent slot the first time it's used) and then
     *  amortized across every subsequent watcher fire for that task, so —
     *  unlike the old per-event connect — this no longer sits directly on the
     *  "change detected → transfer starts" critical path on anything but the
     *  very first fire (or a reconnect after a real drop). */
    private static final int CONNECT_TIMEOUT_MILLIS = 8_000;

    /** One task's kept-warm SSH session plus however many SFTP channel slots
     *  have been opened on it so far (grown lazily, up to whatever concurrency
     *  a given fire actually asks for). */
    private static final class Entry {
        final Session session;
        final ConcurrentHashMap<Integer, ChannelSftp> slots = new ConcurrentHashMap<>();
        final String credentialFingerprint;
        volatile long lastUsedMillis;
        int connectCount;

        Entry(Session session, String fingerprint, int connectCount) {
            this.session = session;
            this.credentialFingerprint = fingerprint;
            this.connectCount = connectCount;
            this.lastUsedMillis = System.currentTimeMillis();
        }
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reaper;

    public PersistentSftpConnectionManager() {
        reaper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sftp-persistent-reaper");
            t.setDaemon(true);
            return t;
        });
        reaper.scheduleAtFixedRate(this::reapIdle, 1, 1, TimeUnit.MINUTES);
    }

    private void reapIdle() {
        long now = System.currentTimeMillis();
        for (java.util.Map.Entry<String, Entry> e : entries.entrySet()) {
            if (now - e.getValue().lastUsedMillis > IDLE_CLOSE_MILLIS) {
                if (entries.remove(e.getKey(), e.getValue())) {
                    closeQuietly(e.getValue());
                    log.info("Persistent SFTP connection for task " + e.getKey()
                            + " closed after " + (IDLE_CLOSE_MILLIS / 60000) + " minute(s) idle.");
                }
            }
        }
    }

    /**
     * Returns a live, ready-to-use {@link ChannelSftp} for {@code taskId} —
     * slot 0, i.e. the plain single-threaded case. Equivalent to
     * {@code acquireChannel(taskId, 0, credential)}.
     */
    public ChannelSftp getChannel(String taskId, Credential credential) throws Exception {
        return acquireChannel(taskId, 0, credential);
    }

    /**
     * Returns a live, ready-to-use {@link ChannelSftp} for {@code taskId}'s
     * {@code slot}-th concurrent worker, reusing that slot's existing channel
     * if it's still alive and the task's credential hasn't changed since the
     * session was opened, otherwise (re)connecting the whole session (which
     * invalidates every other slot too — a dead/changed session means a dead
     * SSH connection, not just one channel) or just opening this one
     * additional channel if the session itself is still fine and only this
     * slot is new or was closed.
     *
     * <p>Callers should call slots {@code 0..concurrency-1} for a given fire,
     * one per worker thread, and are expected to call each concurrently from
     * at most one thread at a time (a slot is not itself meant to be shared
     * across threads simultaneously — get one slot per thread).
     */
    public synchronized ChannelSftp acquireChannel(String taskId, int slot, Credential credential) throws Exception {
        String fingerprint = fingerprint(credential);
        Entry existing = entries.get(taskId);

        if (existing != null && existing.credentialFingerprint.equals(fingerprint) && existing.session.isConnected()) {
            existing.lastUsedMillis = System.currentTimeMillis();
            ChannelSftp channel = existing.slots.get(slot);
            if (channel != null && channel.isConnected()) {
                return channel;
            }
            // Session's fine, this particular slot just hasn't been opened
            // yet (or dropped) — cheap: one more channel on an already-live
            // connection, no fresh handshake.
            ChannelSftp opened = (ChannelSftp) existing.session.openChannel("sftp");
            opened.connect(CONNECT_TIMEOUT_MILLIS);
            existing.slots.put(slot, opened);
            return opened;
        }

        int priorConnectCount = 0;
        if (existing != null) {
            priorConnectCount = existing.connectCount;
            closeQuietly(existing);
            entries.remove(taskId);
        }
        Entry created = connect(credential, priorConnectCount + 1);
        ChannelSftp channel = (ChannelSftp) created.session.openChannel("sftp");
        channel.connect(CONNECT_TIMEOUT_MILLIS);
        created.slots.put(slot, channel);
        entries.put(taskId, created);
        return channel;
    }

    /**
     * How many times this task's connection has actually had to be
     * (re)established (as opposed to reused) since this manager started —
     * i.e. the number of distinct SSH sessions this task has consumed.
     * Exposed for the Event Monitor's per-run detail popup, where "1" means
     * "reused the already-warm connection" and anything higher means a
     * reconnect happened (idle timeout, credential change, or a dropped
     * connection). Always 1 regardless of how many concurrent channel slots
     * are open — they all ride on the same single SSH session.
     */
    public int getSessionCount(String taskId) {
        Entry e = entries.get(taskId);
        return e != null ? Math.max(e.connectCount, 1) : 1;
    }

    private Entry connect(Credential credential, int connectCount) throws Exception {
        JSch jsch = new JSch();
        Session session = jsch.getSession(credential.getUsername(), credential.getHost(), 22);
        session.setPassword(credential.getPassword());
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        session.setConfig(config);
        session.connect(CONNECT_TIMEOUT_MILLIS);
        // Keep the connection alive across long idle gaps between watcher
        // fires — without this, some firewalls/NAT devices silently drop a
        // quiet TCP connection well before our own idle-close window, and the
        // first .put()/.get() after that just hangs until it times out rather
        // than failing fast, which would look exactly like "still slow"
        // even with the persistent-connection fix in place.
        session.setServerAliveInterval(15_000);
        session.setServerAliveCountMax(4);
        return new Entry(session, fingerprint(credential), connectCount);
    }

    private static String fingerprint(Credential c) {
        return c.getHost() + "|" + c.getUsername() + "|" + c.getPassword();
    }

    /**
     * Force-closes and forgets this task's connection (session and every
     * open channel slot on it). Call when the task is deleted, disabled, or
     * its target credential changes, so the next fire reconnects cleanly
     * instead of reusing a session against a stale target.
     */
    public synchronized void close(String taskId) {
        Entry e = entries.remove(taskId);
        if (e != null) closeQuietly(e);
    }

    public synchronized void closeAll() {
        for (Entry e : entries.values()) closeQuietly(e);
        entries.clear();
    }

    /** Call on application/daemon shutdown. */
    public void shutdown() {
        closeAll();
        reaper.shutdownNow();
    }

    private static void closeQuietly(Entry e) {
        for (ChannelSftp c : e.slots.values()) {
            try { if (c.isConnected()) c.disconnect(); } catch (Exception ignored) {}
        }
        try { if (e.session != null && e.session.isConnected()) e.session.disconnect(); } catch (Exception ignored) {}
    }
}
