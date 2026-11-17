package service.transfer;

import model.Credential;

import java.util.Collection;
import java.util.function.Consumer;

/**
 * Makes sure remote folders exist, in one session, carrying on when one fails (typically because it
 * already exists).
 */
public final class RemoteDirs {

    private final TransferEngine engine;

    public RemoteDirs(TransferEngine engine) {
        this.engine = engine;
    }

    /** Returns true when there was nothing to do or the session reported success. */
    public boolean ensure(Credential target, Collection<String> remotePaths, Consumer<String> logLine) throws Exception {
        if (remotePaths.isEmpty()) return true;
        java.util.List<TransferCommand> cmds = new java.util.ArrayList<>();
        for (String p : remotePaths) cmds.add(new TransferCommand.MakeRemoteDir(p));
        return engine.run(TransferJob.lenient(target, target.getPassword(), cmds), logLine, "backup-mkdir");
    }
}
