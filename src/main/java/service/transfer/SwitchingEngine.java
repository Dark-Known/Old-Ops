package service.transfer;

import model.Credential;

import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Chooses between two engines on every call, so the setting can be changed without a restart.
 */
public final class SwitchingEngine implements TransferEngine {

    private final BooleanSupplier useFirst;
    private final TransferEngine first;
    private final TransferEngine second;

    /** @param useFirst true to use {@code first}, false to use {@code second}; asked again on every call */
    public SwitchingEngine(BooleanSupplier useFirst, TransferEngine first, TransferEngine second) {
        this.useFirst = useFirst;
        this.first = first;
        this.second = second;
    }

    private TransferEngine current() { return useFirst.getAsBoolean() ? first : second; }

    @Override
    public boolean run(TransferJob job, Consumer<String> logLine, String taskId) throws Exception {
        return current().run(job, logLine, taskId);
    }

    @Override
    public Map<String, RemoteFileStat> listFiles(Credential target, String password, String dirPath,
                                                 Consumer<String> logLine) throws Exception {
        return current().listFiles(target, password, dirPath, logLine);
    }
}
