package service.transfer;

import model.Credential;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Sends jobs for the built-in "Local (this computer)" credential to the SFTP engine (which reads and writes the
 * local folders directly) whatever engine the setting selects; WinSCP cannot do local-to-local copies.
 * Every other job goes to the selected engine as before.
 */
public final class LocalAwareEngine implements TransferEngine {

    private final TransferEngine local;
    private final TransferEngine selected;

    public LocalAwareEngine(TransferEngine local, TransferEngine selected) {
        this.local = local;
        this.selected = selected;
    }

    @Override
    public boolean run(TransferJob job, Consumer<String> logLine, String taskId) throws Exception {
        return (job.target() != null && job.target().isLocal() ? local : selected).run(job, logLine, taskId);
    }

    @Override
    public Map<String, RemoteFileStat> listFiles(Credential target, String password, String dirPath,
                                                 Consumer<String> logLine) throws Exception {
        return (target != null && target.isLocal() ? local : selected).listFiles(target, password, dirPath, logLine);
    }
}
