package service.transfer;

import model.Credential;

import java.util.List;

/**
 * Everything an engine needs for one session: which server, how to log in, and what to do there, in
 * order. This is the contract between the transfer planner and the engines; no engine-specific syntax
 * appears in it.
 *
 * @param stopOnError true: the first failing command ends the job as failed. false: a failing command
 *                    is logged and the job carries on (and counts as successful), e.g. for {@code mkdir}
 *                    of folders that may already exist
 */
public record TransferJob(Credential target, String password, List<TransferCommand> commands, boolean stopOnError) {

    public TransferJob {
        commands = List.copyOf(commands);
    }

    /** A job that stops at the first failure. */
    public static TransferJob strict(Credential target, String password, List<TransferCommand> commands) {
        return new TransferJob(target, password, commands, true);
    }

    /** A job that logs failures and carries on. */
    public static TransferJob lenient(Credential target, String password, List<TransferCommand> commands) {
        return new TransferJob(target, password, commands, false);
    }

    /** Never includes the password. */
    @Override
    public String toString() {
        return "TransferJob[" + (target == null ? "?" : target.getUsername() + "@" + target.getHost())
                + ", " + commands.size() + " command(s), stopOnError=" + stopOnError + "]";
    }
}
