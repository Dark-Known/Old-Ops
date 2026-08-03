package service;

import model.RemoteFileException;
import model.RemoteFileMetadata;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@link RemoteFileMetadataService} implementation that operates on the local filesystem.
 *
 * <p>This allows the inbound-watcher logic to work identically for:
 * <ul>
 *   <li>SFTP transfers  — backed by {@link SftpRemoteFileMetadataService}</li>
 *   <li>Local→local transfers — backed by this class (e.g. a file dropped every 2 min
 *       into a watch folder that must be copied to a destination folder).</li>
 * </ul>
 *
 * <p>Only regular files (not directories or symlinks) directly inside
 * {@code remoteDirectory} are returned. Sub-directory traversal is intentionally
 * omitted to match the flat-listing behaviour of the SFTP implementation.
 */
public class LocalFileMetadataService implements RemoteFileMetadataService {

    /**
     * Returns all regular files in {@code remoteDirectory} whose last-modified
     * time is strictly after {@code modifiedAfter}, ordered oldest → newest.
     *
     * @param remoteDirectory absolute or relative path to the local watch folder
     * @param modifiedAfter   exclusive lower bound; pass {@link Instant#EPOCH} on the
     *                        first run to retrieve every file in the directory
     */
    @Override
    public List<RemoteFileMetadata> getFilesModifiedAfter(
            String remoteDirectory,
            Instant modifiedAfter) throws RemoteFileException {

        Path dir = Paths.get(remoteDirectory);

        if (!Files.isDirectory(dir)) {
            throw new RemoteFileException(
                    "Local watch directory does not exist or is not a directory: " + remoteDirectory,
                    null);
        }

        List<RemoteFileMetadata> result = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(entry, BasicFileAttributes.class);
                } catch (IOException ioe) {
                    // Skip files we cannot stat (e.g. broken symlinks)
                    continue;
                }

                if (!attrs.isRegularFile()) {
                    continue;
                }

                Instant lastModified = attrs.lastModifiedTime().toInstant();
                if (lastModified.isAfter(modifiedAfter)) {
                    result.add(new RemoteFileMetadata(
                            entry.getFileName().toString(),
                            lastModified,
                            attrs.size()));
                }
            }
        } catch (IOException ex) {
            throw new RemoteFileException(
                    "Failed to list local directory: " + remoteDirectory, ex);
        }

        result.sort(Comparator.comparing(RemoteFileMetadata::lastModified));
        return result;
    }
}