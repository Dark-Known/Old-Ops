package service;

/**
 * Thrown when a watcher-driven run finds nothing to do, so the run is recorded as
 * skipped rather than failed. Shared by the transfer and mail code paths.
 */
public class WatcherSkipException extends Exception {
    public WatcherSkipException(String message) { super(message); }
}
