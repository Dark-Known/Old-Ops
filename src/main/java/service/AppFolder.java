package service;

import persistence.AppStorage;
import persistence.ChangeSignals;
import persistence.Changes;
import persistence.CommandQueue;

import java.io.File;
import java.nio.file.Path;

/**
 * The application's data folder as the screens need it: where the daemon publishes its status, how to
 * send it a command, and the cross-process change signals. Screens ask this object instead of building
 * file names from the folder themselves, so the layout of the folder is known in one place.
 */
public final class AppFolder {

    /** The file the daemon writes its scheduler status to. */
    public static final String DAEMON_STATUS_FILE = "scheduler-status-daemon.dat";

    private final File dir;

    private AppFolder(File dir) {
        this.dir = dir;
    }

    public static AppFolder of(AppStorage storage) {
        return new AppFolder(storage.getDataDir());
    }

    public static AppFolder at(File dataDir) {
        return new AppFolder(dataDir);
    }

    public Path daemonStatusFile() {
        return dir.toPath().resolve(DAEMON_STATUS_FILE);
    }

    /**
     * Sends a command from the GUI to the daemon: straight to its control endpoint, or queued for its next
     * poll when it cannot be reached.
     *
     * @return whether the command was delivered or stored
     */
    public boolean sendCommand(String taskId, CommandQueue.Action action) {
        return CommandQueueService.enqueue(dir, taskId, action, "gui");
    }

    /** Live updates of the daemon's status file. */
    public DaemonStatusStream daemonStatusStream() {
        return DaemonStatusStream.forDataDir(dir);
    }

    /** "Something changed" signals other processes raise (run history, tasks, ...). */
    public ChangeSignals changes() {
        return Changes.forDataDir(dir);
    }

    /** The folder itself, for the few features that still read or write files in it directly. */
    public File dir() {
        return dir;
    }
}
