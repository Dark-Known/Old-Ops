package service.transfer;

import service.sftp.RemoteEntry;
import service.sftp.SftpClient;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * Runs a {@link TransferJob} on an {@link SftpClient}: one connection, the commands in order, then close.
 *
 * <p>With {@link TransferJob#stopOnError()} the first failing command ends the job as failed; otherwise a
 * failure is logged as a warning and the job goes on (and counts as successful). A failure to connect at
 * all is thrown so the caller can classify it (authentication, network, timeout). A local destination
 * that is an existing folder, or a remote destination that is an existing folder, receives the file under
 * its original name. Downloads and uploads keep timestamps (the client's job).
 */
public final class SftpJobRunner {

    /** Opens connections; replaceable in tests. */
    @FunctionalInterface
    public interface Connector {
        SftpClient connect(String host, int port, String user, String password) throws IOException;
    }

    private final Connector connector;
    private final Consumer<SftpClient> onOpen;
    private final Consumer<SftpClient> onClose;

    public SftpJobRunner(Connector connector, Consumer<SftpClient> onOpen, Consumer<SftpClient> onClose) {
        this.connector = connector;
        this.onOpen = onOpen;
        this.onClose = onClose;
    }

    /** @return true if the job succeeded (see the class comment for what that means with and without stopOnError) */
    public boolean run(TransferJob job, Consumer<String> log) throws IOException {
        String[] hostAndPort = splitHostPort(job.target().getHost());
        String user = job.target().getUsername() == null ? "" : job.target().getUsername();
        SftpClient client = connector.connect(hostAndPort[0], Integer.parseInt(hostAndPort[1]), user, job.password());
        if (onOpen != null) onOpen.accept(client);
        int failures = 0;
        try {
            for (TransferCommand c : job.commands()) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Transfer cancelled");
                try {
                    execute(client, c, log);
                } catch (IOException | RuntimeException e) {
                    failures++;
                    if (job.stopOnError()) {
                        log.accept("[ERROR] " + verb(c) + " failed: " + e.getMessage());
                        return false;
                    }
                    log.accept("[WARN] " + verb(c) + " failed (continuing): " + e.getMessage());
                }
            }
            return job.stopOnError() ? failures == 0 : true;
        } finally {
            try { client.close(); } finally { if (onClose != null) onClose.accept(client); }
        }
    }

    private static void execute(SftpClient client, TransferCommand c, Consumer<String> log) throws IOException {
        if (c instanceof TransferCommand.Download d) {
            File local = new File(d.localPath());
            RemoteEntry re = client.stat(d.remotePath());
            if (re == null) throw new IOException("Remote file not found: " + d.remotePath());
            if (local.isDirectory()) local = new File(local, re.name());
            client.download(d.remotePath(), local);
            log.accept("[INFO] Downloaded " + d.remotePath() + " (" + re.size() + " bytes)");
            if (d.deleteRemoteAfter()) {
                // Same session, but only once the copy on this PC is verified to be complete.
                if (!local.isFile() || local.length() != re.size()) {
                    throw new IOException("Kept " + d.remotePath() + " on the server: the copy on this PC is missing or has the wrong size");
                }
                if (!client.delete(d.remotePath())) throw new IOException("Could not delete " + d.remotePath() + " on the server");
            }
        } else if (c instanceof TransferCommand.Upload u) {
            File local = new File(u.localPath());
            String remote = u.remotePath();
            if (!local.isFile()) throw new IOException("Local file not found: " + local);
            RemoteEntry existing = client.stat(remote);
            if (existing != null && existing.directory()) {
                remote = remote.endsWith("/") ? remote + local.getName() : remote + "/" + local.getName();
            }
            client.upload(local, remote);
            log.accept("[INFO] Uploaded " + local.getName() + " -> " + remote + " (" + local.length() + " bytes)");
        } else if (c instanceof TransferCommand.MakeRemoteDir m) {
            client.mkdirs(m.remotePath());
        } else if (c instanceof TransferCommand.DeleteRemote r) {
            if (!client.delete(r.remotePath())) throw new IOException("Remote file not found: " + r.remotePath());
        } else if (c instanceof TransferCommand.MoveRemote m) {
            client.rename(m.fromPath(), m.toPath());
            log.accept("[INFO] Moved " + m.fromPath() + " -> " + m.toPath());
        } else {
            throw new IOException("Unsupported command: " + c);
        }
    }

    /** The short name used in log lines ({@code get failed: ...}), as it always was. */
    static String verb(TransferCommand c) {
        if (c instanceof TransferCommand.Download) return "get";
        if (c instanceof TransferCommand.Upload) return "put";
        if (c instanceof TransferCommand.DeleteRemote) return "rm";
        if (c instanceof TransferCommand.MoveRemote) return "mv";
        return "mkdir";
    }

    /** {@code host} or {@code host:port} -> [host, port]; the port defaults to 22. */
    static String[] splitHostPort(String hostPart) throws IOException {
        if (hostPart == null || hostPart.isEmpty()) throw new IOException("No host to connect to");
        String host = hostPart, port = "22";
        int c = hostPart.lastIndexOf(':');
        if (c > 0 && !hostPart.endsWith("]")) {
            host = hostPart.substring(0, c);
            String p = hostPart.substring(c + 1);
            if (!p.isEmpty()) port = p;
        }
        if (host.isEmpty()) throw new IOException("No host to connect to");
        return new String[]{host, port};
    }
}
