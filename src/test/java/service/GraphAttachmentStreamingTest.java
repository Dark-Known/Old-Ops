package service;

import static org.junit.Assert.*;

import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/** Attachments are streamed one at a time; big ones go to a temporary file instead of the heap. */
public class GraphAttachmentStreamingTest {

    private static final byte[] SMALL = "hello,world\n1,2\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BIG = new byte[3 * 1024 * 1024];
    static { new java.util.Random(7).nextBytes(BIG); }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int code, byte[] body) throws java.io.IOException {
        ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) try (OutputStream o = ex.getResponseBody()) { o.write(body); }
        ex.close();
    }

    private static HttpServer server(AtomicInteger throttled, boolean valueEndpointWorks, boolean selectWorks) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.createContext("/me/messages/m1/attachments", ex -> {
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getRawQuery();
            if (path.endsWith("/a1/$value")) {
                if (!valueEndpointWorks) { reply(ex, 404, new byte[0]); return; }
                if (throttled != null && throttled.getAndIncrement() == 0) {
                    ex.getResponseHeaders().add("Retry-After", "1");
                    reply(ex, 429, new byte[0]);
                    return;
                }
                reply(ex, 200, SMALL);
            } else if (path.endsWith("/a2/$value")) {
                reply(ex, valueEndpointWorks ? 200 : 404, valueEndpointWorks ? BIG : new byte[0]);
            } else if (path.endsWith("/a1") || path.endsWith("/a2")) {   // single attachment as JSON (fallback)
                byte[] raw = path.endsWith("/a1") ? SMALL : BIG;
                String json = "{\"contentBytes\":\"" + java.util.Base64.getEncoder().encodeToString(raw) + "\"}";
                reply(ex, 200, json.getBytes(StandardCharsets.UTF_8));
            } else {
                if (query != null && query.contains("select") && !selectWorks) { reply(ex, 400, "{}".getBytes()); return; }
                boolean meta = query != null && query.contains("select");
                String c1 = meta ? "" : ",\"contentBytes\":\"" + java.util.Base64.getEncoder().encodeToString(SMALL) + "\"";
                String c2 = meta ? "" : ",\"contentBytes\":\"" + java.util.Base64.getEncoder().encodeToString(BIG) + "\"";
                String json = "{\"value\":["
                        + "{\"@odata.type\":\"#microsoft.graph.fileAttachment\",\"id\":\"a1\",\"name\":\"data.csv\",\"contentType\":\"text/csv\",\"size\":" + SMALL.length + ",\"isInline\":false" + c1 + "},"
                        + "{\"@odata.type\":\"#microsoft.graph.fileAttachment\",\"id\":\"a2\",\"name\":\"photo.bin\",\"contentType\":\"application/octet-stream\",\"size\":" + BIG.length + ",\"isInline\":false" + c2 + "},"
                        + "{\"@odata.type\":\"#microsoft.graph.fileAttachment\",\"id\":\"a3\",\"name\":\"logo.png\",\"contentType\":\"image/png\",\"size\":10,\"isInline\":true},"
                        + "{\"@odata.type\":\"#microsoft.graph.itemAttachment\",\"id\":\"a4\",\"name\":\"fwd.eml\",\"size\":10,\"isInline\":false}"
                        + "]}";
                reply(ex, 200, json.getBytes(StandardCharsets.UTF_8));
            }
        });
        s.start();
        return s;
    }

    private static final java.io.File logDir = newLogDir();

    private static java.io.File newLogDir() {
        try {
            java.io.File d = Files.createTempDirectory("graphlog").toFile();
            GraphCallLog.configure(d);
            return d;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static GraphMailService.AttachmentBundle fetch(HttpServer s, java.util.List<String> log) throws Exception {
        GraphMailService g = new GraphMailService();
        g.setGraphBaseForTesting("http://127.0.0.1:" + s.getAddress().getPort());
        return g.fetchTextAttachments("token", "m1", log::add);
    }

    private static void check(GraphMailService.AttachmentBundle b) throws Exception {
        assertEquals("inline and item attachments are skipped", 2, b.files.size());
        GraphMailService.AttachmentFile csv = b.files.get(0), big = b.files.get(1);
        assertEquals("data.csv", csv.name);
        assertFalse("small stays in memory", csv.isSpilled());
        assertTrue("big goes to a temp file", big.isSpilled());
        assertEquals(BIG.length, big.length());
        assertTrue("text attachment is turned into text", b.text.contains("hello,world"));
        assertEquals(Arrays.asList("data.csv"), b.names);
        Path out = Files.createTempFile("opstool-att-out", ".bin");
        big.copyTo(out);
        assertTrue("content identical", Arrays.equals(BIG, Files.readAllBytes(out)));
        Files.delete(out);
        csv.copyTo(out);
        assertTrue(Arrays.equals(SMALL, Files.readAllBytes(out)));
        Files.delete(out);
    }

    @Test public void attachmentsAreStreamedAndBigOnesSpillToDisk() throws Exception {
        HttpServer s = server(null, true, true);
        try {
            GraphMailService.AttachmentBundle b = fetch(s, new ArrayList<>());
            check(b);
            for (GraphMailService.AttachmentFile f : b.files) f.discard();
            f(b);
        } finally { s.stop(0); }
    }

    private static void f(GraphMailService.AttachmentBundle b) throws Exception {
        // after discard, the temp file is gone: copying must fail
        Path out = Files.createTempFile("opstool-att-out", ".bin");
        try {
            b.files.get(1).copyTo(out);
            fail("temp file should be gone after discard");
        } catch (java.io.IOException expected) { }
        Files.deleteIfExists(out);
    }

    @Test public void aThrottledDownloadIsRetried() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer s = server(hits, true, true);
        try {
            GraphMailService.AttachmentBundle b = fetch(s, new ArrayList<>());
            check(b);
            assertTrue("first request was throttled, then retried", hits.get() >= 2);
            java.util.List<GraphCallLog.Entry> calls = GraphCallLog.readRecent(logDir, 100);
            assertTrue("calls are logged", calls.size() >= 2);
            boolean throttled = false, retried = false;
            for (GraphCallLog.Entry c : calls) {
                if (c.status() == 429) throttled = true;
                if (c.attempt() == 2 && c.ok()) retried = true;
                assertTrue("no token or secret in the log: " + c, !c.target().contains("token") || c.target().contains("/token"));
            }
            assertTrue("429 recorded", throttled);
            assertTrue("successful second attempt recorded", retried);
            assertEquals("Download attachment", GraphCallLog.describe(new GraphCallLog.Entry(1, "graph", "GET",
                    "h/v1.0/me/messages/m1/attachments/a1/$value", "/me", 200, 5, -1, "", 1, "")));
            for (GraphMailService.AttachmentFile x : b.files) x.discard();
        } finally { s.stop(0); }
    }

    @Test public void ifTheRawEndpointRefusesEachAttachmentIsFetchedAsJson() throws Exception {
        HttpServer s = server(null, false, true);
        try {
            GraphMailService.AttachmentBundle b = fetch(s, new ArrayList<>());
            assertEquals(2, b.files.size());
            assertEquals(BIG.length, b.files.get(1).length());
            assertTrue(b.text.contains("hello,world"));
        } finally { s.stop(0); }
    }

    @Test public void ifTheMetadataListIsRefusedTheOldFullListIsUsed() throws Exception {
        HttpServer s = server(null, true, false);
        try {
            java.util.List<String> log = new ArrayList<>();
            GraphMailService.AttachmentBundle b = fetch(s, log);
            assertEquals(2, b.files.size());
            assertEquals(BIG.length, b.files.get(1).length());
            assertTrue(log.toString(), log.stream().anyMatch(l -> l.contains("using the full list")));
        } finally { s.stop(0); }
    }
}
