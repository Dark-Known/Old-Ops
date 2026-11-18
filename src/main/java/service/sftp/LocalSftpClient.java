package service.sftp;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * An {@link SftpClient} over this computer's own filesystem, used when a task's credential is the built-in
 * "Local (this computer)" entry. "Remote" paths are ordinary local paths (forward slashes accepted, and
 * {@code /C:/x} is read as {@code C:\x}), so everything built on SftpClient (listing, batching, file-selection
 * rules, the ledger, the watcher, "delete/move after transfer") works the same for local-to-local copies.
 */
public final class LocalSftpClient implements SftpClient {

    private volatile boolean cancelled;

    /** The local file a "remote" path stands for. */
    static Path path(String remotePath) {
        String p = remotePath == null ? "" : remotePath.trim();
        if (p.matches("^/[A-Za-z]:([/\\\\].*)?$")) p = p.substring(1);   // /C:/x -> C:/x
        if (p.isEmpty()) p = ".";
        return Paths.get(p);
    }

    private void check() throws IOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("Transfer cancelled");
    }

    private static RemoteEntry entry(Path p) throws IOException {
        java.nio.file.attribute.BasicFileAttributes a = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class);
        Path name = p.getFileName();
        return new RemoteEntry(name == null ? p.toString() : name.toString(), a.size(),
                a.lastModifiedTime().toMillis() / 1000L, a.isDirectory());
    }

    @Override public List<RemoteEntry> list(String remoteDir) throws IOException {
        check();
        Path dir = path(remoteDir);
        if (!Files.isDirectory(dir)) throw new IOException("Folder not found: " + dir);
        List<RemoteEntry> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s.sorted(Comparator.comparing(Path::toString))::iterator) {
                try { out.add(entry(p)); } catch (IOException skipUnreadable) { /* vanished while listing */ }
            }
        }
        return out;
    }

    @Override public RemoteEntry stat(String remotePath) throws IOException {
        Path p = path(remotePath);
        return Files.exists(p) ? entry(p) : null;
    }

    @Override public String home() { return System.getProperty("user.home"); }

    @Override public void download(String remotePath, File local) throws IOException { copy(path(remotePath), local.toPath()); }

    @Override public void upload(File local, String remotePath) throws IOException { copy(local.toPath(), path(remotePath)); }

    /** Copies through a temporary name in the destination folder, then renames, so a reader never sees half a file. */
    private void copy(Path from, Path to) throws IOException {
        check();
        if (!Files.isRegularFile(from)) throw new IOException("File not found: " + from);
        if (Files.isDirectory(to)) to = to.resolve(from.getFileName());
        Path parent = to.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = to.resolveSibling(to.getFileName() + ".part");
        try {
            Files.copy(from, tmp, StandardCopyOption.REPLACE_EXISTING);
            check();
            Files.setLastModifiedTime(tmp, FileTime.fromMillis(Files.getLastModifiedTime(from).toMillis()));
            try { Files.move(tmp, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, to, StandardCopyOption.REPLACE_EXISTING); }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Override public void mkdirs(String remoteDir) throws IOException { Files.createDirectories(path(remoteDir)); }

    @Override public boolean delete(String remotePath) throws IOException { return Files.deleteIfExists(path(remotePath)); }

    @Override public void rename(String from, String to) throws IOException {
        Path dest = path(to);
        Path parent = dest.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try { Files.move(path(from), dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(path(from), dest, StandardCopyOption.REPLACE_EXISTING); }
    }

    @Override public void cancel() { cancelled = true; }

    @Override public void close() { }
}
