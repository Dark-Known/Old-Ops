package service.transfer;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Configuration and shared plumbing for the transfer engines. */
public final class EngineSettings {

    private EngineSettings() {}

    /** app-config.xml {@code <transferEngine>}: the in-process SFTP engine unless it is set to {@code winscp}. Read on every call. */
    public static boolean useSftpEngine() {
        String v = util.AppConfig.readValue("transferEngine");
        return v == null || !v.trim().equalsIgnoreCase("winscp");
    }

    /** app-config.xml {@code <winscpMaxRuntimeMinutes>}; 0 or absent = no cap (default). */
    public static long maxRuntimeMinutes() {
        try {
            String v = util.AppConfig.readValue("winscpMaxRuntimeMinutes");
            return v == null ? 0 : Math.max(0, Long.parseLong(v.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static final ScheduledExecutorService WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "winscp-watchdog");
                t.setDaemon(true);
                return t;
            });

    /** Runs {@code onTimeout} after {@code minutes}, unless the returned future is cancelled first. */
    static ScheduledFuture<?> startWatchdog(Runnable onTimeout, long minutes) {
        return WATCHDOG.schedule(onTimeout, minutes, TimeUnit.MINUTES);
    }
}
