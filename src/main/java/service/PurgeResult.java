package service;

/** What one purge of one database removed. */
public record PurgeResult(String database, int deletedByAge, int deletedBySize,
                          long sizeBefore, long sizeAfter, long millis, String error) {

    public int deleted() { return deletedByAge + deletedBySize; }

    public boolean failed() { return error != null; }

    public long reclaimed() { return Math.max(0, sizeBefore - sizeAfter); }

    public static PurgeResult failure(String database, long sizeBefore, long millis, String error) {
        return new PurgeResult(database, 0, 0, sizeBefore, sizeBefore, millis, error);
    }

    public String summary() {
        if (failed()) return database + ": purge failed — " + error;
        if (deleted() == 0) return database + ": nothing to purge (" + human(sizeAfter) + ")";
        return database + ": removed " + deleted() + " row(s)"
                + (deletedByAge > 0 ? " (" + deletedByAge + " by age" + (deletedBySize > 0 ? ", " + deletedBySize + " by size" : "") + ")"
                                    : " (by size)")
                + ", " + human(sizeBefore) + " → " + human(sizeAfter);
    }

    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        return String.format("%.2f GB", mb / 1024.0);
    }
}
