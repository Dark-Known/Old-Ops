package service;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Vector;

/**
 * Thin wrapper around a single JSch SFTP session used purely for interactive
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

    private final Session session;
    private final ChannelSftp channel;

    private SftpBrowseService(Session session, ChannelSftp channel) {
        this.session = session;
        this.channel = channel;
    }

    public static SftpBrowseService connect(String host, String username, String password) throws Exception {
        JSch jsch = new JSch();
        Session session = jsch.getSession(username, host, 22);
        session.setPassword(password == null ? "" : password);
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        session.setConfig(config);
        session.connect(8_000);

        ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
        channel.connect(6_000);
        return new SftpBrowseService(session, channel);
    }

    /** The remote user's home / landing directory — used as the initial browse path. */
    public String home() throws Exception {
        return channel.pwd();
    }

    @SuppressWarnings("unchecked")
    public List<Entry> list(String path) throws Exception {
        List<Entry> out = new ArrayList<>();
        Vector<ChannelSftp.LsEntry> entries = channel.ls(path);
        for (ChannelSftp.LsEntry e : entries) {
            String n = e.getFilename();
            if (".".equals(n) || "..".equals(n)) continue;
            out.add(new Entry(n, e.getAttrs().isDir(), e.getAttrs().getSize()));
        }
        out.sort((a, b) -> {
            if (a.directory != b.directory) return a.directory ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return out;
    }

    @Override
    public void close() {
        if (channel != null && channel.isConnected()) channel.disconnect();
        if (session != null && session.isConnected()) session.disconnect();
    }
}
