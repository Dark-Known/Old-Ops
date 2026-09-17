package service;

import model.TaskRunRecord;
import model.TaskRunRecord.FailureCategory;

import java.util.Locale;

/**
 * Turns a raw failure {@code reason}/{@code details} string into a
 * structured classification — see {@link model.TaskRunRecord}'s
 * {@code failureCategory}/{@code retryable}/{@code suggestedAction} fields.
 *
 * <p>This is deliberately a text-pattern classifier, not a rewrite of every
 * exception site in {@link TransferService}/mail/backup code to throw
 * typed exceptions — that would be the more "correct" long-term fix, but
 * every failure path already funnels into a plain string one way or another
 * (WinSCP's own stderr, JSch exception messages, IOExceptions), so
 * classifying centrally here gets structured categories for the whole app
 * without touching dozens of call sites. Run once, at the moment a FAILED
 * row is recorded (see {@link RunHistoryService#recordRun}) — not
 * re-derived on every view — so it's cheap and consistent.
 *
 * <p>Deliberately conservative: an unmatched failure gets
 * {@link FailureCategory#UNKNOWN} with {@code retryable = true} rather than
 * guessing wrong. A wrong "don't retry, fix your config" suggestion is worse
 * than no suggestion.
 */
public final class FailureClassifier {

    private FailureClassifier() {}

    /**
     * Classifies directly from the exception object where one is still in
     * hand, rather than from whatever text it eventually gets stringified
     * into — this is the reliable path {@link TransferService}'s outer
     * catch blocks use (see {@code TransferService#lastFailureClassification}),
     * as opposed to {@link #classify(String, String)} below, which is the
     * only option left once a failure is several layers removed and all
     * that's left is a log line (e.g. WinSCP's own stderr, which is just
     * text since it's an external process, not a Java exception).
     *
     * <p>Falls back to {@link #classify(String, String)} on the exception's
     * own message for any type not explicitly recognized here — this list
     * covers the exception types actually seen at TransferService's catch
     * sites, not an exhaustive taxonomy of every possible Throwable.
     */
    public static TaskRunRecord classifyThrowable(Throwable t) {
        if (t == null) return classify(null, null);

        if (t instanceof java.net.SocketTimeoutException) {
            return withAction(FailureCategory.TIMEOUT, true,
                    "Often transient on a slow link — retrying usually works; raise the timeout in Settings if it keeps happening.");
        }
        if (t instanceof java.net.ConnectException || t instanceof java.net.UnknownHostException
                || t instanceof java.net.NoRouteToHostException) {
            return withAction(FailureCategory.NETWORK, true,
                    "Check the target server is reachable and the port is open, then retry.");
        }
        if (t instanceof java.nio.file.AccessDeniedException) {
            return withAction(FailureCategory.PERMISSION, false,
                    "Check the account has read/write permission on this path (local or remote).");
        }
        if (t instanceof java.nio.file.NoSuchFileException || t instanceof java.io.FileNotFoundException) {
            return withAction(FailureCategory.CONFIG, false,
                    "Check the task's source/destination path — it doesn't exist.");
        }
        if (t.getClass().getName().equals("com.jcraft.jsch.JSchException")) {
            // JSch doesn't expose a distinct exception subtype per failure
            // reason (auth failure, unknown host, connection refused, etc.
            // are all plain JSchException with different message text), so
            // even with the exception object in hand this one specific type
            // still has to fall through to the text classifier below — but
            // at least we know for certain it's an SSH/SFTP-layer failure,
            // which narrows things down before the text match runs.
            return classify(t.getMessage(), null);
        }

        return classify(t.getMessage(), null);
    }

    private static TaskRunRecord withAction(FailureCategory category, boolean retryable, String action) {
        TaskRunRecord ctx = new TaskRunRecord();
        ctx.setFailureCategory(category);
        ctx.setRetryable(retryable);
        ctx.setSuggestedAction(action);
        return ctx;
    }

    public static TaskRunRecord classify(String reason, String details) {
        TaskRunRecord ctx = new TaskRunRecord();
        String haystack = ((reason != null ? reason : "") + " " + (details != null ? details : ""))
                .toLowerCase(Locale.ROOT);

        if (containsAny(haystack, "connection refused", "connection reset", "no route to host",
                "unreachable", "network is unreachable", "could not connect", "connect timed out")) {
            ctx.setFailureCategory(FailureCategory.NETWORK);
            ctx.setRetryable(true);
            ctx.setSuggestedAction("Check the target server is reachable and the port is open, then retry.");
        } else if (containsAny(haystack, "authentication failed", "auth fail", "permission denied (publickey",
                "unauthorized", "invalid credentials", "login incorrect", "bad password", "401")) {
            ctx.setFailureCategory(FailureCategory.AUTH);
            ctx.setRetryable(false);
            ctx.setSuggestedAction("Check the credential's username/password in Credentials — it's likely wrong or expired.");
        } else if (containsAny(haystack, "permission denied", "access is denied", "access denied", "eacces")) {
            ctx.setFailureCategory(FailureCategory.PERMISSION);
            ctx.setRetryable(false);
            ctx.setSuggestedAction("Check the account has read/write permission on this path (local or remote).");
        } else if (containsAny(haystack, "no space left", "disk full", "not enough space", "quota exceeded")) {
            ctx.setFailureCategory(FailureCategory.DISK_SPACE);
            ctx.setRetryable(false);
            ctx.setSuggestedAction("Free up space on the destination (or source, for local backups) and retry.");
        } else if (containsAny(haystack, "timed out", "timeout", "read timed out")) {
            ctx.setFailureCategory(FailureCategory.TIMEOUT);
            ctx.setRetryable(true);
            ctx.setSuggestedAction("Often transient on a slow link — retrying usually works; raise the timeout in Settings if it keeps happening.");
        } else if (containsAny(haystack, "detected stale running", "stale (orphaned", "cancelling and resetting")) {
            ctx.setFailureCategory(FailureCategory.ORPHANED);
            ctx.setRetryable(true);
            ctx.setSuggestedAction("The process running this task stopped unexpectedly mid-run (e.g. the Daemon was restarted) — it will run again automatically.");
        } else if (containsAny(haystack, "no such file", "not found", "cannot find the path",
                "does not exist", "invalid path", "malformed", "could not locate winscp")) {
            ctx.setFailureCategory(FailureCategory.CONFIG);
            ctx.setRetryable(false);
            ctx.setSuggestedAction("Check the task's source/destination path (or the WinSCP path in Settings) — something in the configuration doesn't exist.");
        } else {
            ctx.setFailureCategory(FailureCategory.UNKNOWN);
            ctx.setRetryable(true);
            ctx.setSuggestedAction(null);
        }
        return ctx;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }
}
