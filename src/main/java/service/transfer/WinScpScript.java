package service.transfer;

import service.TransferPaths;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/** Renders a {@link TransferJob} as the script WinSCP.com runs. Only {@link WinScpEngine} needs this. */
final class WinScpScript {

    private WinScpScript() { }

    /** The script line for one command, with the path quoting WinSCP expects. */
    static String commandLine(TransferCommand c) {
        if (c instanceof TransferCommand.Download d) {
            return "get " + (d.deleteRemoteAfter() ? "-delete " : "")
                    + TransferPaths.escapeWinScpRemotePath(d.remotePath()) + " " + TransferPaths.escapeWinScpPath(d.localPath());
        }
        if (c instanceof TransferCommand.Upload u) {
            return "put " + TransferPaths.escapeWinScpPath(u.localPath()) + " " + TransferPaths.escapeWinScpRemotePath(u.remotePath());
        }
        if (c instanceof TransferCommand.DeleteRemote r) {
            return "rm " + TransferPaths.escapeWinScpRemotePath(r.remotePath());
        }
        if (c instanceof TransferCommand.MoveRemote m) {
            return "mv " + TransferPaths.escapeWinScpRemotePath(m.fromPath()) + " " + TransferPaths.escapeWinScpRemotePath(m.toPath());
        }
        if (c instanceof TransferCommand.MakeRemoteDir d) {
            return "mkdir " + TransferPaths.escapeWinScpRemotePath(d.remotePath());
        }
        throw new IllegalArgumentException("Unsupported command: " + c);
    }

    /**
     * Writes the job to a new owner-only temp file; the password goes to {@code scripts} for hand-off
     * through the process environment, never into the file. The caller deletes the file.
     */
    static File write(ScriptFiles scripts, TransferJob job) throws IOException {
        File script = File.createTempFile("opstool_job_", ".txt");
        ScriptFiles.secureTemp(script);
        try (PrintWriter pw = new PrintWriter(new FileWriter(script))) {
            pw.println("option batch " + (job.stopOnError() ? "abort" : "continue"));
            pw.println("option confirm off");
            scripts.writeOpenLine(pw, job.target(), job.password(), script);
            for (TransferCommand c : job.commands()) pw.println(commandLine(c));
            pw.println("close");
            pw.println("exit");
        } catch (IOException | RuntimeException e) {
            script.delete();
            throw e;
        }
        return script;
    }
}
