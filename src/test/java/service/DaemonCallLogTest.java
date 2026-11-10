package service;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DaemonCallLogTest {

    @Test
    public void controlCommandsStatusAndQueueFallbackAreAllLogged() throws Exception {
        File dir = Files.createTempDirectory("daemonlog").toFile();
        try (DaemonControlServer s = DaemonControlServer.start(dir, (action, taskId, who) -> {
            if (action == CommandQueueService.Action.CANCEL) throw new IllegalStateException("not running");
        })) {
            assertTrue(DaemonControlClient.send(dir, CommandQueueService.Action.RUN_NOW, "task-1234567890", "gui"));
            assertTrue("a failing command is still answered", !DaemonControlClient.send(dir, CommandQueueService.Action.CANCEL, "task-9", "gui"));

            java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpResponse<String> r = http.send(java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create("http://127.0.0.1:" + s.port() + "/command")).POST(java.net.http.HttpRequest.BodyPublishers.ofString("action=RUN_NOW"))
                    .header("X-Opstool-Token", "wrong").build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(403, r.statusCode());
            http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + s.port() + "/status"))
                    .header("X-Opstool-Token", s.token()).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        }
        List<DaemonCallLog.Entry> log = DaemonCallLog.readRecent(dir, 50);
        assertEquals(4, log.size());
        DaemonCallLog.Entry run = log.get(0);
        assertEquals("RUN_NOW", run.action());
        assertEquals(200, run.status());
        assertEquals("Run now (task task-123)", DaemonCallLog.describe(run));
        assertEquals(500, log.get(1).status());
        assertEquals("not running", log.get(1).response());
        assertEquals(403, log.get(2).status());
        assertTrue("the token is never written", !Files.readString(new File(new File(dir, "logs"), DaemonCallLog.FILE_NAME).toPath()).contains("wrong"));
        assertEquals("Status poll", DaemonCallLog.describe(log.get(3)));
    }
}
