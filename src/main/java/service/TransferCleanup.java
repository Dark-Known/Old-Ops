package service;

import model.FileSelection.AfterAction;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * What happens to the original files once a transfer has fully succeeded: leave them, delete them, or
 * move them into an archive folder.
 *
 * <p>The rules this class holds to:
 * <ul>
 *   <li>It is only ever called after the <b>whole</b> transfer succeeded — never partway.</li>
 *   <li>An archived file is <b>never overwritten</b>: if the name is taken, a timestamp is added to the
 *       name ({@code report_20261026_101500.csv}, then {@code _2}, {@code _3}… if even that is taken).</li>
 *   <li>A local original that changed since it was listed (size or time differ) is left alone — it was
 *       probably modified while being sent, so what was sent may not be what is on disk now.</li>
 *   <li>It never throws and never turns a successful transfer into a failure: problems are logged as
 *       warnings and counted in the {@link Result}.</li>
 * </ul>
 *
 * <p>This class does the local side itself and plans the remote side ({@link #planRemoteMoves}); the
 * transfer code executes the remote plan over SFTP.
 */
public final class TransferCleanup {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private TransferCleanup() {
    }

    /**
     * {@code name} if it is free, otherwise {@code name} with a timestamp before the extension, otherwise
     * that with {@code _2}, {@code _3}… appended. {@code taken} is not modified.
     */
    static String archiveName(String name, Set<String> taken, LocalDateTime now) {
        if (!taken.contains(name)) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;   // a leading dot (".env") is not an extension
        String ext = dot > 0 ? name.substring(dot) : "";
        String stamped = base + "_" + now.format(STAMP) + ext;
        if (!taken.contains(stamped)) return stamped;
        for (int i = 2; ; i++) {
            String n = base + "_" + now.format(STAMP) + "_" + i + ext;
            if (!taken.contains(n)) return n;
        }
    }

    /**
     * The file name part of a remote or local path.
     */
    static String baseName(String path) {
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return i >= 0 ? path.substring(i + 1) : path;
    }

    /**
     * Where each remote original goes in the archive folder. {@code existingNames} are the names already
     * in that folder; names chosen for earlier files of this same batch are avoided too.
     */
    public static List<RemoteMove> planRemoteMoves(List<String> fromPaths, String archiveDir,
                                                   Set<String> existingNames, LocalDateTime now) {
        String dir = archiveDir.endsWith("/") ? archiveDir : archiveDir + "/";
        Set<String> taken = new HashSet<>(existingNames);
        List<RemoteMove> plan = new ArrayList<>();
        for (String from : fromPaths) {
            String name = archiveName(baseName(from), taken, now);
            taken.add(name);
            plan.add(new RemoteMove(from, dir + name));
        }
        return plan;
    }

    // ── Names ────────────────────────────────────────────────────────

    /**
     * True when {@code copy} exists as a regular file and, if {@code expectedSize >= 0}, has exactly that size.
     */
    public static boolean copyIsComplete(Path copy, long expectedSize) {
        try {
            return Files.isRegularFile(copy) && (expectedSize < 0 || Files.size(copy) == expectedSize);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Deletes or moves local originals after a successful upload. {@code archiveDir} is only used for
     * {@link AfterAction#MOVE}; it is created if missing.
     */
    public static Result cleanupLocal(List<LocalOriginal> originals, AfterAction action, Path archiveDir,
                                      Consumer<String> log, LocalDateTime now) {
        if (action == AfterAction.KEEP || originals.isEmpty()) return new Result(0, 0, originals.size());
        int done = 0, failed = 0, kept = 0;
        List<String> stillThere = new ArrayList<>();

        Set<String> taken = new HashSet<>();
        boolean folderReady = false;
        if (action == AfterAction.MOVE) {
            try {
                Files.createDirectories(archiveDir);
                folderReady = true;
                try (var names = Files.list(archiveDir)) {
                    names.forEach(p -> taken.add(p.getFileName().toString()));
                }
            } catch (IOException | RuntimeException e) {
                log.accept("[WARN] Cannot use the archive folder '" + archiveDir + "': " + e.getMessage());
            }
        }

        for (LocalOriginal o : originals) {
            String name = o.path().getFileName().toString();
            try {
                if (!Files.exists(o.path())) {
                    done++;
                    continue;
                } // already gone — nothing left to clean up
                if (changedSinceListed(o)) {
                    kept++;
                    stillThere.add(name);
                    log.accept("[WARN] Kept original '" + name + "': it changed after it was listed, "
                            + "so what was sent may differ from what is on disk now.");
                    continue;
                }
                if (action == AfterAction.DELETE) {
                    Files.delete(o.path());
                    done++;
                    log.accept("[INFO] Deleted original: " + o.path());
                } else {
                    if (!folderReady) throw new IOException("archive folder is not available");
                    Path moved = moveWithoutOverwriting(o.path(), archiveDir, name, taken, now);
                    done++;
                    log.accept("[INFO] Moved original: " + o.path() + " -> " + moved);
                }
            } catch (IOException | RuntimeException e) {
                failed++;
                stillThere.add(name);
                log.accept("[WARN] Could not " + (action == AfterAction.DELETE ? "delete" : "move")
                        + " original '" + name + "': " + e.getMessage());
            }
        }
        if (!stillThere.isEmpty()) {
            log.accept("[WARN] The transfer succeeded, but " + stillThere.size() + " original file(s) are still in place: "
                    + String.join(", ", stillThere));
        }
        return new Result(done, failed, kept);
    }

    private static boolean changedSinceListed(LocalOriginal o) {
        try {
            if (o.size() >= 0 && Files.size(o.path()) != o.size()) return true;
            return o.mtimeMillis() > 0 && Files.getLastModifiedTime(o.path()).toMillis() != o.mtimeMillis();
        } catch (IOException e) {
            return true; // cannot tell — do not touch it
        }
    }

    // ── Safety checks ────────────────────────────────────────────────

    /**
     * Moves into {@code dir} under the first free name; a name that appears meanwhile is skipped, never replaced.
     */
    private static Path moveWithoutOverwriting(Path source, Path dir, String name, Set<String> taken,
                                               LocalDateTime now) throws IOException {
        for (int attempt = 0; attempt < 1000; attempt++) {
            String target = archiveName(name, taken, now);
            taken.add(target);
            Path dest = dir.resolve(target);
            try {
                return Files.move(source, dest);          // no REPLACE_EXISTING: an existing file makes this throw
            } catch (FileAlreadyExistsException exists) {
                // somebody put a file there since we listed the folder — try the next name
            }
        }
        throw new IOException("no free name found in " + dir);
    }

    // ── Local originals (uploads) ────────────────────────────────────

    /**
     * An original on this PC, with what it looked like when it was listed for the transfer.
     */
    public record LocalOriginal(Path path, long size, long mtimeMillis) {
    }

    /**
     * One remote rename: {@code from} → {@code to}, both full remote paths.
     */
    public record RemoteMove(String from, String to) {
    }

    /**
     * What a cleanup did. {@code failed} + {@code kept} originals are still where they were.
     */
    public record Result(int done, int failed, int kept) {
        public boolean clean() {
            return failed == 0;
        }
    }
}
