package service.sftp;

import java.io.IOException;

/**
 * How one file is written to the server. Two strategies:
 * <ul>
 *   <li>{@link Mode#ATOMIC} (default): write to {@code name.part}, rename to {@code name}, copy the local
 *       modified time. A receiver polling the folder never sees a half-written file, and a failed upload
 *       leaves nothing under the real name. This is what {@link SftpClient#upload(java.io.File, String)} does.</li>
 *   <li>{@link Mode#IN_PLACE}: write straight to the final name (the folder watcher's original behaviour), for receivers
 *       that cannot tolerate a rename or a {@code .part} file.</li>
 * </ul>
 * Talks to the server through {@link RemoteFiles}, so the strategy is testable without a server and does not
 * depend on JSch.
 */
public final class UploadStrategy {

    /** The few remote operations an upload needs. */
    public interface RemoteFiles {
        void put(String local, String remote) throws IOException;
        void rename(String from, String to) throws IOException;
        void remove(String remote) throws IOException;
        void setModifiedTime(String remote, long epochMillis) throws IOException;
    }

    public enum Mode {
        ATOMIC, IN_PLACE;

        /** Reads the setting text; anything unrecognised means the safe default, {@link #ATOMIC}. */
        public static Mode parse(String text) {
            if (text != null) {
                String t = text.trim().toLowerCase().replace('_', '-');
                if (t.equals("in-place") || t.equals("inplace")) return IN_PLACE;
            }
            return ATOMIC;
        }
    }

    public static final String PART_SUFFIX = ".part";

    private UploadStrategy() { }

    public static void upload(RemoteFiles remote, Mode mode, String local, String remotePath, long localModifiedMillis)
            throws IOException {
        if (mode == Mode.IN_PLACE) {
            remote.put(local, remotePath);
            return;
        }
        String part = remotePath + PART_SUFFIX;
        try {
            remote.put(local, part);
            try {
                remote.rename(part, remotePath);
            } catch (IOException renameRefused) {
                // Some servers refuse to rename over an existing file: remove it and retry once.
                try { remote.remove(remotePath); } catch (IOException ignored) { }
                remote.rename(part, remotePath);
            }
        } catch (IOException e) {
            try { remote.remove(part); } catch (IOException ignored) { }
            throw e;
        }
        try {
            remote.setModifiedTime(remotePath, localModifiedMillis);
        } catch (IOException ignored) {
            // the server does not allow setting times; the upload itself succeeded
        }
    }
}
