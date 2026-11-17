package service;

import persistence.CommandQueue;
import persistence.CommandQueues;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * How the GUI asks the daemon (the sole scheduler and worker-pool owner) to act, instead of executing
 * the work in its own process: "run now", cancel, restart, reconnect a watch, purge.
 *
 * <p>The command goes straight to the running daemon over its control endpoint when that is reachable.
 * Otherwise it is left in the {@link CommandQueue} for the daemon's poller to pick up, so nothing depends
 * on the endpoint being up. Failures are logged, not thrown: a queueing hiccup should not crash a button click.
 */
public final class CommandQueueService {

    private static final Logger log = Logger.getLogger(CommandQueueService.class.getName());

    private CommandQueueService() {}

    /** Returns whether the command was delivered (directly) or stored (for the daemon's next poll). */
    public static boolean enqueue(File dataDir, String taskId, CommandQueue.Action action, String requestedBy) {
        DaemonCallLog.configure(dataDir);
        if (DaemonControlClient.send(dataDir, action, taskId, requestedBy)) return true;
        try {
            CommandQueues.forDataDir(dataDir).enqueue(taskId, action, requestedBy);
            DaemonCallLog.record("queue", "ENQUEUE", "gui_commands", 202, 0, action.name(), taskId, requestedBy,
                    "Control endpoint not reachable; queued in the database for the service to pick up");
            return true;
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to enqueue command " + action + " for task " + taskId, e);
            DaemonCallLog.record("queue", "ENQUEUE", "gui_commands", 0, 0, action.name(), taskId, requestedBy, String.valueOf(e.getMessage()));
            return false;
        }
    }
}
