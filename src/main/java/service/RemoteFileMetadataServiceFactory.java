package service;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import model.Credential;
import model.ScheduledTask;
import service.RemoteFileMetadataService;

import java.util.Properties;

/**
 * Resolves the appropriate {@link RemoteFileMetadataService} implementation
 * for a given {@link ScheduledTask}.
 *
 * <p>Local→local file transfers are not supported — every {@code FILE_TRANSFER}
 * task must have a resolved target {@link Credential}, and this factory always
 * returns an {@link SftpRemoteFileMetadataService} backed by a freshly-opened
 * JSch SFTP channel to that target host.
 *
 * <p>Callers are responsible for closing the returned {@link ManagedMetadataService}
 * (which implements {@link AutoCloseable}) so that any underlying SSH session is
 * released when the transfer run finishes.
 */
public class RemoteFileMetadataServiceFactory {

    /**
     * Wraps a {@link RemoteFileMetadataService} together with any closeable
     * resources (e.g. an SSH {@link Session}) that must be released after use.
     */
    public static final class ManagedMetadataService implements AutoCloseable {

        private final RemoteFileMetadataService service;

        /**
         * The watch directory that the service should list.
         * <ul>
         *   <li>INBOUND SFTP       — remote source path (targetPath)</li>
         *   <li>INBOUND local→local — local source/watch path (targetPath)</li>
         *   <li>OUTBOUND watcher   — local source path (sourcePath) — handled
         *       directly by TransferService via LocalFileMetadataService, so
         *       the factory is not used for that branch.</li>
         * </ul>
         */
        private final String watchDirectory;

        /** Non-null only when an SSH session was opened; closed in {@link #close()}. */
        private final Session sshSession;

        ManagedMetadataService(RemoteFileMetadataService service,
                               String watchDirectory,
                               Session sshSession) {
            this.service        = service;
            this.watchDirectory = watchDirectory;
            this.sshSession     = sshSession;
        }

        public RemoteFileMetadataService service()        { return service; }
        public String                    watchDirectory() { return watchDirectory; }
        public Session                   sshSession()     { return sshSession; }

        @Override
        public void close() {
            if (sshSession != null && sshSession.isConnected()) {
                sshSession.disconnect();
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private final XmlStorageService storage;

    public RemoteFileMetadataServiceFactory(XmlStorageService storage) {
        this.storage = storage;
    }

    /**
     * Builds and returns a {@link ManagedMetadataService} appropriate for {@code task}.
     *
     * @param task       the task about to be executed
     * @param credential resolved target credential (required — local→local is not supported)
     * @throws Exception if an SSH/SFTP connection cannot be established
     */
    public ManagedMetadataService create(ScheduledTask task, Credential credential)
            throws Exception {

        // ── Remote SFTP: open a JSch session and wrap it ──────────────────────
        if (credential == null) {
            throw new IllegalStateException(
                    "Cannot open SFTP connection: no credential resolved for task " + task.getId());
        }

        JSch jsch = new JSch();
        Session session = jsch.getSession(
                credential.getUsername(),
                credential.getHost(),
                22);
        session.setPassword(credential.getPassword());

        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        session.setConfig(config);
        session.connect(30_000);

        ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
        channel.connect(10_000);

        // For INBOUND tasks the watch directory is the remote source path (targetPath).
        String watchDir = resolveRemoteWatchDirectory(task);

        return new ManagedMetadataService(
                new SftpRemoteFileMetadataService(channel),
                watchDir,
                session);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * For an INBOUND task the watch directory is the <em>remote</em> source (targetPath).
     * Strips any trailing glob wildcard so that a plain directory path is returned.
     */
    private String resolveRemoteWatchDirectory(ScheduledTask task) {
        String path = normalizeRemotePath(task.getTargetPath());
        if (path.isEmpty()) return "/";
        if (path.endsWith("*")) {
            path = path.substring(0, path.lastIndexOf('/') + 1);
        } else if (!path.endsWith("/")) {
            // If the last segment looks like a filename (contains '.'), use its parent
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash >= 0 && path.substring(lastSlash + 1).contains(".")) {
                path = path.substring(0, lastSlash + 1);
            } else {
                path = path + "/";
            }
        }
        return path;
    }

    private String normalizeRemotePath(String path) {
        if (path == null) return "";
        String normalized = path.trim().replace("\\", "/");
        normalized = normalized.replaceAll("/+", "/");

        // Windows OpenSSH SFTP requires a leading slash before the drive letter:
        // "C:/Daily Changes" → "/C:/Daily Changes"
        if (normalized.matches("^[A-Za-z]:/.*")) {
            normalized = "/" + normalized;
        }

        return normalized;
    }
}