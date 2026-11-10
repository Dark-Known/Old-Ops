package service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * Size-based batching for large transfers, split out of {@link TransferService} so the rules can be tested alone.
 */
public final class TransferBatching {

    private TransferBatching() {
    }

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
}
