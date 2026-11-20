package service.watch;

import service.watch.RemoteListenerRegistry.Proc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Keeps track of the processes a remote Windows listener runs as, and ends the ones left behind by an earlier
 * connection when the listener is armed again.
 *
 * <p>The listener never ends another process itself. Once armed it reports its own processes
 * ({@value #PROC_PREFIX}); the service records them in the {@link RemoteListenerRegistry}. Before the next listener
 * is armed for the same folder, {@link #endPrevious} sends one separate command naming exactly those processes
 * (pid and start time). The server ends a process only if it still has that pid and start time and is PowerShell or
 * cmd.exe. A listener of another installation or task was never recorded here, so it is never touched.
 */
final class RemoteProcessJanitor {

    /**
     * First characters of the line a listener prints once it is armed.
     */
    static final String PROC_PREFIX = "__PROC__";
    static final String GONE = "__GONE__";
    static final String KILLED = "__KILLED__";
    static final String KEPT = "__KEPT__";
    static final String FAILED = "__FAILED__";

    private static final Pattern SHELL_NAME = Pattern.compile("(?i)cmd|powershell|pwsh");
    private static final long MAX_PID = Integer.MAX_VALUE;   // the server script reads the pid as a 32-bit int

    private RemoteProcessJanitor() {
    }

    /**
     * Runs a command on the server and returns the lines it printed.
     */
    interface Remote {
        List<String> run(String command, long timeoutMs) throws Exception;
    }

    /**
     * What {@link #endPrevious} found.
     */
    static final class Result {
        final int gone, killed, kept, failed;
        final boolean unreachable;

        Result(int gone, int killed, int kept, int failed, boolean unreachable) {
            this.gone = gone;
            this.killed = killed;
            this.kept = kept;
            this.failed = failed;
            this.unreachable = unreachable;
        }

        static final Result NOTHING = new Result(0, 0, 0, 0, false);
    }

    /**
     * Reads a listener's report line: "{@value #PROC_PREFIX}pid=..;start=..;ppid=..;pname=..;pstart=..;sshd=..".
     * Returns the processes that may later be ended: the listener itself and, if it is a shell, the process it runs
     * under. sshd is never returned. A malformed or incomplete line gives an empty list.
     */
    static List<Proc> parseReport(String line, String key, String host, String user, long now) {
        List<Proc> out = new ArrayList<>();
        if (line == null || !line.startsWith(PROC_PREFIX)) return out;
        Map<String, String> f = new HashMap<>();
        for (String part : line.substring(PROC_PREFIX.length()).split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) f.put(part.substring(0, eq).trim().toLowerCase(Locale.ROOT), part.substring(eq + 1).trim());
        }
        long pid = number(f.get("pid")), start = number(f.get("start"));
        if (pid <= 0 || pid > MAX_PID || start <= 0) return out;
        out.add(new Proc(key, host, user, "listener", pid, start, now));
        long ppid = number(f.get("ppid")), pstart = number(f.get("pstart"));
        String pname = f.getOrDefault("pname", "");
        if (ppid > 0 && ppid <= MAX_PID && pstart > 0 && SHELL_NAME.matcher(pname).matches() && ppid != pid) {
            out.add(new Proc(key, host, user, "shell", ppid, pstart, now));
        }
        return out;
    }

    private static long number(String s) {
        if (s == null || !s.matches("\\d{1,18}")) return -1;
        return Long.parseLong(s);
    }

    /**
     * Ends the processes recorded for {@code key} that are still running, and forgets the ones that are dealt with.
     * A recorded process that cannot be checked right now (the command failed or timed out, or the server could not end
     * it) stays recorded and is tried again next time. Never throws.
     */
    static Result endPrevious(RemoteListenerRegistry registry, String key, Remote remote, Consumer<String> log, long timeoutMs) {
        List<Proc> recorded = registry.get(key);
        if (recorded.isEmpty()) return Result.NOTHING;

        // One live process has one pid: if the same pid was recorded twice, the older record is certainly dead.
        Map<Long, Proc> newestByPid = new LinkedHashMap<>();
        List<Proc> superseded = new ArrayList<>();
        for (Proc p : recorded) {
            Proc other = newestByPid.get(p.pid);
            if (other == null) {
                newestByPid.put(p.pid, p);
            } else if (p.recordedAt >= other.recordedAt) {
                superseded.add(other);
                newestByPid.put(p.pid, p);
            } else {
                superseded.add(p);
            }
        }
        if (!superseded.isEmpty()) registry.remove(key, superseded);
        List<Proc> targets = new ArrayList<>(newestByPid.values());

        log.accept("Looking for processes of an earlier listener on this folder: "
                + targets.stream().map(Proc::toString).collect(Collectors.joining(", ")));
        List<String> lines;
        try {
            String command = ListenerScripts.windowsEndPrevious(
                    targets.stream().map(p -> p.pid + ":" + p.start).collect(Collectors.joining(",")),
                    GONE, KILLED, KEPT, FAILED);
            lines = remote.run(command, timeoutMs);
        } catch (Exception e) {
            log.accept("Could not check for them (" + e.getMessage() + "); they stay recorded and are checked again next time.");
            return new Result(0, 0, 0, 0, true);
        }

        Map<Long, String> outcome = new HashMap<>();
        for (String raw : lines) {
            String line = raw.trim();
            for (String tag : new String[]{GONE, KILLED, KEPT, FAILED}) {
                if (line.startsWith(tag)) outcome.put(number(line.substring(tag.length())), tag);
            }
        }
        int gone = 0, killed = 0, kept = 0, failed = 0;
        List<Proc> resolved = new ArrayList<>();
        for (Proc p : targets) {
            String o = outcome.get(p.pid);
            if (GONE.equals(o)) {
                gone++;
                resolved.add(p);
                log.accept("  " + p + ": already gone");
            } else if (KILLED.equals(o)) {
                killed++;
                resolved.add(p);
                log.accept("  " + p + ": was still running, ended");
            } else if (KEPT.equals(o)) {
                kept++;
                resolved.add(p);
                log.accept("  " + p + ": the pid now belongs to a different process, left alone");
            } else {
                failed++;
                log.accept("  " + p + (FAILED.equals(o) ? ": could not be ended" : ": no answer from the server") + "; will try again next time");
            }
        }
        registry.remove(key, resolved);
        return new Result(gone, killed, kept, failed, false);
    }
}
