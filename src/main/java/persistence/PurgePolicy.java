package persistence;

/**
 * How much history a database may keep: at most {@code keepDays} days and at most {@code maxMb}
 * megabytes. Zero means "no limit" for that metric. Both limits apply; whichever is hit first
 * removes the oldest rows. A plain value: where the numbers come from is {@code service.RetentionSettings}'s concern.
 */
public record PurgePolicy(int keepDays, long maxMb) {

    public PurgePolicy {
        keepDays = Math.max(0, keepDays);
        maxMb = Math.max(0, maxMb);
    }

    public long maxBytes() { return maxMb * 1024L * 1024L; }

    public boolean unlimited() { return keepDays == 0 && maxMb == 0; }

    @Override public String toString() {
        return (keepDays == 0 ? "any age" : keepDays + " days") + " / " + (maxMb == 0 ? "no size cap" : maxMb + " MB");
    }
}
