package service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

/**
 * Where log files live: <code>&lt;data folder&gt;/logs</code>, together with the task logs, never loose in the data
 * folder next to the databases. Files that an older version left in the data folder are moved over the first time
 * they are asked for.
 */
public final class LogFiles {
    private static final Logger log = Logger.getLogger(LogFiles.class.getName());
    public static final String DIR = "logs";

    private LogFiles() {}

    public static File dir(File dataDir) {
        File d = new File(dataDir, DIR);
        d.mkdirs();
        return d;
    }

    /** The log file {@code name} inside the logs folder, adopting a copy an older version left in the data folder. */
    public static File file(File dataDir, String name) {
        File target = new File(dir(dataDir), name);
        File legacy = new File(dataDir, name);
        if (legacy.isFile() && !target.exists()) {
            try {
                Files.move(legacy.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
                log.info("Moved " + name + " into the logs folder");
            } catch (IOException e) {
                try { Files.move(legacy.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING); }
                catch (IOException ignored) { /* keep reading/writing the new location; the old copy stays */ }
            }
        }
        return target;
    }

    /** Moves every file in the data folder whose name starts with {@code prefix} and ends with {@code suffix}. */
    public static void adoptAll(File dataDir, String prefix, String suffix) {
        File[] old = dataDir.listFiles((d, n) -> n.startsWith(prefix) && n.endsWith(suffix));
        if (old == null) return;
        for (File f : old) file(dataDir, f.getName());
    }
}
