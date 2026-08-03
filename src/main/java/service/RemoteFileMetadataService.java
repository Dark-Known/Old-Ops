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
}