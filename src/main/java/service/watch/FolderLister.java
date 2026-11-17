package service.watch;

import model.Credential;
import model.RemoteFileMetadata;
import model.ScheduledTask;
import service.FolderWatch;
import service.RemoteFileMetadataServiceFactory;
import service.TransferPaths;
import service.transfer.CredentialLookup;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Lists the folder a "Watch a folder" task points at: a local folder, or a remote one over SFTP when
 * the task names a credential.
 */
public final class FolderLister implements FolderWatch.Lister {

    private final CredentialLookup credentials;
    private final RemoteFileMetadataServiceFactory remote;

    public FolderLister(CredentialLookup credentials, RemoteFileMetadataServiceFactory remote) {
        this.credentials = credentials;
        this.remote = remote;
    }

    @Override
    public List<FolderWatch.Item> list(ScheduledTask task, Consumer<String> log) throws Exception {
        String dir = task.getBackupSourcePath() == null ? "" : task.getBackupSourcePath().trim();
        if (dir.isEmpty()) throw new IOException("No folder to watch is set on this task.");
        String credName = task.getBackupSourceUsername() == null ? "" : task.getBackupSourceUsername().trim();
        if (credName.isEmpty()) return FolderWatch.listLocal(dir);
        Credential cred = credentials.find(credName, "watch", log);
        if (cred == null) throw new IOException("No credential named '" + credName + "'.");
        try (RemoteFileMetadataServiceFactory.ManagedMetadataService m = remote.create(task, cred)) {
            List<FolderWatch.Item> out = new ArrayList<>();
            for (RemoteFileMetadata f : m.service().getFilesModifiedAfter(TransferPaths.normalizeRemotePath(dir), Instant.EPOCH))
                out.add(new FolderWatch.Item(f.fileName(), f.size(), f.lastModified().toEpochMilli()));
            return out;
        }
    }
}
