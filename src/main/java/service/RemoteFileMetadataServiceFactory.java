package service;

import persistence.AppStorage;
import service.sftp.ConnectOptions;
import service.sftp.JschSftpClient;
import service.sftp.SftpClient;
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
     * resources (the SFTP connection) that must be released after use.
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

        /** Non-null only when a connection was opened; closed in {@link #close()}. */
        private final SftpClient connection;

        ManagedMetadataService(RemoteFileMetadataService service,
                               String watchDirectory,
                               SftpClient connection) {
            this.service        = service;
            this.watchDirectory = watchDirectory;
            this.connection     = connection;
        }

        public RemoteFileMetadataService service()        { return service; }
        public String                    watchDirectory() { return watchDirectory; }

        @Override
        public void close() {
            if (connection != null) connection.close();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static final ConnectOptions CONNECT = ConnectOptions.of(ConnectOptions.DEFAULT_PORT, 30_000, 10_000);

    private final AppStorage storage;

    public RemoteFileMetadataServiceFactory(AppStorage storage) {
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

        SftpClient client = JschSftpClient.connect(credential.getHost(), credential.getUsername(),
                credential.getPassword(), CONNECT);

        // For INBOUND tasks the watch directory is the remote source path (targetPath).
        String watchDir = resolveRemoteWatchDirectory(task);

        return new ManagedMetadataService(
                new SftpRemoteFileMetadataService(client),
                watchDir,
                client);
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