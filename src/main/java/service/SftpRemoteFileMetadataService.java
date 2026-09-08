package service;

import java.time.Instant;
import java.util.*;

import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import model.RemoteFileException;
import model.RemoteFileMetadata;
import service.RemoteFileMetadataService;

import com.jcraft.jsch.ChannelSftp;
public class SftpRemoteFileMetadataService
        implements RemoteFileMetadataService {

    private final ChannelSftp channel;

    public SftpRemoteFileMetadataService(ChannelSftp channel) {
        this.channel = Objects.requireNonNull(channel);
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
            channel.cd(normalizedPath);
            Vector<ChannelSftp.LsEntry> entries = channel.ls(".");

            List<RemoteFileMetadata> result = new ArrayList<>();

            for (ChannelSftp.LsEntry entry : entries) {

                SftpATTRS attrs = entry.getAttrs();

                // Skip directories and POSIX navigation entries
                if (attrs.isDir()
                        || entry.getFilename().equals(".")
                        || entry.getFilename().equals("..")) {
                    continue;
                }

                Instant lastModified =
                        Instant.ofEpochSecond(attrs.getMTime());

                if (lastModified.isAfter(modifiedAfter)) {

                    result.add(new RemoteFileMetadata(
                            entry.getFilename(),
                            lastModified,
                            attrs.getSize()));
                }
            }

            result.sort(
                    Comparator.comparing(RemoteFileMetadata::lastModified));

            return result;

        } catch (SftpException ex) {
            throw new RemoteFileException(
                    "Unable to retrieve remote file metadata"
                            + " | SFTP error code: " + ex.id  // add this
                            + " | cause: " + ex.getMessage(),  // add this
                    ex);
        }
    }

    /**
     * A single SFTP {@code stat} round trip — see the interface javadoc for
     * why this matters: for a remote directory with tens of thousands of
     * files, {@link #getFilesModifiedAfter} has to page through the entire
     * listing (many round trips, easily tens of seconds), while this is
     * exactly one.
     */
    @Override
    public model.RemoteFileMetadata statFile(String remoteDirectory, String fileName) throws RemoteFileException {
        String normalizedDir = remoteDirectory.replace("\\", "/");
        if (!normalizedDir.endsWith("/")) normalizedDir += "/";
        String fullPath = normalizedDir + fileName;
        try {
            SftpATTRS attrs = channel.stat(fullPath);
            if (attrs.isDir()) return null;
            return new RemoteFileMetadata(fileName, Instant.ofEpochSecond(attrs.getMTime()), attrs.getSize());
        } catch (SftpException ex) {
            if (ex.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                return null; // genuinely doesn't exist right now (e.g. already renamed/moved away) — not an error
            }
            throw new RemoteFileException(
                    "Unable to stat remote file: " + fileName
                            + " | SFTP error code: " + ex.id
                            + " | cause: " + ex.getMessage(),
                    ex);
        }
    }
}