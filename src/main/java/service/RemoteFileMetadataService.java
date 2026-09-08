package service;

import model.RemoteFileException;
import model.RemoteFileMetadata;
import java.time.Instant;
import java.util.List;

public interface RemoteFileMetadataService {

    /**
     * Returns all regular files whose last modified timestamp
     * is greater than the supplied timestamp.
     *
     * The returned list is ordered by last modified time
     * (oldest -> newest).
     */
    List<RemoteFileMetadata> getFilesModifiedAfter(
            String remoteDirectory,
            Instant modifiedAfter)
            throws RemoteFileException;

    /**
     * Returns metadata for exactly one named file inside
     * {@code remoteDirectory}, or {@code null} if it doesn't exist (or isn't
     * a regular file) — for a caller that already knows which specific
     * file(s) it's interested in (e.g. a watcher fire naming specific
     * changed files) and doesn't need, and for a large directory very much
     * doesn't want, a full listing of everything else in it just to check
     * on a handful of names. One round trip per call against a remote
     * implementation, versus one round trip per few hundred entries for a
     * full listing — for a directory with tens of thousands of files, that's
     * the difference between this taking milliseconds and it taking tens of
     * seconds.
     *
     * <p>Default implementation falls back to {@link #getFilesModifiedAfter}
     * with {@link Instant#EPOCH} and filtering client-side, for
     * implementations with no cheaper single-file check available;
     * {@link SftpRemoteFileMetadataService} overrides this with a single SFTP
     * stat call instead.
     */
    default RemoteFileMetadata statFile(String remoteDirectory, String fileName) throws RemoteFileException {
        return getFilesModifiedAfter(remoteDirectory, Instant.EPOCH).stream()
                .filter(f -> f.fileName().equals(fileName))
                .findFirst().orElse(null);
    }
}