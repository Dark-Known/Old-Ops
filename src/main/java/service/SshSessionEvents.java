package service;

import java.util.function.Consumer;

/**
 * Process-wide hook through which every component that opens an SSH session on behalf of a task
 * reports "opened", "closed" and "failed" to the Event Monitor. Components call the static
 * methods; {@link TaskSchedulerService} installs the sink that turns them into feed events.
 * Never throws — reporting must not affect the connection it describes.
 */
public final class SshSessionEvents {

    public enum Phase { OPENED, CLOSED, FAILED }

    /** {@code detached}: the session outlives any single run (e.g. the remote watcher), so the event
     *  must not be attached to whatever run of the task happens to be in flight. */
    public record Event(Phase phase, String taskId, String purpose, String host, String user,
                        String reason, long openMs, boolean detached) {}

    private static volatile Consumer<Event> sink;

    private SshSessionEvents() {}

    public static void setSink(Consumer<Event> s) { sink = s; }

    /** Removes {@code s} only if it is still the installed sink (a newer scheduler may own it now). */
    public static void clearSink(Consumer<Event> s) { if (sink == s) sink = null; }

    public static void opened(String taskId, String purpose, String host, String user) {
        emit(new Event(Phase.OPENED, taskId, purpose, host, user, null, 0, false));
    }

    public static void closed(String taskId, String purpose, String host, String user,
                              String reason, long openMs) {
        emit(new Event(Phase.CLOSED, taskId, purpose, host, user, reason, openMs, false));
    }

    public static void failed(String taskId, String purpose, String host, String user, String reason) {
        emit(new Event(Phase.FAILED, taskId, purpose, host, user, reason, 0, false));
    }

    /** Same as the task variants, for sessions that live longer than one run. */
    public static void openedDetached(String taskId, String purpose, String host, String user) {
        emit(new Event(Phase.OPENED, taskId, purpose, host, user, null, 0, true));
    }

    public static void closedDetached(String taskId, String purpose, String host, String user,
                                      String reason, long openMs) {
        emit(new Event(Phase.CLOSED, taskId, purpose, host, user, reason, openMs, true));
    }

    public static void failedDetached(String taskId, String purpose, String host, String user, String reason) {
        emit(new Event(Phase.FAILED, taskId, purpose, host, user, reason, 0, true));
    }

    // ── Sessions opened from the UI (folder browser, Test connection): not tied to a task ──

    public static void uiOpened(String purpose, String host, String user) {
        BackgroundJobs.note(model.EventKind.SSH_OPENED, model.TaskRunRecord.Status.SUCCESS, purpose,
                "SSH session opened to " + who(host, user) + " (" + purpose + ")", null);
    }

    public static void uiClosed(String purpose, String host, String user, long openMs) {
        BackgroundJobs.note(model.EventKind.SSH_CLOSED, model.TaskRunRecord.Status.SUCCESS, purpose,
                "SSH session closed to " + who(host, user) + " (" + purpose + ")"
                        + (openMs > 0 ? " after " + duration(openMs) : ""), null);
    }

    public static void uiFailed(String purpose, String host, String user, String reason) {
        BackgroundJobs.note(model.EventKind.SSH_FAILED, model.TaskRunRecord.Status.FAILED, purpose,
                "SSH connect to " + who(host, user) + " failed (" + purpose + ")"
                        + (reason != null ? ": " + reason : ""), null);
    }

    /** [user, host] from the {@code open sftp://user:pass@host/} line of a WinSCP script (never the password). */
    public static String[] targetOfWinScpScript(java.io.File script) {
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(script))) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (!t.startsWith("open sftp://")) continue;
                String rest = t.substring("open sftp://".length());
                int slash = rest.indexOf('/');
                if (slash >= 0) rest = rest.substring(0, slash);
                int at = rest.lastIndexOf('@');
                String host = at >= 0 ? rest.substring(at + 1) : rest;
                String cred = at >= 0 ? rest.substring(0, at) : "";
                int colon = cred.indexOf(':');
                String user = colon >= 0 ? cred.substring(0, colon) : cred;
                try { user = java.net.URLDecoder.decode(user, "UTF-8"); } catch (Exception ignored) { }
                return new String[]{user, host};
            }
        } catch (Exception ignored) { }
        return new String[]{null, null};
    }

    public static String who(String host, String user) {
        return (user != null && !user.isBlank() ? user + "@" : "") + (host != null ? host : "?");
    }

    public static String duration(long ms) {
        long s = ms / 1000;
        if (ms < 1000) return ms + " ms";
        return s < 60 ? s + " s" : s < 3600 ? (s / 60) + " min " + (s % 60) + " s"
                : (s / 3600) + " h " + ((s % 3600) / 60) + " min";
    }

    private static void emit(Event e) {
        Consumer<Event> s = sink;
        if (s == null) return;
        try { s.accept(e); } catch (Throwable ignored) {}
    }
}
