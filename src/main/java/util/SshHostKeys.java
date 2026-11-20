package util;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SSH host-key verification for the JSch connections (trust on first use).
 *
 * <p>Policy comes from app-config.xml {@code <sshHostKeyPolicy>}:
 * <ul>
 *   <li>{@code TOFU} (default) - the first key seen for a host is remembered in
 *       {@code <dataDir>/known_hosts}; later connections must present the same key or
 *       the connection fails with "HostKey has been changed".</li>
 *   <li>{@code OFF} - no verification (the behaviour of earlier builds).</li>
 * </ul>
 * If a server's key legitimately changes (re-install), delete its line from known_hosts.
 *
 * <p>Only the JSch-based connections use this. WinSCP scripts still use {@code -hostkey="*"}.
 */
public final class SshHostKeys {

    private static final Logger log = Logger.getLogger(SshHostKeys.class.getName());
    public static final String FILE_NAME = "known_hosts";

    private static final Object LOCK = new Object();
    private static File dir;

    private SshHostKeys() {}

    /** First call wins, same convention as {@link CryptoUtil#configure}. */
    public static void configure(File dataDir) {
        if (dataDir == null) return;
        synchronized (LOCK) {
            if (dir == null) dir = dataDir;
        }
    }

    /** The known_hosts file in use, or null if no directory has been configured yet. */
    public static File knownHostsFile() {
        synchronized (LOCK) {
            return dir == null ? null : new File(dir, FILE_NAME);
        }
    }

    public static boolean enabled() {
        String v = AppConfig.readValue("sshHostKeyPolicy");
        return v == null || !v.trim().equalsIgnoreCase("OFF");
    }

    /**
     * Call after {@code jsch.getSession(...)} and before {@code session.connect(...)}.
     * Sets StrictHostKeyChecking on the session config accordingly.
     */
    public static void apply(JSch jsch, Session session) {
        if (!enabled()) {
            session.setConfig("StrictHostKeyChecking", "no");
            return;
        }
        try {
            File base;
            synchronized (LOCK) {
                if (dir == null) {
                    String configured = AppConfig.readValue("dataDir");
                    dir = new File(configured != null ? configured : "C:\\OpsTools\\Data");
                    log.warning("SshHostKeys used before configure(); using " + dir);
                }
                base = dir;
            }
            File file = new File(base, FILE_NAME);
            base.mkdirs();
            if (!file.exists()) file.createNewFile();
            jsch.setKnownHosts(file.getAbsolutePath());
            jsch.setHostKeyRepository(new Tofu(jsch.getHostKeyRepository()));
            // "yes" makes JSch reject anything our repository does not report as OK.
            session.setConfig("StrictHostKeyChecking", "yes");
        } catch (Exception e) {
            // Fail closed would take every transfer down over a file problem; log loudly instead.
            log.log(Level.SEVERE, "Host-key checking could not be set up (" + e + "); connecting WITHOUT verification", e);
            session.setConfig("StrictHostKeyChecking", "no");
        }
    }

    /** Accepts and records unknown hosts, delegates everything else (changed keys fail). */
    private static final class Tofu implements HostKeyRepository {
        private final HostKeyRepository delegate;

        Tofu(HostKeyRepository delegate) { this.delegate = delegate; }

        @Override
        public int check(String host, byte[] key) {
            synchronized (LOCK) {
                int r = delegate.check(host, key);
                if (r == NOT_INCLUDED) {
                    try {
                        delegate.add(new HostKey(host, key), (UserInfo) null);
                        log.info("Remembered SSH host key for " + host);
                        return OK;
                    } catch (Exception e) {
                        log.log(Level.WARNING, "Could not remember host key for " + host, e);
                        return OK; // do not break transfers over a write problem
                    }
                }
                if (r == CHANGED) {
                    log.severe("SSH host key for " + host + " does not match " + FILE_NAME
                            + " - refusing to connect. If the server was rebuilt, remove its line from that file.");
                }
                return r;
            }
        }

        @Override public void add(HostKey hostkey, UserInfo ui) { delegate.add(hostkey, ui); }
        @Override public void remove(String host, String type) { delegate.remove(host, type); }
        @Override public void remove(String host, String type, byte[] key) { delegate.remove(host, type, key); }
        @Override public String getKnownHostsRepositoryID() { return delegate.getKnownHostsRepositoryID(); }
        @Override public HostKey[] getHostKey() { return delegate.getHostKey(); }
        @Override public HostKey[] getHostKey(String host, String type) { return delegate.getHostKey(host, type); }
    }
}
