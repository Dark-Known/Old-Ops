package service;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class DaemonControlTest {

    @Test
    public void commandIsDeliveredAndExecuted() throws Exception {
        File dir = Files.createTempDirectory("opstool-ctl").toFile();
        List<String> seen = new ArrayList<>();
        try (DaemonControlServer s = DaemonControlServer.start(dir, (a, id, who) -> seen.add(a + "|" + id + "|" + who))) {
            assertTrue(DaemonControlClient.send(dir, CommandQueueService.Action.RUN_NOW, "task 1&x=2", "alice"));
            assertEquals(List.of("RUN_NOW|task 1&x=2|alice"), seen); // special characters survive
        }
        assertFalse("control file removed on close", new File(dir, DaemonControlServer.FILE_NAME).exists());
    }

    @Test
    public void executorFailureMeansNotDelivered() throws Exception {
        File dir = Files.createTempDirectory("opstool-ctl").toFile();
        try (DaemonControlServer s = DaemonControlServer.start(dir, (a, id, who) -> { throw new IllegalStateException("boom"); })) {
            assertFalse(DaemonControlClient.send(dir, CommandQueueService.Action.CANCEL, "t", "bob"));
        }
    }

    @Test
    public void wrongTokenAndWrongMethodAreRefused() throws Exception {
        File dir = Files.createTempDirectory("opstool-ctl").toFile();
        List<String> seen = new ArrayList<>();
        try (DaemonControlServer s = DaemonControlServer.start(dir, (a, id, who) -> seen.add(id))) {
            HttpClient c = HttpClient.newHttpClient();
            URI u = URI.create("http://127.0.0.1:" + s.port() + "/command");
            HttpResponse<String> bad = c.send(HttpRequest.newBuilder(u).header(DaemonControlServer.TOKEN_HEADER, "nope")
                    .POST(HttpRequest.BodyPublishers.ofString("action=REFRESH&taskId=x")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, bad.statusCode());
            HttpResponse<String> none = c.send(HttpRequest.newBuilder(u)
                    .POST(HttpRequest.BodyPublishers.ofString("action=REFRESH&taskId=x")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, none.statusCode());
            HttpResponse<String> get = c.send(HttpRequest.newBuilder(u).header(DaemonControlServer.TOKEN_HEADER, s.token()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(405, get.statusCode());
            HttpResponse<String> unknown = c.send(HttpRequest.newBuilder(u).header(DaemonControlServer.TOKEN_HEADER, s.token())
                    .POST(HttpRequest.BodyPublishers.ofString("action=FORMAT_DISK&taskId=x")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, unknown.statusCode());
            assertTrue("nothing may have been executed", seen.isEmpty());
        }
    }

    @Test
    public void noDaemonMeansFallBackToQueue() throws Exception {
        File dir = Files.createTempDirectory("opstool-ctl").toFile();
        assertFalse(DaemonControlClient.send(dir, CommandQueueService.Action.REFRESH, "", "gui")); // no control file
        File dead;
        try (DaemonControlServer s = DaemonControlServer.start(dir, (a, id, who) -> { })) {
            dead = new File(dir, DaemonControlServer.FILE_NAME);
            java.nio.file.Files.copy(dead.toPath(), new File(dir, "copy.json").toPath());
        }
        // stale control file left behind by a dead daemon: port refuses connections
        java.nio.file.Files.copy(new File(dir, "copy.json").toPath(), dead.toPath());
        assertFalse(DaemonControlClient.send(dir, CommandQueueService.Action.REFRESH, "", "gui"));
    }
}
