package service.transfer;

import java.util.Locale;

/** Human-readable byte counts for log lines and alerts. */
public final class ByteFormat {
    private ByteFormat() { }

    /** {@code 1536} → {@code "1.5 KB"}; values under 1 KB are shown in bytes. */
    public static String humanReadable(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format(Locale.ROOT, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
}
