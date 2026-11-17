package service;

import model.ScheduledTask;
import model.ScheduledTask.TaskStatus;
import model.ScheduledTask.TaskType;

import java.util.List;

/** The wording and the counts the task list shows for each row and for the summary cards. No Swing. */
public final class TaskListSummary {

    private TaskListSummary() { }

    /**
     * Type, direction and mode on one line: "File Transfer" tasks read "FILE TRANSFER — INBOUND (LATEST ONLY)",
     * other types just "OUTLOOK MAIL" / "BACKUP".
     */
    public static String typeLabel(ScheduledTask t) {
        String type = t.getTaskType().name().replace("_", " ");
        if (t.getTaskType() == TaskType.FILE_TRANSFER) {
            String direction = t.getTransferDirection().name();
            String mode = t.getTransferMode() != null
                    ? " (" + t.getTransferMode().name().replace("_", " ") + ")" : "";
            type += " — " + direction + mode;
        }
        return type;
    }

    /** " · FAILED" after the last-run time; empty when there is no result; SKIPPED reads "Skipped". */
    public static String resultSuffix(String lastResult) {
        if (lastResult == null || lastResult.isEmpty()) return "";
        return " · " + ("SKIPPED".equals(lastResult) ? "Skipped" : lastResult);
    }

    /** What the summary cards show. */
    public static final class Counts {
        public final int total, running, failed;
        /** "—" when nothing has run yet, otherwise a whole percent such as "86%". */
        public final String successRate;
        Counts(int total, int running, int failed, String successRate) {
            this.total = total; this.running = running; this.failed = failed; this.successRate = successRate;
        }
    }

    /** Success counts runs that ended SUCCESS or SKIPPED, out of every task that has run at least once. */
    public static Counts count(List<ScheduledTask> tasks) {
        int running = 0, failed = 0, ran = 0, ok = 0;
        for (ScheduledTask t : tasks) {
            String last = t.getLastRunResult() != null ? t.getLastRunResult() : "";
            if (t.getStatus() == TaskStatus.RUNNING) running++;
            if ("FAILED".equals(last)) failed++;
            if (t.getLastRunAt() != null) {
                ran++;
                if ("SUCCESS".equals(last) || "SKIPPED".equals(last)) ok++;
            }
        }
        String rate = ran == 0 ? "—" : Math.round(100.0 * ok / ran) + "%";
        return new Counts(tasks.size(), running, failed, rate);
    }
}
