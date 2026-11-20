package service;

import model.ScheduledTask;

/**
 * The batch size and number of parallel SFTP sessions a transfer uses: the task's own value if it has one, otherwise the
 * global one from Settings. 0 on the task means "use the global value".
 */
final class TransferTuning {

    static final int MAX_CONCURRENCY = 8;
    static final int MAX_BATCH_MB = 10_240;   // 10 GB per batch is more than any one session should carry

    final long maxBytes;
    final int concurrency;

    private TransferTuning(long maxBytes, int concurrency) {
        this.maxBytes = maxBytes;
        this.concurrency = concurrency;
    }

    /**
     * @param task              the task being run (null: use the globals)
     * @param globalMaxBytes    the Settings batch cap in bytes
     * @param globalConcurrency the Settings number of parallel batches
     */
    static TransferTuning of(ScheduledTask task, long globalMaxBytes, int globalConcurrency) {
        long bytes = globalMaxBytes;
        int conc = Math.max(1, globalConcurrency);
        if (task != null) {
            int mb = task.getTransferBatchMaxMb();
            if (mb > 0) bytes = Math.min(mb, MAX_BATCH_MB) * 1024L * 1024L;
            int c = task.getTransferConcurrency();
            if (c > 0) conc = c;
        }
        return new TransferTuning(bytes, Math.min(conc, MAX_CONCURRENCY));
    }
}
