package service;

import service.sftp.ConnectOptions;
import service.sftp.JschSftpClient;
import service.sftp.SftpClient;


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

        SftpClient client = null;
        boolean opened = false;
        long openedAt = 0;
        try {
            client = JschSftpClient.connect(host.trim(), username.trim(), password,
                    ConnectOptions.of(port, CONNECT_TIMEOUT_MS, CHANNEL_TIMEOUT_MS));
            opened = true;
            openedAt = System.currentTimeMillis();
            SshSessionEvents.uiOpened("Test connection", host.trim(), username.trim());

            String home = client.home();

            long elapsed = System.currentTimeMillis() - start;
            return new Result(true,
                    "Authenticated over SFTP. Home directory: " + home, elapsed);

        } catch (Exception ex) {
            if (!opened) SshSessionEvents.uiFailed("Test connection", host.trim(), username.trim(), describeFailure(ex));
            long elapsed = System.currentTimeMillis() - start;
            return new Result(false, describeFailure(ex), elapsed);
        } finally {
            if (client != null) client.close();
            if (opened) SshSessionEvents.uiClosed("Test connection", host.trim(), username.trim(),
                    System.currentTimeMillis() - openedAt);
        }
    }

    /** Turns a raw JSch/IO exception into a short, human-readable reason. */
    static String describeFailure(Exception ex) {
        // The client wraps the driver's error ("SFTP connect to h:22 failed: ..."); classify on the original reason,
        // since the wrapper's own words would match "connect" for every failure.
        Throwable reason = ex.getCause() != null ? ex.getCause() : ex;
        String raw = reason.getMessage() != null ? reason.getMessage() : reason.getClass().getSimpleName();
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
