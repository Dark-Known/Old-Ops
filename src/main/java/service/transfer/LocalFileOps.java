package service.transfer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

/** Small local-disk helpers shared by file transfers and backups. */
public final class LocalFileOps {
    private LocalFileOps() { }

    /** Moves a single file, replacing the destination if it already exists. Logs the outcome; returns success. */
    public static boolean moveReplacing(Path src, Path dest, Consumer<String> logLine) {
        try {
            Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING);
            logLine.accept("[SUCCESS] Backed up: " + src.getFileName());
            return true;
        } catch (IOException ex) {
            logLine.accept("[ERROR] Failed to back up " + src.getFileName() + ": " + ex.getMessage());
            return false;
        }
    }

    /** Sleeps between batches; an interrupt stops the wait and is preserved for the caller. */
    public static void pauseBetweenBatches(int intervalSeconds, Consumer<String> logLine) {
        try {
            logLine.accept("[INFO] Pausing " + intervalSeconds + "s before next batch...");
            Thread.sleep(intervalSeconds * 1000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
