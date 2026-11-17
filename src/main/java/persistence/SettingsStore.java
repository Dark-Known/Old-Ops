package persistence;

import java.util.Map;

/**
 * Where the application's live settings (key/value strings) are kept. The store only keeps and returns
 * them; defaults, caching and typed access are {@code util.AppSettings}' job. Shared by the GUI and the
 * Daemon, so one process's {@link #replaceAll} must be visible to the other's next {@link #readAll}.
 */
public interface SettingsStore {

    /** Everything stored. Empty when nothing is stored yet or the store cannot be read. */
    Map<String, String> readAll();

    /**
     * Replaces everything stored with {@code values}, atomically.
     *
     * @return false when the store is not available at all (nothing was written); a failure in the middle
     *         of a write is thrown as a {@link RuntimeException}
     */
    boolean replaceAll(Map<String, String> values);

    /** Whether the store is open and usable right now (checked live, not just "it opened once"). */
    boolean isConnected();

    /** A short reason the store could not be opened, or null when it is fine. */
    String connectionError();

    /** Where the settings live, for display in the Settings panel (a file path, a connection name...). */
    String location();
}
