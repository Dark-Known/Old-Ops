package service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

/** Size-based batching for large transfers, split out of {@link TransferService} so the rules can be tested alone. */
public final class TransferBatching {

    private TransferBatching() { }

    /**
     * Splits {@code items} into consecutive batches of at most {@code maxBytes} each. An item bigger than the
     * cap goes alone in its own batch (it is never split); {@code maxBytes <= 0} means no cap; unknown or
     * negative sizes count as 0. Order is kept.
     */
    public static <T> List<List<T>> chunkBySize(List<T> items, ToLongFunction<T> sizeOf, long maxBytes) {
        List<List<T>> out = new ArrayList<>();
        if (items.isEmpty()) return out;
        if (maxBytes <= 0) maxBytes = Long.MAX_VALUE;
        List<T> current = new ArrayList<>();
        long currentSize = 0;
        for (T item : items) {
            long sz = Math.max(sizeOf.applyAsLong(item), 0);
            if (!current.isEmpty() && currentSize + sz > maxBytes) {
                out.add(current);
                current = new ArrayList<>();
                currentSize = 0;
            }
            current.add(item);
            currentSize += sz;
        }
        if (!current.isEmpty()) out.add(current);
        return out;
    }

    /** Each session carries at least one file: 3 files with 3 sessions allowed run as 3 sessions. */
    public static final int MIN_ITEMS_PER_SESSION = 1;

    /**
     * Like {@link #chunkBySize}, but when several sessions may run at once and the size cap alone would leave some
     * idle (for example 60 MB of files and a 100 MB cap gives one batch), the work is spread over up to {@code sessions}
     * batches of similar size instead. Never more sessions than there are files.
     */
    public static <T> List<List<T>> planForSessions(List<T> items, ToLongFunction<T> sizeOf, long maxBytes, int sessions) {
        List<List<T>> bySize = chunkBySize(items, sizeOf, maxBytes);
        int wanted = Math.min(Math.max(sessions, 1), items.size() / MIN_ITEMS_PER_SESSION);
        if (wanted <= 1 || bySize.size() >= wanted) return bySize;
        // Longest-processing-time balancing: biggest file first, always into the batch that is lightest so far.
        // Unknown sizes count as 1, so equal files (or no sizes at all) are shared out evenly.
        List<T> sorted = new java.util.ArrayList<>(items);
        sorted.sort((x, y) -> Long.compare(Math.max(sizeOf.applyAsLong(y), 1), Math.max(sizeOf.applyAsLong(x), 1)));
        List<List<T>> buckets = new java.util.ArrayList<>();
        long[] load = new long[wanted];
        for (int i = 0; i < wanted; i++) buckets.add(new java.util.ArrayList<>());
        for (T item : sorted) {
            int lightest = 0;
            for (int i = 1; i < wanted; i++) if (load[i] < load[lightest]) lightest = i;
            buckets.get(lightest).add(item);
            load[lightest] += Math.max(sizeOf.applyAsLong(item), 1);
        }
        return buckets;
    }
}
