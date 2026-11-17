package service.transfer;

import model.Credential;

import java.util.Map;
import java.util.function.Consumer;

/**
 * What actually talks to a remote server. Today there are two implementations, {@link WinScpEngine}
 * (runs WinSCP.com) and {@link SftpEngine} (in-process SFTP), and {@link SwitchingEngine} picks one
 * from the configuration. A new transport (FTPS, cloud storage, ...) is another implementation of
 * this interface plus a line where the engines are assembled in {@code TransferService}.
 *
 * <p>The planner describes its work as a {@link TransferJob} of structured {@link TransferCommand}s;
 * nothing in it is specific to WinSCP or to SFTP.
 */
public interface TransferEngine {

    /**
     * Runs one job: connects to {@code job.target()}, executes the commands in order, disconnects.
     *
     * @param taskId the task the run belongs to, or a pseudo id such as {@code "backup-mkdir"};
     *               used to cancel the run and to count its sessions
     * @return true if the job succeeded (see {@link TransferJob#stopOnError()})
     */
    boolean run(TransferJob job, Consumer<String> logLine, String taskId) throws Exception;

    /**
     * Lists the files (not folders) directly inside {@code dirPath}, keyed by full remote path
     * ({@code dirPath} followed by the file name).
     */
    Map<String, RemoteFileStat> listFiles(Credential target, String password, String dirPath,
                                          Consumer<String> logLine) throws Exception;
}
