package service.transfer;

/**
 * One step of a {@link TransferJob}. Paths are plain (unquoted, unescaped) text: how a path is quoted
 * for a given tool is that engine's business, not the planner's.
 */
public sealed interface TransferCommand {

    /** Copies a remote file to this PC. With {@code deleteRemoteAfter} the original is removed in the same session, but only once the local copy is complete. */
    record Download(String remotePath, String localPath, boolean deleteRemoteAfter) implements TransferCommand { }

    /** Copies a local file to the server. A remote path that is an existing folder receives the file under its own name. */
    record Upload(String localPath, String remotePath) implements TransferCommand { }

    /** Removes a file on the server. */
    record DeleteRemote(String remotePath) implements TransferCommand { }

    /** Renames/moves a file on the server; never replaces an existing file. */
    record MoveRemote(String fromPath, String toPath) implements TransferCommand { }

    /** Creates a folder on the server, with its parents; fine if it already exists. */
    record MakeRemoteDir(String remotePath) implements TransferCommand { }
}
