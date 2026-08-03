package service;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import model.Credential;
import model.ScheduledTask;
import model.ScheduledTask.TransferDirection;
import service.LocalFileMetadataService;
import service.RemoteFileMetadataService;

import java.util.Properties;

/**
 * Resolves the appropriate {@link RemoteFileMetadataService} implementation
 * for a given {@link ScheduledTask}.
 *
 * <p>Resolution rules:
 * <ol>
 *   <li>If the task's source and target paths both resolve to <em>local</em> filesystem
 *       paths (i.e. the target host is the local machine), a
 *       {@link LocalFileMetadataService} is returned — no network connection needed.</li>
 *   <li>Otherwise an {@link SftpRemoteFileMetadataService} is returned, backed by a
 *       freshly-opened JSch SFTP channel to the target host.</li>
 * </ol>
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
     * @param credential resolved target credential (may be {@code null} for local→local)
     * @throws Exception if an SSH/SFTP connection cannot be established
     */
    public ManagedMetadataService create(ScheduledTask task, Credential credential)
            throws Exception {

        boolean isLocalToLocal = isLocalToLocal(task, credential);

        if (isLocalToLocal) {
            // ── Local→local: watch the SOURCE directory on the local filesystem.
            //
            // INBOUND naming convention used throughout the codebase:
            //   targetPath  = the folder being *watched* (files arrive here)
            //   sourcePath  = the local *destination* (files are copied here)
            //
            // The factory must therefore pass targetPath as the watch directory
            // so that LocalFileMetadataService lists the correct folder.
            String watchDir = resolveLocalWatchDirectory(task);
            return new ManagedMetadataService(
                    new LocalFileMetadataService(),
                    watchDir,
                    null   // no SSH session → TransferService detects local→local via sshSession()==null
            );
        }

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
     * Returns {@code true} when the transfer is entirely local (source host == target
     * host == local machine) so no network connection is needed.
     *
     * A task is considered local-to-local when:
     * <ul>
     *   <li>the credential's host matches the local hostname (case-insensitive), OR</li>
     *   <li>the credential is {@code null} / has no configured host.</li>
     * </ul>
     */
    private boolean isLocalToLocal(ScheduledTask task, Credential credential) {
        if (credential == null) return true;
        String credHost = credential.getHost();
        if (credHost == null || credHost.isEmpty()) return true;
        String localHost = TransferService.getLocalHostname();
        return credHost.equalsIgnoreCase(localHost)
                || credHost.equalsIgnoreCase("localhost")
                || credHost.equals("127.0.0.1");
    }

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

    /**
     * Resolves the local watch directory for a local→local task.
     *
     * <ul>
     *   <li><b>INBOUND</b>  — the watch folder is {@code targetPath} (files land here
     *       and are then copied to {@code sourcePath}).</li>
     *   <li><b>OUTBOUND</b> — the watch folder is {@code sourcePath} (files are pushed
     *       from here to the remote).  This branch is only reached when
     *       {@code isLocalToLocal} is true for an outbound task, which in practice
     *       should not happen, but is handled defensively.</li>
     * </ul>
     *
     * Trailing glob wildcards ({@code *}) are stripped.
     */
    private String resolveLocalWatchDirectory(ScheduledTask task) {
        // INBOUND: targetPath is the watched source folder
        // OUTBOUND (defensive fallback): sourcePath is the watched source folder
        boolean inbound = task.getTransferDirection() == TransferDirection.INBOUND;
        String path = inbound ? task.getTargetPath() : task.getSourcePath();

        if (path == null) return ".";

        // Strip trailing glob wildcard
        if (path.endsWith("*")) {
            int lastSep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            if (lastSep >= 0) path = path.substring(0, lastSep);
        }

        return path;
    }
}