package service.sftp;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Minimal SFTP operations the transfer code needs, independent of the library behind it.
 * Implementations are NOT thread-safe: use one client per thread/transfer.
 *
 * <p>{@link #cancel()} may be called from any thread; it makes the operation in progress
 * fail promptly with an {@link IOException} and closes the connection.
 */
public interface SftpClient extends AutoCloseable {

    /** Entries of a remote directory (without "." and ".."). */
    List<RemoteEntry> list(String remoteDir) throws IOException;

    /** Size/mtime of one remote path, or null if it does not exist. */
    RemoteEntry stat(String remotePath) throws IOException;

    /**
     * Downloads to {@code local}. The data is written to a temporary file next to the target and
     * moved into place on success, so a failed or cancelled download never leaves a partial file
     * under the final name.
     */
    void download(String remotePath, File local) throws IOException;

    /**
     * Uploads {@code local}. The data is written to {@code remotePath + ".part"} and renamed on
     * success, so readers on the remote side never see a half-written file under the final name.
     */
    void upload(File local, String remotePath) throws IOException;

    /** Creates the directory and any missing parents. No error if it already exists. */
    void mkdirs(String remoteDir) throws IOException;

    /** Deletes a remote file. Returns false if it did not exist. */
    boolean delete(String remotePath) throws IOException;

    /**
     * Renames/moves a remote file within the server. Never replaces an existing file at {@code to}
     * (the call fails instead), so an archive is never overwritten.
     */
    void rename(String from, String to) throws IOException;

    /** Aborts whatever is running and closes the connection. Safe to call more than once. */
    void cancel();

    @Override
    void close();
}
