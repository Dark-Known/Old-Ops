package service;

import model.FileSelection;
import model.ScheduledTask;
import model.WatchOptions;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/**
 * "Watch a folder" without any transfer: looks at a folder (on this PC, a network share, or over SFTP), compares it with what it
 * saw before and reports the files that are new or have changed. What it saw before is kept in the transfer ledger, so it survives
 * restarts. The first look only learns what is already there (nothing is reported). The task's File Selection decides which
 * names count, how long a file must have stayed unchanged ("settled"), and the "no new file for N minutes" alert.
 */
public final class FolderWatch {

    /** One file seen in the folder. */
    public record Item(String name, long size, long mtimeMillis) {}

    /** Lists the folder; throws when it can not be read (the run then fails and an alert can fire). */
    public interface Lister {
        List<Item> list(ScheduledTask task, Consumer<String> log) throws Exception;
    }

    /** What one look found. */
    public record Outcome(boolean baseline, List<Item> added, List<Item> changed, boolean resumed, int watching, int ignored) {
        public boolean quiet() { return !baseline && added.isEmpty() && changed.isEmpty(); }
    }

    private static final String MARKER = "\u0000watching";

    private final File dataDir;
    private final Lister lister;
    private final LongSupplier clock;

    public FolderWatch(File dataDir, Lister lister) { this(dataDir, lister, System::currentTimeMillis); }

    FolderWatch(File dataDir, Lister lister, LongSupplier clock) {
        this.dataDir = dataDir;
        this.lister = lister;
        this.clock = clock;
    }

    public Outcome look(ScheduledTask task, Consumer<String> log) throws Exception {
        WatchOptions opts = WatchOptions.parse(task.getWatchOptions());
        FileSelection sel = task.getFileSelection();
        List<Item> all = lister.list(task, log);

        // Names that satisfy the task's rules and have stopped changing; the rest is looked at again next time.
        List<Item> matching = sel.filterOnly(all, Item::name, Item::size, Item::mtimeMillis);
        int ignored = all.size() - matching.size();

        TransferLedger ledger = new TransferLedger(dataDir, clock);
        TransferLedger.Snapshot known = ledger.snapshot(task.getId());
        // A marker file name that can not exist in a folder tells "looked before" from "never looked" (an empty folder leaves nothing else).
        boolean first = !known.wasSent(MARKER, 0, 0, FileSelection.SentPolicy.UNCHANGED);

        List<Item> added = new ArrayList<>(), changed = new ArrayList<>();
        for (Item it : matching) {
            if (known.wasSent(it.name(), it.size(), it.mtimeMillis(), FileSelection.SentPolicy.UNCHANGED)) continue;
            boolean seenName = known.wasSent(it.name(), it.size(), it.mtimeMillis(), FileSelection.SentPolicy.ANY_NAME);
            if (seenName) changed.add(it); else added.add(it);
        }

        if (first) {
            remember(ledger, task, matching);
            ledger.record(task.getId(), List.of(new TransferLedger.Entry(MARKER, 0, 0)));
            ledger.startWatching(task.getId());
            return new Outcome(true, List.of(), List.of(), false, matching.size(), ignored);
        }

        if (!opts.onNew()) added = new ArrayList<>();
        if (!opts.onChanged()) changed = new ArrayList<>();
        // Everything seen is remembered, also what this task was told not to report, so it is not reported later.
        remember(ledger, task, matching);

        boolean resumed = false;
        if (!added.isEmpty() || !changed.isEmpty()) resumed = ledger.markFilesTransferred(task.getId());
        return new Outcome(false, added, changed, resumed, matching.size(), ignored);
    }

    private static void remember(TransferLedger ledger, ScheduledTask task, List<Item> items) throws IOException {
        List<TransferLedger.Entry> e = new ArrayList<>();
        for (Item it : items) e.add(new TransferLedger.Entry(it.name(), it.size(), it.mtimeMillis()));
        ledger.record(task.getId(), e);
    }

    /** Lists a folder on this PC or a network share: regular files directly inside it. */
    public static List<Item> listLocal(String dir) throws IOException {
        Path p = new File(dir).toPath();
        if (!Files.isDirectory(p)) throw new IOException("The folder does not exist or can not be read: " + dir);
        List<Item> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(p)) {
            for (Path f : (Iterable<Path>) s::iterator) {
                try {
                    if (!Files.isRegularFile(f)) continue;
                    out.add(new Item(f.getFileName().toString(), Files.size(f), Files.getLastModifiedTime(f).toMillis()));
                } catch (IOException ignored) { /* vanished while listing */ }
            }
        }
        return out;
    }
}
