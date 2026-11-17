package ui;

import service.TaskSchedulerService;
import service.queue.SchedulerStatusSnapshot;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The text of the "workers" tooltip, shared by the status strip and the Event Monitor's summary bar.
 * Built fresh each time it is shown: which task each worker thread is running (name, thread, how long),
 * then one line per other worker lane and the background jobs running on them. When the background
 * service is the active scheduler it reads what that process exports; otherwise this window's own pool.
 */
final class WorkersTooltip {
    private WorkersTooltip() {}

    static String build(TaskSchedulerService scheduler, AppFeeds.Daemon daemon, Function<String, String> taskName) {
        boolean viaService = !scheduler.isStarted() && daemon != null && daemon.alive() && daemon.snapshot() != null;
        StringBuilder sb = new StringBuilder("<html><b>Task workers</b> \u2014 ");
        List<String> taskLines = new ArrayList<>();
        List<String> other = new ArrayList<>();
        if (viaService) {
            SchedulerStatusSnapshot snap = daemon.snapshot();
            sb.append("background service: ").append(snap.getActiveWorkers()).append(" busy of ").append(snap.getPoolSize());
            for (var t : snap.getInFlight())
                taskLines.add(t.thread() + " \u00b7 " + taskName.apply(t.taskId()) + " \u00b7 " + ago(t.startedAt()));
            if (taskLines.isEmpty())  // an older service that doesn't export per-worker detail
                for (String id : snap.getRunningTaskIds()) taskLines.add(taskName.apply(id));
            other.addAll(snap.getLiveLines());
        } else {
            sb.append("this window: ").append(scheduler.getActiveWorkerCount()).append(" busy of ").append(scheduler.getWorkerPoolSize());
            for (var t : scheduler.getInFlightTasks())
                taskLines.add(t.thread() + " \u00b7 " + taskName.apply(t.taskId()) + " \u00b7 " + ago(t.startedAt()));
            other.addAll(service.Workers.summaryLines());
            other.addAll(service.BackgroundJobs.liveLines(8));
        }
        if (taskLines.isEmpty()) sb.append("<br><i>No task is running.</i>");
        for (String l : taskLines) sb.append("<br>\u2022 ").append(esc(l));
        sb.append("<br><br><b>Other work</b> \u2014 ").append(viaService ? "background service" : "this window");
        if (other.isEmpty()) sb.append("<br><i>Idle.</i>");
        for (String l : other) sb.append("<br>").append(l.matches(".*: \\d+ busy.*") ? "" : "\u2022 ").append(esc(l));
        if (viaService) {  // this window's own lanes are separate from the service's
            List<String> mine = new ArrayList<>(service.Workers.summaryLines());
            mine.addAll(service.BackgroundJobs.liveLines(6));
            sb.append("<br><br><b>Other work</b> \u2014 this window");
            for (String l : mine) sb.append("<br>").append(l.matches(".*: \\d+ busy.*") ? "" : "\u2022 ").append(esc(l));
        }
        return sb.append("</html>").toString();
    }

    private static String esc(String t) { return t.replace("&", "&amp;").replace("<", "&lt;"); }

    private static String ago(LocalDateTime since) {
        long s = Math.max(0, Duration.between(since, LocalDateTime.now()).getSeconds());
        return s >= 120 ? (s / 60) + " min" : s + " s";
    }
}
