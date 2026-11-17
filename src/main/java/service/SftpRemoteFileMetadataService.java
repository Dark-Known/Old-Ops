package service;

import java.time.Instant;
import java.util.*;

import model.RemoteFileException;
import model.RemoteFileMetadata;
import service.RemoteFileMetadataService;

public class SftpRemoteFileMetadataService
        implements RemoteFileMetadataService {

    private final service.sftp.SftpClient client;

    public SftpRemoteFileMetadataService(service.sftp.SftpClient client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public List<RemoteFileMetadata> getFilesModifiedAfter(
            String remoteDirectory,
            Instant modifiedAfter)
            throws RemoteFileException {

        try {
            // Normalize Windows-style backslashes to forward slashes
            String normalizedPath = remoteDirectory.replace("\\", "/");

            // cd first, then ls "." — avoids path-escaping issues (e.g. spaces)
            List<RemoteFileMetadata> result = new ArrayList<>();
            for (service.sftp.RemoteEntry entry : client.listViaCd(normalizedPath)) {
                if (entry.directory()) continue;
                Instant lastModified = Instant.ofEpochSecond(entry.modifiedEpochSeconds());
                if (lastModified.isAfter(modifiedAfter)) {
                    result.add(new RemoteFileMetadata(entry.name(), lastModified, entry.size()));
                }
            }
            result.sort(Comparator.comparing(RemoteFileMetadata::lastModified));
            return result;
        } catch (java.io.IOException ex) {
            throw new RemoteFileException(
                    "Unable to retrieve remote file metadata | cause: " + ex.getMessage(), ex);
        }
    }

    @Override
    public model.RemoteFileMetadata statFile(String remoteDirectory, String fileName) throws RemoteFileException {
        String normalizedDir = remoteDirectory.replace("\\", "/");
        if (!normalizedDir.endsWith("/")) normalizedDir += "/";
        String fullPath = normalizedDir + fileName;
        try {
            service.sftp.RemoteEntry entry = client.stat(fullPath);
            if (entry == null || entry.directory()) return null; // not there right now (e.g. already moved away) is not an error
            return new RemoteFileMetadata(fileName, Instant.ofEpochSecond(entry.modifiedEpochSeconds()), entry.size());
        } catch (java.io.IOException ex) {
            throw new RemoteFileException("Unable to stat remote file: " + fileName + " | cause: " + ex.getMessage(), ex);
        }
    }
}
