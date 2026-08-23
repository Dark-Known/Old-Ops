package service;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.util.Properties;

/**
 * Verifies a set of target-system credentials by actually opening a JSch
 * SFTP session against the host — the same connection mechanism used by
 * {@link RemoteFileMetadataServiceFactory} and {@link TransferService} at
 * run time — so "Test Connection" reflects exactly what a real task run
 * would experience (reachability, auth, and SFTP subsystem availability).
 *
 * <p>Intentionally blocking — callers must run {@link #testSftp} off the
 * Swing EDT (e.g. inside a {@link javax.swing.SwingWorker}) so the UI
 * doesn't freeze while a connection attempt times out.
 */
public final class ConnectionTestService {

    private ConnectionTestService() {}

    private static final int DEFAULT_PORT = 22;
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int CHANNEL_TIMEOUT_MS = 6_000;

    /** Outcome of a single test attempt. */
    public static final class Result {
        public final boolean success;
        public final String message;
        public final long elapsedMs;

        public Result(boolean success, String message, long elapsedMs) {
            this.success   = success;
            this.message   = message;
            this.elapsedMs = elapsedMs;
        }
    }

    public static Result testSftp(String host, String username, String password) {
        return testSftp(host, DEFAULT_PORT, username, password);
    }

    public static Result testSftp(String host, int port, String username, String password) {
        long start = System.currentTimeMillis();

        if (host == null || host.trim().isEmpty()) {
            return new Result(false, "Hostname / IP is required.", 0);
        }
        if (username == null || username.trim().isEmpty()) {
            return new Result(false, "Username is required.", 0);
        }

        Session session = null;
        ChannelSftp channel = null;
        try {
            JSch jsch = new JSch();
            session = jsch.getSession(username.trim(), host.trim(), port);
            session.setPassword(password == null ? "" : password);

            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);
            session.connect(CONNECT_TIMEOUT_MS);

            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(CHANNEL_TIMEOUT_MS);
            String home = channel.pwd();

            long elapsed = System.currentTimeMillis() - start;
            return new Result(true,
                    "Authenticated over SFTP. Home directory: " + home, elapsed);

        } catch (Exception ex) {
            long elapsed = System.currentTimeMillis() - start;
            return new Result(false, describeFailure(ex), elapsed);
        } finally {
            if (channel != null && channel.isConnected()) channel.disconnect();
            if (session != null && session.isConnected()) session.disconnect();
        }
    }

    /** Turns a raw JSch/IO exception into a short, human-readable reason. */
    private static String describeFailure(Exception ex) {
        String raw = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        String lower = raw.toLowerCase();

        if (lower.contains("auth")) {
            return "Authentication failed — check the username and password.";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "Connection timed out — check the host/port and network reachability.";
        }
        if (lower.contains("unknownhost")) {
            return "Unknown host — check the hostname / IP address.";
        }
        if (lower.contains("connection refused") || lower.contains("connect")) {
            return "Could not reach host on port 22 — check firewall / SSH service status.";
        }
        return "Connection failed: " + raw;
    }
}
