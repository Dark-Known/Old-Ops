package service.watch;

import model.Credential;
import service.sftp.SftpClient;

/**
 * The connections the folder watcher keeps for one task while a fire runs: open (or reuse) a session,
 * hand out one channel per worker thread, note what happened for the session transcript, close when the
 * fire is done. {@link PersistentSftpConnectionManager} is the JSch implementation; {@code TransferService}
 * depends only on this interface, so another implementation (or a test double) can be supplied.
 *
 * <p>What is handed out is a {@link SftpClient}, so the watcher code does not know about JSch. The clients are
 * borrowed: closing one does nothing; the session lives until {@link #close(String)}.
 */
public interface WatcherConnections {

    /** Slot 0: the plain single-threaded case. */
    SftpClient getClient(String taskId, Credential credential) throws Exception;

    /** One client per worker thread: slots {@code 0..concurrency-1}. */
    SftpClient acquireClient(String taskId, int slot, Credential credential) throws Exception;

    /** How many sessions have been opened for the task: 1 means the warm connection was reused. */
    int getSessionCount(String taskId);

    /** Adds a line to the task's session transcript. */
    void note(String taskId, String text);

    void close(String taskId);

    void close(String taskId, String reason);

    void closeAll();

    void shutdown();
}
