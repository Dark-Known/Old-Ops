package service;

import service.sftp.ConnectOptions;
import service.sftp.JschSftpClient;
import service.sftp.RemoteEntry;
import service.sftp.SftpClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin wrapper around a single SFTP session used purely for interactive
 * directory browsing (see {@code ui.DirectoryBrowserDialog}) — as opposed to
 * {@link RemoteFileMetadataServiceFactory}, which opens a session for an
 * actual transfer run. Caller owns the lifecycle: {@link #connect} to open,
 * {@link #close()} when the browse dialog is dismissed.
 */
public final class SftpBrowseService implements AutoCloseable {

    public static final class Entry {
        public final String name;
        public final boolean directory;
        public final long size;

        Entry(String name, boolean directory, long size) {
            this.name = name;
            this.directory = directory;
            this.size = size;
        }
    }

    private final SftpClient client;

    private String host, user;
    private long openedAt = System.currentTimeMillis();
    private boolean reported = false;

    private SftpBrowseService(SftpClient client) {
        this.client = client;
    }

    private static final ConnectOptions OPTIONS = ConnectOptions.of(ConnectOptions.DEFAULT_PORT, 8_000, 6_000);

    public static SftpBrowseService connect(String host, String username, String password) throws Exception {
        SftpClient client;
        try {
            client = JschSftpClient.connect(host, username, password, OPTIONS);
        } catch (Exception ex) {
            SshSessionEvents.uiFailed("Remote folder browser", host, username, ex.getMessage());
            throw ex;
        }
        SftpBrowseService svc = new SftpBrowseService(client);
        svc.host = host; svc.user = username; svc.reported = true;
        SshSessionEvents.uiOpened("Remote folder browser", host, username);
        return svc;
    }

    /** The remote user's home / landing directory — used as the initial browse path. */
    public String home() throws Exception {
        return client.home();
    }

    public List<Entry> list(String path) throws Exception {
        List<Entry> out = new ArrayList<>();
        for (RemoteEntry e : client.list(path)) {
            out.add(new Entry(e.name(), e.directory(), e.size()));
        }
        out.sort((a, b) -> {
            if (a.directory != b.directory) return a.directory ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return out;
    }

    @Override
    public void close() {
        client.close();
        if (reported) {
            reported = false;
            SshSessionEvents.uiClosed("Remote folder browser", host, user, System.currentTimeMillis() - openedAt);
        }
    }
}
