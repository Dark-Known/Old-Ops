package service.sftp;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

/** {@link SftpClient} on JSch, with trust-on-first-use host-key checking ({@link util.SshHostKeys}). */
public final class JschSftpClient implements SftpClient {

    private static final int SERVER_ALIVE_MS = 15_000;

    private final Session session;
    private final ChannelSftp channel;
    private volatile boolean cancelled;

    private JschSftpClient(Session session, ChannelSftp channel) {
        this.session = session;
        this.channel = channel;
    }

    public static JschSftpClient connect(String host, int port, String username, String password,
                                         int connectTimeoutMs) throws IOException {
        Session session = null;
        try {
            JSch jsch = new JSch();
            session = jsch.getSession(username, host, port);
            util.SshAuth.apply(jsch, session, password);
            util.SshHostKeys.apply(jsch, session);
            session.setServerAliveInterval(SERVER_ALIVE_MS);
            session.setServerAliveCountMax(4);
            session.connect(connectTimeoutMs);
            ChannelSftp ch = (ChannelSftp) session.openChannel("sftp");
            ch.connect(connectTimeoutMs);
            return new JschSftpClient(session, ch);
        } catch (Exception e) {
            if (session != null) session.disconnect();
            throw new IOException("SFTP connect to " + host + ":" + port + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<RemoteEntry> list(String remoteDir) throws IOException {
        try {
            Vector<ChannelSftp.LsEntry> raw = channel.ls(remoteDir);
            List<RemoteEntry> out = new ArrayList<>();
            for (ChannelSftp.LsEntry e : raw) {
                String n = e.getFilename();
                if (".".equals(n) || "..".equals(n)) continue;
                SftpATTRS a = e.getAttrs();
                out.add(new RemoteEntry(n, a.getSize(), a.getMTime(), a.isDir()));
            }
            return out;
        } catch (SftpException e) {
            throw wrap("list " + remoteDir, e);
        }
    }

    @Override
    public RemoteEntry stat(String remotePath) throws IOException {
        try {
            SftpATTRS a = channel.stat(remotePath);
            String n = remotePath.substring(remotePath.lastIndexOf('/') + 1);
            return new RemoteEntry(n, a.getSize(), a.getMTime(), a.isDir());
        } catch (SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return null;
            throw wrap("stat " + remotePath, e);
        }
    }

    @Override
    public void download(String remotePath, File local) throws IOException {
        File dir = local.getAbsoluteFile().getParentFile();
        Files.createDirectories(dir.toPath());
        File tmp = File.createTempFile(".opstool-", ".part", dir);
        try {
            long remoteMtime = channel.stat(remotePath).getMTime();
            channel.get(remotePath, tmp.getAbsolutePath());
            tmp.setLastModified(remoteMtime * 1000L); // keep the remote timestamp, like WinSCP does
            Files.move(tmp.toPath(), local.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (SftpException e) {
            throw wrap("download " + remotePath, e);
        } finally {
            tmp.delete();
        }
    }

    @Override
    public void upload(File local, String remotePath) throws IOException {
        String part = remotePath + ".part";
        try {
            channel.put(local.getAbsolutePath(), part, ChannelSftp.OVERWRITE);
            try {
                channel.rename(part, remotePath);
            } catch (SftpException renameFailed) {
                // Some servers refuse to rename over an existing file: remove it and retry once.
                try { channel.rm(remotePath); } catch (SftpException ignored) { }
                channel.rename(part, remotePath);
            }
            try {
                channel.setMtime(remotePath, (int) (local.lastModified() / 1000L)); // preserve timestamp
            } catch (SftpException ignored) {
                // server does not allow setting times - the upload itself succeeded
            }
        } catch (SftpException e) {
            try { channel.rm(part); } catch (Exception ignored) { }
            throw wrap("upload " + remotePath, e);
        }
    }

    @Override
    public void mkdirs(String remoteDir) throws IOException {
        StringBuilder path = new StringBuilder(remoteDir.startsWith("/") ? "/" : "");
        for (String part : remoteDir.split("/")) {
            if (part.isEmpty()) continue;
            if (path.length() > 0 && path.charAt(path.length() - 1) != '/') path.append('/');
            path.append(part);
            try {
                if (!channel.stat(path.toString()).isDir()) {
                    throw new IOException(path + " exists and is not a directory");
                }
            } catch (SftpException notThere) {
                try {
                    channel.mkdir(path.toString());
                } catch (SftpException e) {
                    throw wrap("mkdir " + path, e);
                }
            }
        }
    }

    @Override
    public boolean delete(String remotePath) throws IOException {
        try {
            channel.rm(remotePath);
            return true;
        } catch (SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return false;
            throw wrap("delete " + remotePath, e);
        }
    }

    @Override
    public void cancel() {
        cancelled = true;
        close();
    }

    @Override
    public void close() {
        try { channel.disconnect(); } catch (Exception ignored) { }
        try { session.disconnect(); } catch (Exception ignored) { }
    }

    private IOException wrap(String what, SftpException e) {
        return new IOException(cancelled ? what + " cancelled" : what + " failed: " + e.getMessage(), e);
    }
}
