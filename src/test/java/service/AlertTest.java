package service;

import com.sun.net.httpserver.HttpServer;
import model.EventKind;
import model.TaskRunRecord;
import org.junit.Test;
import util.MiniJson;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class AlertTest {

    private static AlertConfig enabled(String url) {
        return AlertConfig.empty().with(true, url, AlertConfig.PROBLEMS, true, 15, "", "", 30);
    }

    @Test public void theWebhookIsEncryptedOnDiskAndComesBack() throws Exception {
        File dir = Files.createTempDirectory("alertcfg").toFile();
        String url = "https://prod-1.westeurope.logic.azure.com:443/workflows/abc?sig=SECRET123";
        AlertConfig c = enabled(url).withTask("t1", new AlertConfig.TaskRule(AlertConfig.Mode.OFF, java.util.Set.of()))
                .withTask("t2", new AlertConfig.TaskRule(AlertConfig.Mode.CUSTOM, EnumSet.of(EventKind.NO_FILES)));
        c.save(dir);
        String raw = Files.readString(new File(dir, AlertConfig.FILE_NAME).toPath());
        assertTrue("secret not stored in clear", !raw.contains("SECRET123"));
        AlertConfig back = AlertConfig.load(dir);
        assertEquals(url, back.webhookUrl);
        assertEquals("prod-1.westeurope.logic.azure.com", back.webhookHost());
        assertEquals(AlertConfig.Mode.OFF, back.ruleFor("t1").mode());
        assertEquals(EnumSet.of(EventKind.NO_FILES), back.ruleFor("t2").kinds());
        assertEquals(AlertConfig.Mode.INHERIT, back.ruleFor("other").mode());
    }

    @Test public void whatProducesAnAlertForWhichTask() {
        AlertConfig c = enabled("http://x/y").withTask("off", new AlertConfig.TaskRule(AlertConfig.Mode.OFF, java.util.Set.of()))
                .withTask("mine", new AlertConfig.TaskRule(AlertConfig.Mode.CUSTOM, EnumSet.of(EventKind.NO_FILES)));
        assertTrue(c.wants("a", EventKind.RUN_FAILED));
        assertTrue(c.wants("a", EventKind.NO_FILES));
        assertTrue("a recovery follows", c.wants("a", EventKind.FILES_RESUMED));
        assertTrue("successes are not alerts", !c.wants("a", EventKind.RUN_SUCCEEDED));
        assertTrue("info events are not alerts", !c.wants("a", EventKind.RUN_STARTED));
        assertTrue(!c.wants("off", EventKind.RUN_FAILED));
        assertTrue(!c.wants("off", EventKind.FILES_RESUMED));
        assertTrue("custom list is used", c.wants("mine", EventKind.NO_FILES) && !c.wants("mine", EventKind.RUN_FAILED));
        assertTrue("alert deliveries never alert", !c.wants("a", EventKind.ALERT_FAILED) && !c.wants("a", EventKind.ALERT_SENT));
        assertTrue("disabled sends nothing", !c.with(false, "http://x/y", AlertConfig.PROBLEMS, true, 15, "", "", 30).wants("a", EventKind.RUN_FAILED));
        assertTrue("no webhook sends nothing", !c.with(true, "", AlertConfig.PROBLEMS, true, 15, "", "", 30).wants("a", EventKind.RUN_FAILED));
    }

    private static TeamsCard.Alert sample(boolean recovery) {
        LocalDateTime t = LocalDateTime.of(2026, 10, 31, 9, 0, 0);
        return new TeamsCard.Alert(EventKind.RUN_FAILED, recovery, "id1", "Orders \"nightly\"", "SFTP_TRANSFER", "FAILED",
                "Upload failed: connection reset", "Line1\nLine2 with \"quotes\" and <tags>", "Check the server.", t, t.plusSeconds(30), 30_000,
                "SCHEDULE", "run-1", "SERVER-C", "file:///C:/data/report.html", "C:\\data\\report.html", 2, t.plusSeconds(31));
    }

    @Test public void theCardIsValidAdaptiveCardJsonWithAnOpenLogsButton() {
        String json = TeamsCard.build(sample(false));
        Map<String, Object> msg = MiniJson.parseObject(json);       // throws on invalid JSON
        assertEquals("message", MiniJson.getString(msg, "type", ""));
        Map<String, Object> att = (Map<String, Object>) MiniJson.getArray(msg, "attachments").get(0);
        assertEquals("application/vnd.microsoft.card.adaptive", MiniJson.getString(att, "contentType", ""));
        Map<String, Object> card = MiniJson.getObject(att, "content");
        assertEquals("AdaptiveCard", MiniJson.getString(card, "type", ""));
        assertTrue(json.contains("Open logs") && json.contains("file:///C:/data/report.html"));
        assertTrue(json.contains("Orders \\\"nightly\\\""));
        assertTrue(json.contains("2 similar alerts"));
        assertTrue("failure uses the attention colour", json.contains("\"style\":\"attention\""));
        String rec = TeamsCard.build(sample(true));
        assertTrue(rec.contains("Recovered: ") && rec.contains("\"style\":\"good\""));
        MiniJson.parseObject(rec);
    }

    @Test public void theReportIsOneOfflineHtmlFileAndEscapesEverything() {
        List<String> lines = List.of("INFO started", "ERROR boom <script>alert(1)</script>", "WARN retry");
        String html = AlertReport.build(sample(false), lines, List.of(), List.of());
        assertTrue(html.contains("<script>(function()"));
        assertTrue("log text is escaped", html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertTrue(!html.contains("alert(1)</script>"));
        assertTrue("works offline: nothing is loaded from the network", !html.contains("src=\"http") && !html.contains("href=\"http")
                && !html.contains("@import") && !html.contains("<link"));
        assertTrue(html.contains("class=\"row e\">ERROR") && html.contains("class=\"row w\">WARN"));
        assertTrue(html.contains("Task log (3)"));
    }

    // ── Dispatcher with a fake Teams workflow ─────────────────────────────

    private static final class Hook implements AutoCloseable {
        final HttpServer server;
        final List<String> bodies = new CopyOnWriteArrayList<>();
        final AtomicInteger failFirst = new AtomicInteger();
        Hook() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/hook", ex -> {
                bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                int code = failFirst.getAndDecrement() > 0 ? 500 : 202;
                ex.sendResponseHeaders(code, -1);
                ex.close();
            });
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook"; }
        @Override public void close() { server.stop(0); }
    }

    private static TaskRunRecord rec(String id, String name, TaskRunRecord.Status st, String kind, String reason) {
        TaskRunRecord r = new TaskRunRecord();
        r.setTaskId(id); r.setTaskName(name); r.setTaskType("SFTP_TRANSFER"); r.setStatus(st); r.setReason(reason); r.setDetails(reason);
        r.setStartedAt(LocalDateTime.now().minusSeconds(5)); r.setEndedAt(LocalDateTime.now()); r.setDurationMs(5000);
        r.setEventKind(kind); r.setRunKey("rk"); r.setTrigger("SCHEDULE");
        return r;
    }

    private static void waitFor(java.util.function.BooleanSupplier c) throws Exception {
        for (int i = 0; i < 100 && !c.getAsBoolean(); i++) Thread.sleep(50);
    }

    @Test public void aFailureIsSentOnceAsACardWithAReportThenRecoveryWhenItSucceeds() throws Exception {
        File dir = Files.createTempDirectory("alertdisp").toFile();
        try (Hook hook = new Hook()) {
            enabled(hook.url()).save(dir);
            RunHistoryService history = new RunHistoryService(dir.getAbsolutePath());
            AtomicLong now = new AtomicLong(1_000_000);
            try (AlertDispatcher d = new AlertDispatcher(dir, history, null, now::get, new AlertDispatcher.HttpPoster(), 0)) {
                d.onRecord(rec("t1", "Orders", TaskRunRecord.Status.FAILED, null, "Upload failed"));
                d.onRecord(rec("t1", "Orders", TaskRunRecord.Status.FAILED, null, "Upload failed again"));   // held back
                waitFor(() -> hook.bodies.size() >= 1);
                assertEquals(1, hook.bodies.size());
                assertTrue(hook.bodies.get(0).contains("Open logs") && hook.bodies.get(0).contains("Orders"));
                Map<String, Object> first = MiniJson.parseObject(hook.bodies.get(0));
                Map<String, Object> rep = MiniJson.getObject(first, "report");
                assertTrue("the report travels with the card", MiniJson.getString(rep, "fileName", "").endsWith(".html"));
                String html = new String(java.util.Base64.getDecoder().decode(MiniJson.getString(rep, "contentBase64", "")), StandardCharsets.UTF_8);
                assertTrue(html.contains("<script>") && html.contains("Upload failed"));
                File[] reports = new File(dir, "alert-reports").listFiles();
                assertEquals(1, reports.length);
                assertTrue(Files.readString(reports[0].toPath()).contains("Upload failed"));

                d.onRecord(rec("t1", "Orders", TaskRunRecord.Status.SUCCESS, null, "ok"));
                waitFor(() -> hook.bodies.size() >= 2);
                assertEquals(2, hook.bodies.size());
                assertTrue(hook.bodies.get(1).contains("Recovered: "));

                d.onRecord(rec("t2", "Other", TaskRunRecord.Status.SUCCESS, null, "ok"));      // success without a problem: silent
                Thread.sleep(200);
                assertEquals(2, hook.bodies.size());

                waitFor(() -> history.queryActivityEvents("t1", LocalDateTime.now().minusHours(1), LocalDateTime.now().plusMinutes(1), 50).size() >= 2);
                long sent = history.queryActivityEvents("t1", LocalDateTime.now().minusHours(1), LocalDateTime.now().plusMinutes(1), 50)
                        .stream().filter(e -> "ALERT_SENT".equals(e.getEventKind())).count();
                assertEquals("deliveries show up in the Event Monitor", 2, sent);
                long queued = history.queryActivityEvents("t1", LocalDateTime.now().minusHours(1), LocalDateTime.now().plusMinutes(1), 50)
                        .stream().filter(e -> "ALERT_QUEUED".equals(e.getEventKind())).count();
                assertEquals("each alert is queued first", 2, queued);
                waitFor(() -> history.queryActivityEvents("t1", LocalDateTime.now().minusHours(1), LocalDateTime.now().plusMinutes(1), 50)
                        .stream().filter(e -> "REPORT_SENT".equals(e.getEventKind())).count() >= 2);
                List<TaskRunRecord> sentReports = new ArrayList<>();
                for (TaskRunRecord e : history.queryActivityEvents("t1", LocalDateTime.now().minusHours(1), LocalDateTime.now().plusMinutes(1), 50))
                    if ("REPORT_SENT".equals(e.getEventKind())) sentReports.add(e);
                assertEquals("the report that went out is recorded", 2, sentReports.size());
                assertTrue(sentReports.get(0).getDetails().contains("Attached to the webhook request"));
                assertTrue("the link key is not written to the event", !sentReports.get(0).getDetails().matches("(?s).*[?&]k=[0-9a-f]{8}.*"));
                assertEquals("everything is handled", 0, d.pending());
                List<AlertCallLog.Entry> calls = AlertCallLog.readRecent(dir, 10);
                assertEquals(2, calls.size());
                assertEquals(202, calls.get(0).status());
                assertTrue("the log keeps the card, not the file", !Files.readString(new File(dir, AlertCallLog.FILE_NAME).toPath()).contains("contentBase64"));
                assertTrue("the secret address is never logged", !Files.readString(new File(dir, AlertCallLog.FILE_NAME).toPath()).contains("/hook"));
            }
        }
    }

    @Test public void aTaskSetToOffSendsNothingAndAFailingWebhookIsRetriedThenReported() throws Exception {
        File dir = Files.createTempDirectory("alertdisp2").toFile();
        try (Hook hook = new Hook()) {
            hook.failFirst.set(2);
            enabled(hook.url()).withTask("quiet", new AlertConfig.TaskRule(AlertConfig.Mode.OFF, java.util.Set.of())).save(dir);
            RunHistoryService history = new RunHistoryService(dir.getAbsolutePath());
            try (AlertDispatcher d = new AlertDispatcher(dir, history, null, System::currentTimeMillis, new AlertDispatcher.HttpPoster(), 0)) {
                d.onRecord(rec("quiet", "Quiet", TaskRunRecord.Status.FAILED, null, "x"));
                Thread.sleep(300);
                assertEquals(0, hook.bodies.size());

                d.onRecord(rec("t1", "Orders", TaskRunRecord.Status.FAILED, "NO_FILES", "No files found"));
                waitFor(() -> hook.bodies.size() >= 3);
                assertEquals("two server errors, then accepted", 3, hook.bodies.size());
                waitFor(() -> history.queryActivityEvents("t1", LocalDateTime.now().minusHours(1), LocalDateTime.now().plusMinutes(1), 50).stream()
                        .anyMatch(e -> "ALERT_SENT".equals(e.getEventKind())));
                long retries = history.queryActivityEvents("t1", LocalDateTime.now().minusHours(1), LocalDateTime.now().plusMinutes(1), 50)
                        .stream().filter(e -> "ALERT_RETRY".equals(e.getEventKind())).count();
                assertEquals("each failed attempt is an event", 2, retries);
            }
        }
        // a webhook that never answers: reported as not delivered, the report still written
        File dir2 = Files.createTempDirectory("alertdisp3").toFile();
        enabled("http://127.0.0.1:9/hook").save(dir2);
        RunHistoryService h2 = new RunHistoryService(dir2.getAbsolutePath());
        try (AlertDispatcher d = new AlertDispatcher(dir2, h2, null, System::currentTimeMillis, new AlertDispatcher.HttpPoster(), 0)) {
            d.onRecord(rec("t1", "Orders", TaskRunRecord.Status.FAILED, null, "boom"));
            java.util.function.Supplier<List<TaskRunRecord>> evs = () -> h2.queryActivityEvents("t1", LocalDateTime.now().minusHours(1), LocalDateTime.now().plusMinutes(1), 50);
            waitFor(() -> evs.get().stream().anyMatch(e -> "ALERT_FAILED".equals(e.getEventKind())));
            List<String> kinds = new ArrayList<>();
            for (TaskRunRecord r : evs.get()) kinds.add(r.getEventKind());
            assertTrue("queued, retried, then given up: " + kinds, kinds.contains("ALERT_QUEUED") && kinds.contains("ALERT_RETRY") && kinds.contains("ALERT_FAILED"));
            assertEquals(1, new File(dir2, "alert-reports").listFiles().length);
        }
    }

    @Test public void monitorsHaveTheirOwnRulesAndASocketSourceCanOverrideItsMonitor() {
        AlertConfig c = enabled("http://x/y")
                .withTask(AlertConfig.SERVICE, new AlertConfig.TaskRule(AlertConfig.Mode.OFF, java.util.Set.of()))
                .withTask(AlertConfig.SOCKET, new AlertConfig.TaskRule(AlertConfig.Mode.CUSTOM, EnumSet.of(EventKind.LINK_DOWN)))
                .withTask("srcA", new AlertConfig.TaskRule(AlertConfig.Mode.OFF, java.util.Set.of()));
        assertTrue("service monitor is off", !c.wants(AlertConfig.SERVICE, EventKind.SERVICE_COMMAND_FAILED) && !c.wants(null, EventKind.BACKGROUND_FAILED));
        assertTrue("socket monitor: only its own list", c.wants("srcB", EventKind.LINK_DOWN) && !c.wants("srcB", EventKind.LINK_STALLED));
        assertTrue("a source set to Off overrides the monitor", !c.wants("srcA", EventKind.LINK_DOWN));
        assertTrue("outlook follows the defaults", c.wants(AlertConfig.OUTLOOK, EventKind.GRAPH_FAILING) && c.wants(AlertConfig.OUTLOOK, EventKind.GRAPH_RECOVERED));
        assertTrue("a task rule is not affected by monitors", c.wants("t9", EventKind.RUN_FAILED));
        assertEquals(AlertConfig.SOCKET, AlertConfig.areaOf(EventKind.LINK_UP));
        assertEquals(null, AlertConfig.areaOf(EventKind.RUN_FAILED));
    }

    @Test public void theMailApiRaisesOneEventAfterThreeFailuresAndOneWhenItRecovers() throws Exception {
        File dir = Files.createTempDirectory("monev").toFile();
        RunHistoryService history = new RunHistoryService(dir.getAbsolutePath());
        MonitorEvents me = new MonitorEvents(history);
        me.start();
        try {
            GraphCallLog.configure(dir);
            java.net.URI u = java.net.URI.create("https://graph.microsoft.com/v1.0/users/ops%40corp.com/messages");
            for (int i = 0; i < 5; i++) GraphCallLog.record("graph", "GET", u, "/users/ops%40corp.com", 500, 50, -1, "r" + i, 1, "boom");
            GraphCallLog.record("graph", "GET", u, "/users/ops%40corp.com", 200, 50, 10, "ok", 1, "");
            DaemonCallLog.configure(dir);
            DaemonCallLog.record("control", "POST", "/command", 500, 5, "CANCEL", "t1", "gui", "Task is not running");
            DaemonCallLog.record("control", "POST", "/command", 200, 5, "RUN_NOW", "t1", "gui", "OK");
            LocalDateTime a = LocalDateTime.now().minusHours(1), b = LocalDateTime.now().plusMinutes(1);
            List<String> kinds = new ArrayList<>();
            for (TaskRunRecord r : history.queryActivityEvents(AlertConfig.OUTLOOK, a, b, 50)) kinds.add(r.getEventKind());
            for (TaskRunRecord r : history.queryActivityEvents(AlertConfig.SERVICE, a, b, 50)) kinds.add(r.getEventKind());
            java.util.Collections.sort(kinds);
            assertEquals(List.of("GRAPH_FAILING", "GRAPH_RECOVERED", "SERVICE_COMMAND_FAILED"), kinds);
        } finally {
            me.close();
        }
    }

    @Test public void alertsWaitingWhenTheServiceStoppedAreSentAfterTheRestartAndOldOnesExpire() throws Exception {
        File dir = Files.createTempDirectory("alertq").toFile();
        try (Hook hook = new Hook()) {
            enabled(hook.url()).save(dir);
            AlertQueueStore store = new AlertQueueStore(dir);
            long now = System.currentTimeMillis();
            File fresh = store.save(sample(false), now - 60_000);
            File old = store.save(sample(false), now - 30L * 3600_000L);
            assertTrue(fresh.isFile() && old.isFile());
            RunHistoryService history = new RunHistoryService(dir.getAbsolutePath());
            try (AlertDispatcher d = new AlertDispatcher(dir, history, null, System::currentTimeMillis, new AlertDispatcher.HttpPoster(), 0)) {
                d.start();
                waitFor(() -> hook.bodies.size() >= 1 && !fresh.exists());
                assertEquals("only the recent one is sent", 1, hook.bodies.size());
                assertTrue(hook.bodies.get(0).contains("Orders"));
                assertTrue("both files are gone", !fresh.exists() && !old.exists());
                List<String> kinds = new ArrayList<>();
                waitFor(() -> history.queryActivityEvents("id1", LocalDateTime.now().minusHours(1), LocalDateTime.now().plusMinutes(1), 50).size() >= 3);
                for (TaskRunRecord r : history.queryActivityEvents("id1", LocalDateTime.now().minusHours(1), LocalDateTime.now().plusMinutes(1), 50)) kinds.add(r.getEventKind());
                assertTrue(kinds.toString(), kinds.contains("ALERT_QUEUED") && kinds.contains("ALERT_SENT") && kinds.contains("ALERT_FAILED"));
            }
        }
    }

    @Test public void aQueuedAlertIsSavedUntilItIsDelivered() throws Exception {
        File dir = Files.createTempDirectory("alertq2").toFile();
        java.util.concurrent.CountDownLatch inPost = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
        enabled("http://x/y").save(dir);
        RunHistoryService history = new RunHistoryService(dir.getAbsolutePath());
        AlertDispatcher.Poster slow = (url, json) -> {
            inPost.countDown();
            try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new AlertDispatcher.Result(202, "", null, 1);
        };
        try (AlertDispatcher d = new AlertDispatcher(dir, history, null, System::currentTimeMillis, slow, 0)) {
            d.onRecord(rec("t1", "Orders", TaskRunRecord.Status.FAILED, null, "Upload failed"));
            assertTrue(inPost.await(5, java.util.concurrent.TimeUnit.SECONDS));
            File[] saved = new File(dir, AlertQueueStore.DIR).listFiles((x, n) -> n.endsWith(".json"));
            assertEquals("kept on disk while it is being sent", 1, saved.length);
            release.countDown();
            waitFor(() -> new File(dir, AlertQueueStore.DIR).listFiles((x, n) -> n.endsWith(".json")).length == 0);
            assertEquals(0, new File(dir, AlertQueueStore.DIR).listFiles((x, n) -> n.endsWith(".json")).length);
        }
    }

    @Test public void aTestCardIsAlsoRecordedInTheEventMonitor() throws Exception {
        File dir = Files.createTempDirectory("alerttest").toFile();
        try (Hook hook = new Hook()) {
            AlertConfig cfg = enabled(hook.url());
            RunHistoryService history = new RunHistoryService(dir.getAbsolutePath());
            AlertDispatcher.Result r = AlertDispatcher.sendTest(dir, cfg, null, history);
            assertTrue(r.ok());
            List<String> kinds = new ArrayList<>();
            for (TaskRunRecord e : history.queryActivityEvents("test", LocalDateTime.now().minusHours(1), LocalDateTime.now().plusMinutes(1), 20)) kinds.add(e.getEventKind());
            assertTrue(kinds.toString(), kinds.contains("ALERT_QUEUED") && kinds.contains("ALERT_SENT"));
            assertEquals(1, AlertCallLog.readRecent(dir, 5).size());
        }
    }

    @Test public void reportsAreServedWithASignedLinkAndCanBeDownloaded() throws Exception {
        File dir = Files.createTempDirectory("alertsrv").toFile();
        java.nio.file.Path reports = new File(dir, "alert-reports").toPath();
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("r1.html"), "<html>report one</html>");
        Files.writeString(dir.toPath().resolve("secret.html"), "<html>not a report</html>");
        List<String> seen = new CopyOnWriteArrayList<>();
        try (AlertReportServer s = AlertReportServer.start(dir, "127.0.0.1", 0, () -> reports, List.of(), false, (remote, name, download, status) -> seen.add(name + "|" + download + "|" + status))) {
            java.net.http.HttpClient c = java.net.http.HttpClient.newHttpClient();
            String url = s.urlFor("127.0.0.1", "r1.html");
            java.net.http.HttpResponse<String> ok = c.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode());
            assertTrue(ok.body().contains("report one"));
            assertTrue(ok.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
            assertTrue("shown in the browser by default", ok.headers().firstValue("Content-Disposition").isEmpty());
            java.net.http.HttpResponse<String> dl = c.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url + "&download=1")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals("attachment; filename=\"r1.html\"", dl.headers().firstValue("Content-Disposition").orElse(""));
            String base = url.substring(0, url.indexOf('?'));
            assertEquals("no key", 403, c.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base)).build(), java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals("wrong key", 403, c.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "?k=00")).build(), java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
            String other = s.urlFor("127.0.0.1", "r2.html");
            assertEquals("a key for another file does not work", 403, c.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + other.substring(other.indexOf('?')))).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals("missing file", 404, c.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(other)).build(), java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
            waitFor(() -> seen.size() >= 7);
            assertTrue(seen.toString(), seen.contains("r1.html|false|200") && seen.contains("r1.html|true|200") && seen.contains("r1.html|false|403")
                    && seen.contains("r2.html|false|404"));
            String trav = "http://127.0.0.1:" + s.port() + "/alerts/reports/..%2Fsecret.html?k=" + s.keyFor("../secret.html");
            assertEquals("no way out of the folder", 404, c.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(trav)).build(), java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test public void theOpenLogsLinkPointsAtTheReportServerUnlessABaseAddressIsSet() throws Exception {
        File dir = Files.createTempDirectory("alertlink").toFile();
        RunHistoryService history = new RunHistoryService(dir.getAbsolutePath());
        java.nio.file.Path f = new File(dir, "alert-reports/20261101-090000-orders-run_failed.html").toPath();
        try (AlertDispatcher d = new AlertDispatcher(dir, history, null, System::currentTimeMillis, new AlertDispatcher.HttpPoster(), 0)) {
            AlertConfig c = enabled("http://x/y").withReportServer(true, 8555, "ops-pc.corp");
            String u = d.linkFor(c, f);
            assertTrue(u, u.startsWith("https://ops-pc.corp:8555/alerts/reports/20261101-090000-orders-run_failed.html?k="));
            AlertConfig base = c.with(true, "http://x/y", AlertConfig.PROBLEMS, true, 15, "", "https://files.corp/alerts", 30);
            assertEquals("https://files.corp/alerts/20261101-090000-orders-run_failed.html", d.linkFor(base, f));
            assertTrue(d.linkFor(c.withReportServer(false, 8555, ""), f).startsWith("file:"));
        }
        assertTrue("the report page has a download button", AlertReport.build(sample(false), List.of("x"), List.of(), List.of()).contains("id=\"dl\""));
    }

    @Test public void reportRequestsAreRecordedInTheEventMonitor() throws Exception {
        File dir = Files.createTempDirectory("alertrep").toFile();
        RunHistoryService history = new RunHistoryService(dir.getAbsolutePath());
        try (AlertDispatcher d = new AlertDispatcher(dir, history, null, System::currentTimeMillis, new AlertDispatcher.HttpPoster(), 0)) {
            d.reportRequest("10.0.0.5", "r1.html", false, 200);
            d.reportRequest("10.0.0.5", "r1.html", true, 200);
            d.reportRequest("10.0.0.9", "r1.html", false, 403);
            List<String> kinds = new ArrayList<>();
            for (TaskRunRecord e : history.queryActivityEvents("", LocalDateTime.now().minusHours(1), LocalDateTime.now().plusMinutes(1), 20)) kinds.add(e.getEventKind());
            java.util.Collections.sort(kinds);
            assertEquals(List.of("REPORT_DOWNLOADED", "REPORT_REJECTED", "REPORT_VIEWED"), kinds);
            assertTrue(!AlertConfig.empty().with(true, "http://x", AlertConfig.PROBLEMS, true, 15, "", "", 30).wants("", EventKind.REPORT_VIEWED));
        }
    }

    @Test public void aWatchedFolderCanAlertOnNewFilesWithoutALaterRecoveredCard() throws Exception {
        File dir = Files.createTempDirectory("alertwatch").toFile();
        try (Hook hook = new Hook()) {
            enabled(hook.url()).withTask("w1", new AlertConfig.TaskRule(AlertConfig.Mode.CUSTOM, EnumSet.of(EventKind.FILES_DETECTED))).save(dir);
            RunHistoryService history = new RunHistoryService(dir.getAbsolutePath());
            try (AlertDispatcher d = new AlertDispatcher(dir, history, null, System::currentTimeMillis, new AlertDispatcher.HttpPoster(), 0)) {
                d.onRecord(rec("w1", "Watch inbox", TaskRunRecord.Status.SUCCESS, "FILES_DETECTED", "2 new file(s): a.csv, b.csv"));
                waitFor(() -> hook.bodies.size() >= 1);
                assertTrue(hook.bodies.get(0).contains("Files detected") && hook.bodies.get(0).contains("a.csv"));
                d.onRecord(rec("w1", "Watch inbox", TaskRunRecord.Status.SUCCESS, null, "ok"));
                Thread.sleep(300);
                assertEquals("information is not a problem, so nothing 'recovers'", 1, hook.bodies.size());
            }
        }
    }
}
