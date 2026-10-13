package service;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Age- and size-based purging for a SQLite file holding one or more append-only event tables
 * (each with an {@code id} and a {@code started_at} ISO-timestamp column).
 *
 * <p>Deletes run in small chunks and the owning service's lock is taken <em>per chunk</em>, so
 * the threads that record events keep getting through while a large purge is in progress. Only
 * the final {@code VACUUM}, which rewrites the file to give the space back, holds the lock for
 * longer, and it is skipped unless it would reclaim something worthwhile.
 */
final class SqlitePurge {
    private SqlitePurge() {}

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int CHUNK = 2_000;
    /** After a size purge the file is brought down to this fraction of the cap, so it doesn't re-trigger at once. */
    private static final double TARGET_FRACTION = 0.90;
    private static final int MAX_SIZE_ITERATIONS = 200;
    private static final long VACUUM_MIN_FREE_BYTES = 1L << 20; // 1 MB

    /** On-disk footprint: database file plus its write-ahead log. */
    static long fileBytes(File db) {
        long n = db.length();
        n += new File(db.getPath() + "-wal").length();
        return n;
    }

    static PurgeResult run(String label, Object lock, Connection conn, File dbFile, List<String> tables,
                           PurgePolicy policy, BooleanSupplier cancelled) {
        long t0 = System.nanoTime();
        long before = fileBytes(dbFile);
        if (conn == null) return PurgeResult.failure(label, before, 0, "database is not open");
        int byAge = 0, bySize = 0;
        try {
            if (policy.keepDays() > 0) {
                String cutoff = LocalDateTime.now().minusDays(policy.keepDays()).format(TS);
                for (String table : tables) {
                    int n;
                    do {
                        if (cancelled.getAsBoolean()) break;
                        synchronized (lock) {
                            n = exec(conn, "DELETE FROM " + table + " WHERE id IN "
                                    + "(SELECT id FROM " + table + " WHERE started_at < ? LIMIT " + CHUNK + ")", cutoff);
                        }
                        byAge += n;
                    } while (n == CHUNK);
                }
            }
            if (policy.maxBytes() > 0) {
                bySize = purgeToSize(lock, conn, tables, policy.maxBytes(), cancelled);
            }
            if (byAge + bySize > 0) reclaim(lock, conn, dbFile);
            long after = fileBytes(dbFile);
            return new PurgeResult(label, byAge, bySize, before, after, (System.nanoTime() - t0) / 1_000_000, null);
        } catch (SQLException e) {
            return new PurgeResult(label, byAge, bySize, before, fileBytes(dbFile),
                    (System.nanoTime() - t0) / 1_000_000, e.getMessage());
        }
    }

    /** Deletes oldest rows until the live data fits under {@code 90 %} of the cap. */
    private static int purgeToSize(Object lock, Connection conn, List<String> tables, long maxBytes,
                                   BooleanSupplier cancelled) throws SQLException {
        long target = (long) (maxBytes * TARGET_FRACTION);
        int deleted = 0;
        for (int i = 0; i < MAX_SIZE_ITERATIONS && !cancelled.getAsBoolean(); i++) {
            synchronized (lock) {
                long live = liveBytes(conn);
                if (live <= (i == 0 ? maxBytes : target)) break;      // only act once the cap is exceeded
                long[] counts = new long[tables.size()];
                long total = 0;
                for (int t = 0; t < counts.length; t++) { counts[t] = count(conn, tables.get(t)); total += counts[t]; }
                if (total == 0) break;
                double avg = (double) live / total;                   // average bytes per row, indexes included
                long need = (long) Math.ceil((live - target) / Math.max(avg, 1.0));
                need = Math.max(1, Math.min(need, 5_000));
                int removed = 0;
                for (int t = 0; t < counts.length; t++) {
                    if (counts[t] == 0) continue;
                    long share = Math.max(1, (long) Math.ceil((double) need * counts[t] / total));
                    removed += exec(conn, "DELETE FROM " + tables.get(t) + " WHERE id IN (SELECT id FROM "
                            + tables.get(t) + " ORDER BY started_at, id LIMIT " + share + ")");
                }
                if (removed == 0) break;
                deleted += removed;
            }
        }
        return deleted;
    }

    /** Gives freed pages back to the operating system when that is worth doing. */
    private static void reclaim(Object lock, Connection conn, File dbFile) throws SQLException {
        synchronized (lock) {
            long free = freeBytes(conn);
            long pages = pragma(conn, "page_count") * 1L, size = pragma(conn, "page_size");
            boolean worthwhile = free >= VACUUM_MIN_FREE_BYTES || (pages > 0 && free * 10 >= pages * size);
            try (Statement st = conn.createStatement()) {
                if (worthwhile) st.execute("VACUUM");
                try (ResultSet rs = st.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) { /* ignore result */ }
            }
        }
    }

    static long liveBytes(Connection conn) throws SQLException {
        return (pragma(conn, "page_count") - pragma(conn, "freelist_count")) * pragma(conn, "page_size");
    }

    static long freeBytes(Connection conn) throws SQLException {
        return pragma(conn, "freelist_count") * pragma(conn, "page_size");
    }

    private static long pragma(Connection conn, String name) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("PRAGMA " + name)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private static long count(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private static int exec(Connection conn, String sql, String... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setString(i + 1, params[i]);
            return ps.executeUpdate();
        }
    }
}
