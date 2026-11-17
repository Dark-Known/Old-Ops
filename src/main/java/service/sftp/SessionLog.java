package service.sftp;

/**
 * A running transcript of one SSH/SFTP session: connect, server banner, key exchange, authentication, and every
 * file operation. It becomes the "details" of the session's events in the Event Monitor.
 *
 * <p>The log of the session being set up on the current thread is found through {@link #begin()} / {@link #take()},
 * so the code that opens the connection and the code that reports the event need not know about each other.
 * Bounded, never throws, and never contains passwords.
 */
public final class SessionLog {
    private static final int MAX_CHARS = 48_000;
    private static final ThreadLocal<SessionLog> CURRENT = new ThreadLocal<>();

    private final long t0 = System.currentTimeMillis();
    private final StringBuilder sb = new StringBuilder();
    private boolean full;

    /** Starts a fresh log for the session this thread is about to open. */
    public static SessionLog begin() {
        SessionLog l = new SessionLog();
        CURRENT.set(l);
        return l;
    }

    /** The log begun on this thread, or a new stand-alone one. */
    public static SessionLog currentOrNew() {
        SessionLog l = CURRENT.get();
        return l != null ? l : new SessionLog();
    }

    /** Returns the text of the log begun on this thread and forgets it; null if there was none. */
    public static String take() {
        SessionLog l = CURRENT.get();
        CURRENT.remove();
        return l == null ? null : l.text();
    }

    public synchronized void add(String line) {
        if (full || line == null) return;
        if (sb.length() + line.length() > MAX_CHARS) {
            sb.append("… (log truncated)\n");
            full = true;
            return;
        }
        long ms = System.currentTimeMillis() - t0;
        sb.append(String.format("+%d.%03ds  ", ms / 1000, ms % 1000)).append(line.strip()).append('\n');
    }

    /** A JSch logger writing protocol messages (banner, key exchange, authentication) into this transcript. */
    public com.jcraft.jsch.Logger jschLogger() {
        return new com.jcraft.jsch.Logger() {
            @Override public boolean isEnabled(int level) { return level >= com.jcraft.jsch.Logger.INFO; }
            @Override public void log(int level, String message) {
                // JSch's reader thread reports a harmless NPE when WE close the session; that is not an incident.
                if (message != null && message.contains("leaving main loop due to Cannot invoke")) return;
                add(message);
            }
        };
    }

    public synchronized String text() { return sb.toString().stripTrailing(); }
}
