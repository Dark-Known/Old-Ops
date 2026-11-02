package service;

import model.EventKind;
import model.TaskRunRecord;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns task outcomes and Event Monitor events into Teams cards. Runs inside the scheduler process: it listens to the
 * run history, decides from {@link AlertConfig} whether the event is worth an alert for that task, holds back repeats,
 * writes the offline HTML report, posts the card to the configured Teams workflow, and records the delivery in the
 * Event Monitor ("Alert sent" / "Alert not delivered") and in {@code alerts-activity.log}.
 *
 * <p>Sending happens on one background thread, so a slow or unreachable webhook never delays a task. Nothing here
 * throws into the scheduler.
 */
public final class AlertDispatcher implements AutoCloseable {

    private static final Logger log = Logger.getLogger(AlertDispatcher.class.getName());
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    static final int MAX_ATTEMPTS = 3;

    /** The outcome of one HTTP post. */
    public record Result(int status, String body, String error, long millis) {
        public boolean ok() { return status >= 200 && status < 300; }
    }

    /** Posts a JSON card to a webhook; replaceable in tests. */
    public interface Poster { Result post(String url, String json); }

    private final File dataDir;
    private final RunHistoryService history;
    private final TaskLogService logs;
    private final LongSupplier clock;
    private final Poster poster;
    private final long retryPauseMs;
    /** Worker threads that build and deliver alerts; the queue in front of them is bounded. */
    static final int WORKERS = 2, QUEUE_LIMIT = 200;
    private final java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger workerNo = new java.util.concurrent.atomic.AtomicInteger();
    private final ExecutorService sender = new java.util.concurrent.ThreadPoolExecutor(WORKERS, WORKERS, 30, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(QUEUE_LIMIT), r -> {
        Thread t = new Thread(r, "alert-worker-" + workerNo.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    private final AlertQueueStore store;
    /** Tests turn this off so no port is opened. */
    boolean serveReports = false;
    private volatile AlertReportServer reportServer;
    private String reportServerKey = "";
    /** Alerts older than this when the service starts again are dropped instead of sent. */
    static final long MAX_AGE_MS = 24L * 3600_000L;
    private final AlertThrottle throttle = new AlertThrottle();
    private final Map<String, Integer> held = new HashMap<>();
    private final Map<String, Long> openProblems = new HashMap<>();
    private final String host;

    private AlertConfig config = AlertConfig.empty();
    private long configStamp = Long.MIN_VALUE;
    private long lastPrune;
    private java.util.function.Consumer<TaskRunRecord> runListener, eventListener;

    public AlertDispatcher(File dataDir, RunHistoryService history, TaskLogService logs) {
        this(dataDir, history, logs, System::currentTimeMillis, new HttpPoster(), 2000);
        this.serveReports = true;
    }

    AlertDispatcher(File dataDir, RunHistoryService history, TaskLogService logs, LongSupplier clock, Poster poster, long retryPauseMs) {
        this.dataDir = dataDir;
        this.store = new AlertQueueStore(dataDir);
        this.history = history;
        this.logs = logs;
        this.clock = clock;
        this.poster = poster;
        this.retryPauseMs = retryPauseMs;
        String h;
        try { h = InetAddress.getLocalHost().getHostName(); } catch (Exception e) { h = "this-pc"; }
        this.host = h;
    }

    /** Starts listening to runs and events. */
    public void start() {
        runListener = this::onRecord;
        eventListener = this::onRecord;
        history.addRunListener(runListener);
        history.addActivityListener(eventListener);
        ensureReportServer(currentConfig());
        restoreQueued();
    }

    /** Alerts that were waiting when the service last stopped are queued again, in their original order. */
    void restoreQueued() {
        for (AlertQueueStore.Job j : store.load()) {
            if (clock.getAsLong() - j.queuedAt() > MAX_AGE_MS) {
                store.delete(j.file());
                event(EventKind.ALERT_FAILED, j.alert(), TaskRunRecord.Status.FAILED, "Alert expired: " + j.alert().title(),
                        "It was still waiting when the service restarted, more than 24 hours after it was raised, so it was not sent.");
                continue;
            }
            submit(j.file(), j.alert(), currentConfig(), "Alert queued again after a restart: ");
        }
    }

    @Override public void close() {
        if (runListener != null) history.removeRunListener(runListener);
        if (eventListener != null) history.removeActivityListener(eventListener);
        sender.shutdown();
        stopReportServer();
    }

    /** Starts, restarts or stops the report web server to match the settings. Returns the running server or null. */
    synchronized AlertReportServer ensureReportServer(AlertConfig cfg) {
        String key = cfg.reportServer && cfg.enabled ? cfg.reportPort + "|" + cfg.reportHost : "";
        if (key.equals(reportServerKey) && (key.isEmpty() || reportServer != null)) return reportServer;
        stopReportServer();
        reportServerKey = key;
        if (key.isEmpty() || !serveReports) return null;
        try {
            java.util.List<String> names = new ArrayList<>();
            if (!cfg.reportHost.isBlank()) names.add(cfg.reportHost);
            names.add(host);
            reportServer = AlertReportServer.start(dataDir, "0.0.0.0", cfg.reportPort, () -> reportDir(currentConfig()), names, true, this::reportRequest);
        } catch (Exception e) {
            reportServer = null;
            log.warning("Alert report server not started on port " + cfg.reportPort + " (" + e.getMessage() + ").");
        }
        return reportServer;
    }

    /** The report went out with a delivered alert: say so, with its name, size, whether it travelled in the request, and its link. */
    void reportSent(TeamsCard.Alert alert, String reportPath, String reportUrl, boolean attached, String webhookHost) {
        if (reportPath == null || reportPath.isEmpty()) return;
        long size = -1;
        try { size = Files.size(Path.of(reportPath)); } catch (Exception ignored) { }
        String name = Path.of(reportPath).getFileName().toString();
        String link = reportUrl == null ? "" : reportUrl.replaceAll("([?&]k=)[0-9a-f]+", "$1...");
        event(EventKind.REPORT_SENT, alert, TaskRunRecord.Status.SUCCESS, "Report sent with the alert: " + name,
                "File: " + name + (size >= 0 ? " (" + size + " bytes)" : "") + "\n"
                + (attached ? "Attached to the webhook request to " + webhookHost + " (report.contentBase64) for the workflow to save.\n"
                            : "Not attached to the request (larger than " + (MAX_ATTACHED_BYTES / 1024 / 1024) + " MB); reachable through the link only.\n")
                + "Open logs button: " + (link.isEmpty() ? "(none)" : link) + "\nSaved at: " + reportPath);
    }

    /** A browser asked the report server for something: record it in the Event Monitor. */
    void reportRequest(String remote, String name, boolean download, int status) {
        if (history == null) return;
        EventKind kind = status == 200 ? (download ? EventKind.REPORT_DOWNLOADED : EventKind.REPORT_VIEWED) : EventKind.REPORT_REJECTED;
        String what = name.isEmpty() ? "(no file)" : name;
        String msg = status == 200 ? (download ? "Report downloaded: " : "Report opened: ") + what
                : "Report request refused (" + (status == 403 ? "invalid link" : status == 404 ? "not found" : "HTTP " + status) + "): " + what;
        LocalDateTime now = LocalDateTime.now();
        try {
            history.recordEvent(kind, "", "Alert reports", "ALERT_REPORT", status == 200 ? TaskRunRecord.Status.SUCCESS : TaskRunRecord.Status.FAILED,
                    msg, "From " + remote + "\nGET " + AlertReportServer.PATH + what + (download ? "?download=1" : "") + "\nAnswer: HTTP " + status
                    + "\nThe key in the link is never logged.", now, now, null, null);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Report request not recorded", e);
        }
    }

    private synchronized void stopReportServer() {
        AlertReportServer s = reportServer;
        reportServer = null;
        reportServerKey = "";
        if (s != null) s.close();
    }

    /** Where "Open logs" points for this report. */
    String linkFor(AlertConfig cfg, Path file) {
        if (cfg.reportBaseUrl.isBlank() && cfg.reportServer) {
            String name = file.getFileName().toString();
            String shown = cfg.reportHost.isBlank() ? host : cfg.reportHost;
            AlertReportServer s = ensureReportServer(cfg);
            if (s != null) return s.urlFor(shown, name);
            if (!serveReports) {
                String u = AlertReportServer.linkWithoutServer(dataDir, shown, cfg.reportPort, name);
                if (u != null) return u;
            }
        }
        return reportUrlFor(cfg, file);
    }

    /** Test hook and listener entry point: decides and, if needed, queues the delivery. */
    public void onRecord(TaskRunRecord rec) {
        try {
            EventKind kind = kindOf(rec);
            if (kind == null) return;
            AlertConfig cfg = currentConfig();
            String taskId = rec.getTaskId();
            boolean recovery = false;
            if (kind == EventKind.RUN_SUCCEEDED) {
                Long since;
                synchronized (this) { since = taskId == null ? null : openProblems.remove(taskId); }
                if (since == null || !cfg.ready() || !cfg.recoveryCards || cfg.ruleFor(taskId).mode() == AlertConfig.Mode.OFF) return;
                recovery = true;
            } else if (AlertConfig.RECOVERIES.contains(kind)) {
                if (!cfg.wants(taskId, kind)) return;
                recovery = true;
                synchronized (this) { if (taskId != null) openProblems.remove(taskId); }
            } else if (!cfg.wants(taskId, kind)) {
                return;
            }

            long now = clock.getAsLong();
            int suppressed = 0;
            if (!recovery) {
                String key = (taskId == null ? "-" : taskId) + "|" + kind;
                boolean raise = throttle.tryRaise(key, cfg.throttleMinutes * 60_000L, now);
                synchronized (this) {
                    if (!raise) { held.merge(key, 1, Integer::sum); return; }
                    Integer n = held.remove(key);
                    suppressed = n == null ? 0 : n;
                    if (taskId != null && AlertConfig.PROBLEMS.contains(kind)) openProblems.put(taskId, now);   // only real problems get a "recovered" card
                }
            }
            TeamsCard.Alert alert = alertFor(rec, kind, recovery, suppressed, now);
            enqueue(cfg, alert, rec);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Alert could not be queued", e);
        }
    }

    static EventKind kindOf(TaskRunRecord rec) {
        String k = rec.getEventKind();
        if (k != null && !k.isBlank()) {
            try { return EventKind.valueOf(k); } catch (IllegalArgumentException ignored) { }
        }
        if (rec.getStatus() == TaskRunRecord.Status.FAILED) return EventKind.RUN_FAILED;
        if (rec.getStatus() == TaskRunRecord.Status.SUCCESS) return EventKind.RUN_SUCCEEDED;
        return null;
    }

    private synchronized AlertConfig currentConfig() {
        File f = new File(dataDir, AlertConfig.FILE_NAME);
        long stamp = f.isFile() ? f.lastModified() * 31 + f.length() : -1;
        if (stamp != configStamp) { configStamp = stamp; config = AlertConfig.load(dataDir); ensureReportServer(config); }
        return config;
    }

    private TeamsCard.Alert alertFor(TaskRunRecord r, EventKind kind, boolean recovery, int suppressed, long now) {
        return new TeamsCard.Alert(kind, recovery, r.getTaskId(), r.getTaskName(), r.getTaskType(),
                r.getStatus() == null ? "" : r.getStatus().name(), r.getReason(), r.getDetails(), r.getSuggestedAction(),
                r.getStartedAt(), r.getEndedAt(), r.getDurationMs(), r.getTrigger(), r.getRunKey(), host, null, null, suppressed,
                LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), java.time.ZoneId.systemDefault()));
    }

    /** Saves the alert (so a restart does not lose it), queues it for the worker threads and says so in the Event Monitor. */
    private void enqueue(AlertConfig cfg, TeamsCard.Alert alert, TaskRunRecord rec) {
        File f = null;
        try { f = store.save(alert, clock.getAsLong()); } catch (IOException e) { log.log(Level.WARNING, "Alert not saved to the queue folder", e); }
        submit(f, alert, cfg, "Alert queued: ");
    }

    private void submit(File file, TeamsCard.Alert alert, AlertConfig cfg, String prefix) {
        int depth = pending.incrementAndGet();
        try {
            sender.execute(() -> {
                try { deliverQueued(file, alert); } finally { pending.decrementAndGet(); }
            });
            event(EventKind.ALERT_QUEUED, alert, TaskRunRecord.Status.SUCCESS, prefix + alert.title(),
                    "Waiting for a worker thread (" + depth + " in the queue). Destination: " + cfg.webhookHost() + ".");
        } catch (java.util.concurrent.RejectedExecutionException e) {
            pending.decrementAndGet();
            if (!sender.isShutdown()) store.delete(file);
            event(EventKind.ALERT_FAILED, alert, TaskRunRecord.Status.FAILED, "Alert dropped, queue full: " + alert.title(),
                    "More than " + QUEUE_LIMIT + " alerts are waiting; this one was not queued.");
        }
    }

    /** Runs on a worker thread: uses the settings as they are now, and removes the saved copy once the alert is done with. */
    private void deliverQueued(File file, TeamsCard.Alert alert) {
        AlertConfig cfg = currentConfig();
        try {
            if (!cfg.ready()) {
                event(EventKind.ALERT_FAILED, alert, TaskRunRecord.Status.FAILED, "Alert not sent, alerts are off: " + alert.title(),
                        "Teams alerts were turned off or the webhook address removed before this alert could be sent.");
            } else {
                deliver(cfg, alert, null);
            }
        } finally {
            store.delete(file);
        }
    }

    private void event(EventKind kind, TeamsCard.Alert a, TaskRunRecord.Status status, String message, String details) {
        if (history == null) return;
        LocalDateTime now = LocalDateTime.now();
        try {
            history.recordEvent(kind, a.taskId(), a.taskName(), a.taskType(), status, message, details, now, now, a.runKey(), a.trigger());
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Alert event not recorded", e);
        }
    }

    // ── Delivery (on a worker thread) ────────────────────────────────────

    private void deliver(AlertConfig cfg, TeamsCard.Alert base, TaskRunRecord rec) {
        String taskId = base.taskId() == null ? "" : base.taskId();
        String reportPath = "", reportUrl = null;
        try {
            Path p = writeReport(cfg, base, rec);
            reportPath = p.toString();
            reportUrl = linkFor(cfg, p);
        } catch (Exception e) {
            log.log(Level.WARNING, "Alert report not written", e);
        }
        TeamsCard.Alert alert = new TeamsCard.Alert(base.kind(), base.recovery(), base.taskId(), base.taskName(), base.taskType(),
                base.status(), base.message(), base.details(), base.suggestedAction(), base.startedAt(), base.endedAt(),
                base.durationMs(), base.trigger(), base.runKey(), base.host(), reportUrl, reportPath, base.suppressed(), base.raisedAt());
        String json = TeamsCard.build(alert);
        String body = withReport(json, reportPath);
        Result res = postWithRetry(cfg.webhookUrl, body, json, alert, reportPath);

        LocalDateTime now = LocalDateTime.now();
        try {
            if (res.ok()) {
                history.recordEvent(EventKind.ALERT_SENT, base.taskId(), base.taskName(), base.taskType(), TaskRunRecord.Status.SUCCESS,
                        "Alert sent to Teams: " + alert.title(), "POST " + cfg.webhookHost() + "\nResponse: HTTP " + res.status() + " " + clipText(res.body()) + "\n"
                        + (reportPath.isEmpty() ? "" : "Report: " + reportPath), now, now, base.runKey(), base.trigger());
                reportSent(alert, reportPath, reportUrl, body.length() > json.length(), cfg.webhookHost());
            } else {
                history.recordEvent(EventKind.ALERT_FAILED, base.taskId(), base.taskName(), base.taskType(), TaskRunRecord.Status.FAILED,
                        "Alert could not be delivered to Teams: " + alert.title(),
                        "POST " + cfg.webhookHost() + "\nResponse: " + (res.status() == 0 ? "no answer" : "HTTP " + res.status()) + " "
                        + (res.error() == null ? clipText(res.body()) : res.error()) + "\nGave up after " + MAX_ATTEMPTS + " attempts. The report was still written: " + reportPath,
                        now, now, base.runKey(), base.trigger());
            }
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Alert delivery not recorded", e);
        }
    }

    private static String clipText(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() > 300 ? t.substring(0, 300) + "..." : t;
    }

    /** Alerts waiting for or being handled by a worker thread. */
    public int pending() { return pending.get(); }

    private Result postWithRetry(String url, String body, String json, TeamsCard.Alert alert, String reportPath) {
        Result res = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            res = poster.post(url, body);
            AlertCallLog.write(dataDir, new AlertCallLog.Entry(clock.getAsLong(), nz(alert.taskId()), nz(alert.taskName()),
                    alert.kind() == null ? "" : alert.kind().name(), alert.title(), hostOf(url), res.status(), res.millis(), attempt,
                    res.error() != null ? res.error() : res.body(), reportPath, json));
            boolean retry = !res.ok() && (res.status() == 0 || res.status() == 429 || res.status() >= 500);
            if (!retry || attempt == MAX_ATTEMPTS) break;
            event(EventKind.ALERT_RETRY, alert, TaskRunRecord.Status.FAILED, "Alert retry " + attempt + " of " + MAX_ATTEMPTS + ": " + alert.title(),
                    "POST " + hostOf(url) + " answered " + (res.status() == 0 ? "nothing" : "HTTP " + res.status()) + " "
                    + (res.error() != null ? res.error() : clipText(res.body())) + "\nTrying again shortly.");
            try { Thread.sleep(retryPauseMs * attempt); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return res;
    }

    private Path writeReport(AlertConfig cfg, TeamsCard.Alert a, TaskRunRecord rec) throws Exception {
        Path dir = reportDir(cfg);
        Files.createDirectories(dir);
        List<String> lines = new ArrayList<>();
        List<TaskRunRecord> runs = new ArrayList<>(), events = new ArrayList<>();
        if (a.taskId() != null && !a.taskId().isBlank()) {
            try { if (logs != null) lines = logs.getTaskLogsRecent(a.taskId(), nz(a.taskName()), 400); } catch (RuntimeException ignored) { }
            try { runs = history.getRunsForTask(a.taskId(), 25); } catch (RuntimeException ignored) { }
            try {
                LocalDateTime end = a.raisedAt();
                events = history.queryActivityEvents(a.taskId(), end.minusHours(6), end.plusMinutes(1), 150);
            } catch (RuntimeException ignored) { }
        }
        String name = FILE_TS.format(a.raisedAt()) + "-" + safe(nz(a.taskName()).isEmpty() ? "system" : a.taskName()) + "-"
                + (a.kind() == null ? "alert" : a.kind().name().toLowerCase()) + ".html";
        Path file = dir.resolve(name);
        Files.writeString(file, AlertReport.build(a, lines, runs, events), StandardCharsets.UTF_8);
        pruneReports(dir, cfg.reportKeepDays);
        return file;
    }

    Path reportDir(AlertConfig cfg) {
        return cfg.reportDir.isBlank() ? new File(dataDir, "alert-reports").toPath() : new File(cfg.reportDir).toPath();
    }

    /** Largest report sent inside the webhook request (the request, not the card, carries it). */
    static final long MAX_ATTACHED_BYTES = 4L * 1024 * 1024;

    /**
     * Adds the report file to the webhook request next to the card, as {@code "report":{"fileName","contentType","contentBase64","size"}}.
     * Teams cards cannot hold a file, but the Workflow behind the webhook can save it (SharePoint/OneDrive "Create file") so the
     * report sits next to the message and anyone can download it and read it offline. Skipped if there is no report or it is too big.
     */
    static String withReport(String cardJson, String reportPath) {
        try {
            if (reportPath == null || reportPath.isEmpty() || !cardJson.endsWith("}")) return cardJson;
            Path f = Path.of(reportPath);
            long size = Files.size(f);
            if (size > MAX_ATTACHED_BYTES) return cardJson;
            String b64 = java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(f));
            return cardJson.substring(0, cardJson.length() - 1) + ",\"report\":{\"fileName\":\"" + f.getFileName().toString().replace("\"", "")
                    + "\",\"contentType\":\"text/html\",\"size\":" + size + ",\"contentBase64\":\"" + b64 + "\"}}";
        } catch (Exception e) {
            return cardJson;
        }
    }

    static String reportUrlFor(AlertConfig cfg, Path file) {
        if (!cfg.reportBaseUrl.isBlank()) {
            String base = cfg.reportBaseUrl.endsWith("/") ? cfg.reportBaseUrl : cfg.reportBaseUrl + "/";
            return base + URLEncoder.encode(file.getFileName().toString(), StandardCharsets.UTF_8).replace("+", "%20");
        }
        return file.toUri().toString();
    }

    private void pruneReports(Path dir, int keepDays) {
        long now = clock.getAsLong();
        if (now - lastPrune < 3_600_000L) return;
        lastPrune = now;
        long cutoff = now - keepDays * 86_400_000L;
        try (var s = Files.list(dir)) {
            s.filter(f -> f.getFileName().toString().endsWith(".html")).forEach(f -> {
                try { if (Files.getLastModifiedTime(f).toMillis() < cutoff) Files.deleteIfExists(f); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }

    private static String safe(String s) {
        String t = s.replaceAll("[^A-Za-z0-9._-]+", "_");
        return t.length() > 40 ? t.substring(0, 40) : t;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    static String hostOf(String url) {
        try { return URI.create(url).getHost(); } catch (RuntimeException e) { return ""; }
    }

    // ── Test card (from the settings screen) ───────────────────────────────

    /** Sends a sample card, with a sample report, using the given settings. Runs on the caller's thread. */
    public static Result sendTest(File dataDir, AlertConfig cfg, TaskLogService logs, RunHistoryService history) {
        String host;
        try { host = InetAddress.getLocalHost().getHostName(); } catch (Exception e) { host = "this-pc"; }
        LocalDateTime now = LocalDateTime.now();
        TeamsCard.Alert base = new TeamsCard.Alert(EventKind.RUN_FAILED, false, "test", "Sample task (test alert)", "SFTP_TRANSFER",
                "FAILED", "This is a test alert from Ops Transfer Tool. Nothing is wrong.",
                "Connection reset by peer while uploading orders_20261031.csv (sample)", "Check the sample server and retry.",
                now.minusSeconds(42), now, 42_000, "MANUAL", "test-run", host, null, null, 0, now);
        AlertDispatcher d = new AlertDispatcher(dataDir, history, logs, System::currentTimeMillis, new HttpPoster(), 0);
        d.serveReports = false;   // the running service serves the files; this process only makes the link
        String reportPath = "", reportUrl = null;
        try {
            Path dir = d.reportDir(cfg);
            Files.createDirectories(dir);
            List<String> lines = List.of("2026-10-31 09:00:01 INFO  Task started (sample)",
                    "2026-10-31 09:00:02 INFO  Connected to sample-host:22",
                    "2026-10-31 09:00:30 WARN  Slow transfer, retrying",
                    "2026-10-31 09:00:43 ERROR Connection reset by peer");
            Path file = dir.resolve(FILE_TS.format(now) + "-test-alert.html");
            Files.writeString(file, AlertReport.build(base, lines, List.of(), List.of()), StandardCharsets.UTF_8);
            reportPath = file.toString();
            reportUrl = d.linkFor(cfg, file);
        } catch (Exception e) {
            log.log(Level.WARNING, "Test report not written", e);
        }
        TeamsCard.Alert a = new TeamsCard.Alert(base.kind(), false, base.taskId(), base.taskName(), base.taskType(), base.status(),
                base.message(), base.details(), base.suggestedAction(), base.startedAt(), base.endedAt(), base.durationMs(),
                base.trigger(), base.runKey(), host, reportUrl, reportPath, 0, now);
        String json = TeamsCard.build(a);
        d.event(EventKind.ALERT_QUEUED, a, TaskRunRecord.Status.SUCCESS, "Test alert queued: " + a.title(),
                "Sent straight away from the settings screen. Destination: " + cfg.webhookHost() + ".");
        Result res = d.poster.post(cfg.webhookUrl, withReport(json, reportPath));
        if (res.ok()) {
            d.event(EventKind.ALERT_SENT, a, TaskRunRecord.Status.SUCCESS, "Test alert sent to Teams: " + a.title(),
                    "POST " + cfg.webhookHost() + "\nResponse: HTTP " + res.status() + " " + clipText(res.body()) + "\nReport: " + reportPath);
            d.reportSent(a, reportPath, reportUrl, withReport(json, reportPath).length() > json.length(), cfg.webhookHost());
        } else {
            d.event(EventKind.ALERT_FAILED, a, TaskRunRecord.Status.FAILED, "Test alert could not be delivered to Teams: " + a.title(),
                    "POST " + cfg.webhookHost() + "\nResponse: " + (res.status() == 0 ? "no answer" : "HTTP " + res.status()) + " "
                    + (res.error() == null ? clipText(res.body()) : res.error()));
        }
        AlertCallLog.write(dataDir, new AlertCallLog.Entry(System.currentTimeMillis(), "test", a.taskName(), "TEST", a.title(),
                hostOf(cfg.webhookUrl), res.status(), res.millis(), 1, res.error() != null ? res.error() : res.body(), reportPath, json));
        d.sender.shutdown();
        return res;
    }

    /** The real HTTP post. */
    static final class HttpPoster implements Poster {
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        @Override public Result post(String url, String json) {
            long t0 = System.currentTimeMillis();
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
                HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
                return new Result(r.statusCode(), r.body() == null ? "" : r.body(), null, System.currentTimeMillis() - t0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result(0, "", "interrupted", System.currentTimeMillis() - t0);
            } catch (Exception e) {
                return new Result(0, "", e.getClass().getSimpleName() + ": " + e.getMessage(), System.currentTimeMillis() - t0);
            }
        }
    }
}
