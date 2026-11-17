package util;

/** Lets tests in other packages clear AppSettings' cache without widening its public API. */
public final class AppSettingsTestHooks {
    private AppSettingsTestHooks() { }
    public static void reset() { AppSettings.resetForTests(); }
}
