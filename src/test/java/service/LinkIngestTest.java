package service;

import static org.junit.Assert.*;

import model.EventKind;
import org.junit.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

public class LinkIngestTest {

    private static final long HOUR = 3_600_000L;

    // ── Settings file ─────────────────────────────────────────────────────

    @Test public void configParsingHandlesHashesLegacyTokensAndBadEntries() {
        Properties p = new Properties();
        p.setProperty("source.a.label", "A to B");
        p.setProperty("source.a.tokenHash", LinkTokens.hash("secret-a-secret-a-secret"));
        p.setProperty("source.b.token", "0123456789abcdef-legacy");           // old plain format
        p.setProperty("source.c.token", "short");
        p.setProperty("source.d.tokenHash", LinkTokens.hash("secret-a-secret-a-secret"));   // same token as a
        p.setProperty("source.e.label", "no token");
        LinkMonitorConfig c = LinkMonitorConfig.parse(p);
        assertEquals(8443, c.port);
        assertTrue("HTTPS is the default", c.tlsEnabled);
        assertEquals(2, c.sources.size());
        assertEquals("A to B", c.source("a").label());
        assertEquals(60, c.source("a").heartbeatSeconds());
        assertEquals(180, c.source("a").silentAfterSeconds());
        assertTrue(c.source("b").accepts("0123456789abcdef-legacy", 0));
        assertEquals("short, duplicate and missing tokens each reported, plus the plain-text hint", 4, c.problems.size());
        assertTrue(c.enabled());
        assertFalse("no sources, nothing to serve", LinkMonitorConfig.empty().enabled());
    }

    @Test public void tlsCanOnlyBeSwitchedOffExplicitly() {
        Properties p = new Properties();
        p.setProperty("tls.enabled", "false");
        assertFalse(LinkMonitorConfig.parse(p).tlsEnabled);
        p.setProperty("tls.enabled", "yes please");
        assertTrue(LinkMonitorConfig.parse(p).tlsEnabled);
    }

    @Test public void settingsSurviveASaveAndReload_andStoreOnlyHashes() throws Exception {
        File dir = Files.createTempDirectory("lm").toFile();
        LinkMonitorConfig.Issued first = LinkMonitorConfig.empty().addSource("Order feed A to B", 60, 0, 30, 5, 10, 1000);
        first.config().withEndpoint(9443, List.of("serverc.corp", "10.1.2.3")).save(dir);

        String text = Files.readString(new File(dir, LinkMonitorConfig.FILE_NAME).toPath());
        assertFalse("the token itself is never written", text.contains(first.token()));
        assertTrue(text.contains(LinkTokens.hash(first.token())));

        LinkMonitorConfig back = LinkMonitorConfig.load(dir);
        assertEquals(9443, back.port);
        assertEquals(List.of("serverc.corp", "10.1.2.3"), back.tlsHostnames);
        assertEquals("order-feed-a-to-b", first.sourceId());
        assertEquals("Order feed A to B", back.source(first.sourceId()).label());
        assertTrue(back.source(first.sourceId()).accepts(first.token(), 0));
        assertTrue(back.problems.isEmpty());
    }

    @Test public void sourceIdsAreSafeAndUnique() {
        LinkMonitorConfig c = LinkMonitorConfig.empty();
        assertEquals("a-b-feed", c.newSourceId("  A/B feed!! "));
        assertEquals("source", c.newSourceId("***"));
        c = c.addSource("Orders", 60, 0, 30, 5, 10, 0).config();
        assertEquals("orders-2", c.newSourceId("Orders"));
    }

    @Test public void regeneratingKeepsTheOldTokenForTheGracePeriodThenOnlyTheNewOne() {
        LinkMonitorConfig.Issued a = LinkMonitorConfig.empty().addSource("Orders", 60, 0, 30, 5, 10, 0);
        LinkMonitorConfig.Issued b = a.config().regenerateToken(a.sourceId(), 24 * HOUR, 10 * HOUR);
        LinkMonitor.Source s = b.config().source(a.sourceId());
        assertNotEquals(a.token(), b.token());
        assertTrue(s.accepts(b.token(), 11 * HOUR));
        assertTrue("old token still works inside the grace period", s.accepts(a.token(), 20 * HOUR));
        assertFalse("and stops afterwards", s.accepts(a.token(), 35 * HOUR));

        LinkMonitorConfig.Issued revoked = a.config().regenerateToken(a.sourceId(), 0, 10 * HOUR);
        assertFalse("grace 0 revokes the old token at once", revoked.config().source(a.sourceId()).accepts(a.token(), 10 * HOUR));
        assertTrue(revoked.config().source(a.sourceId()).accepts(revoked.token(), 10 * HOUR));
    }

    @Test public void editingKeepsTheTokenAndRemovingForgetsTheSource() {
        LinkMonitorConfig.Issued a = LinkMonitorConfig.empty().addSource("Orders", 60, 0, 30, 5, 10, 0);
        LinkMonitorConfig edited = a.config().updateSource(a.sourceId(), "Orders feed", false, 30, 120, 15, 3, 5);
        LinkMonitor.Source s = edited.source(a.sourceId());
        assertEquals("Orders feed", s.label());
        assertFalse(s.enabled());
        assertEquals(30, s.heartbeatSeconds());
        assertEquals(LinkTokens.hash(a.token()), s.tokenHash());
        assertFalse("a disabled source accepts nothing", s.accepts(a.token(), 0));
        assertTrue(edited.removeSource(a.sourceId()).sources.isEmpty());
    }

    @Test public void tokensAreRandomRecognisableAndHashedNotReversible() {
        String t1 = LinkTokens.generate(), t2 = LinkTokens.generate();
        assertNotEquals(t1, t2);
        assertTrue(t1.startsWith("opl_"));
        assertTrue(t1.length() >= 40);
        assertTrue(LinkTokens.matches(t1, LinkTokens.hash(t1)));
        assertFalse(LinkTokens.matches(t2, LinkTokens.hash(t1)));
        assertFalse(LinkTokens.matches(null, LinkTokens.hash(t1)));
        assertFalse(LinkTokens.matches(t1, ""));
    }

    @Test public void parsesSingleEventsBatchesAndRejectsUnknownTypes() {
        assertEquals(1, LinkIngestServer.parse("{\"type\":\"disconnected\",\"seq\":3,\"reason\":\"reset\\nby peer\"}").size());
        assertEquals("newlines in a reason are flattened",
                "reset by peer", LinkIngestServer.parse("{\"type\":\"disconnected\",\"reason\":\"reset\\nby peer\"}").get(0).reason());
        assertEquals(2, LinkIngestServer.parse("{\"events\":[{\"type\":\"connected\"},{\"type\":\"heartbeat\",\"state\":\"connected\"}]}").size());
        try { LinkIngestServer.parse("{\"type\":\"explode\"}"); fail("unknown type"); } catch (IllegalArgumentException expected) { }
        try { LinkIngestServer.parse("not json"); fail("bad json"); } catch (RuntimeException expected) { }
    }

    // ── The endpoint ──────────────────────────────────────────────────────

    private static File dataDirWith(LinkMonitorConfig cfg) throws Exception {
        File dir = Files.createTempDirectory("lm").toFile();
        cfg.save(dir);
        return dir;
    }

    private static LinkMonitorConfig plainConfig(int port, String tokenHash) {
        Properties p = new Properties();
        p.setProperty("port", String.valueOf(port));
        p.setProperty("bind", "127.0.0.1");
        p.setProperty("tls.enabled", "false");
        p.setProperty("source.a.label", "A to B");
        p.setProperty("source.a.tokenHash", tokenHash);
        return LinkMonitorConfig.parse(p);
    }

    @Test public void endToEndOverPlainHttpWhenExplicitlyAllowed() throws Exception {
        String token = LinkTokens.generate();
        File dir = dataDirWith(plainConfig(18943, LinkTokens.hash(token)));

        List<LinkMonitor.Alert> alerts = new ArrayList<>();
        AtomicLong now = new AtomicLong(1_000_000);
        try (LinkMonitorService svc = new LinkMonitorService(dir, a -> { synchronized (alerts) { alerts.add(a); } }, now::get)) {
            svc.refresh();
            int port = svc.listeningPort();
            assertTrue("endpoint should be listening", port > 0);
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + port;

            assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(base + "/socketLink/health")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals("no token", 401, post(http, base, null, "{\"type\":\"connected\"}").statusCode());
            assertEquals("wrong token", 401, post(http, base, "wrong-token-wrong-token", "{\"type\":\"connected\"}").statusCode());
            assertEquals("bad body", 400, post(http, base, token, "nope").statusCode());

            HttpResponse<String> ok = post(http, base, token,
                    "{\"events\":[{\"type\":\"connected\",\"seq\":1,\"instance\":\"x\"},{\"type\":\"disconnected\",\"seq\":2,\"instance\":\"x\",\"reason\":\"reset\"}]}");
            assertEquals(200, ok.statusCode());
            assertEquals("{\"accepted\":2,\"duplicates\":0}", ok.body());
            assertEquals("a retry of the same batch is recognised", "{\"accepted\":0,\"duplicates\":2}",
                    post(http, base, token,
                    "{\"events\":[{\"type\":\"connected\",\"seq\":1,\"instance\":\"x\"},{\"type\":\"disconnected\",\"seq\":2,\"instance\":\"x\"}]}").body());
            synchronized (alerts) {
                assertEquals(1, count(alerts, EventKind.LINK_DOWN));
                assertEquals("every accepted request is logged", 2, count(alerts, EventKind.LINK_REPORT));
                assertEquals("rejections are logged, repeats from one address and status once a minute", 2,
                        count(alerts, EventKind.LINK_REJECTED));
                String d = alerts.stream().filter(x -> x.kind() == EventKind.LINK_REPORT).findFirst().get().details();
                assertTrue(d, d.contains("disconnected") && d.contains("{\"accepted\":2,\"duplicates\":0}"));
            }

            List<LinkActivityLog.Entry> log = LinkActivityLog.readRecent(dir, 100);
            assertEquals("every request incl. the health check and rejections is in the activity log", 6, log.size());
            LinkActivityLog.Entry first = log.get(4);
            assertEquals(200, first.status());
            assertTrue(first.request(), first.request().contains("\"seq\":1"));
            assertEquals("A to B", first.label());
            assertEquals("2 messages: connected, disconnected", LinkActivityLog.summarize(first));
            assertTrue("token is never stored in full", !first.tokenHint().equals(token) && first.tokenHint().startsWith("opl_"));
            assertEquals(401, log.get(1).status());
            assertEquals("Health check", LinkActivityLog.summarize(log.get(0)));

            LinkStatusFile.Status live = LinkStatusFile.read(dir);
            assertTrue("status file written", live != null);
            assertTrue(live.listening());
            assertEquals("DISCONNECTED", live.sources().get(0).state());
            assertEquals(1, live.problems());
            assertTrue(live.fresh(now.get()));

            now.addAndGet(10 * 60_000L);          // ten quiet minutes
            svc.tick();
            LinkStatusFile.Status later = LinkStatusFile.read(dir);
            assertTrue("silent source counts as a problem", later.sources().get(0).silent());
            assertFalse("status goes stale when nobody refreshes it", later.fresh(now.get() + 10 * 60_000L));
            synchronized (alerts) {
                assertEquals(1, count(alerts, EventKind.LINK_SILENT));
            }
        }
    }

    @Test public void selfTestFindsTheEndpointAndMatchesTheCertificate() throws Exception {
        String token = LinkTokens.generate();
        Properties p = new Properties();
        p.setProperty("port", "18947");
        p.setProperty("bind", "127.0.0.1");
        p.setProperty("source.a.tokenHash", LinkTokens.hash(token));
        File dir = dataDirWith(LinkMonitorConfig.parse(p));
        try (LinkMonitorService svc = new LinkMonitorService(dir, a -> { }, System::currentTimeMillis)) {
            svc.refresh();
            assertEquals(18947, svc.listeningPort());
            LinkSelfTest.Result r = LinkSelfTest.run("localhost", 18947, true, LinkTls.certificateFile(dir));
            assertTrue(String.join("\n", r.lines()), r.ok());
            assertTrue(r.presentedFingerprint().equalsIgnoreCase(LinkTls.describe(dir).sha256Fingerprint()));
            LinkSelfTest.Result closed = LinkSelfTest.run("localhost", 18999, true, LinkTls.certificateFile(dir));
            assertTrue("a closed port is reported", !closed.ok());
        }
    }

    private static long count(List<LinkMonitor.Alert> l, EventKind k) {
        return l.stream().filter(x -> x.kind() == k).count();
    }

    @Test public void httpsIsTheDefault_withAGeneratedCertificateTheSenderCanTrust() throws Exception {
        String token = LinkTokens.generate();
        Properties p = new Properties();
        p.setProperty("port", "18945");
        p.setProperty("bind", "127.0.0.1");
        p.setProperty("source.a.tokenHash", LinkTokens.hash(token));
        File dir = dataDirWith(LinkMonitorConfig.parse(p));

        try (LinkMonitorService svc = new LinkMonitorService(dir, a -> { })) {
            svc.refresh();
            int port = svc.listeningPort();
            assertTrue(port > 0);
            File cer = LinkTls.certificateFile(dir);
            assertTrue("certificate exported for the sending server", cer.isFile());
            assertTrue(Files.readString(cer.toPath()).contains("BEGIN CERTIFICATE"));
            LinkTls.Material m = LinkTls.describe(dir);
            assertNotNull(m);
            assertTrue(m.names().contains("localhost"));
            assertTrue(m.names().contains("127.0.0.1"));
            assertTrue(m.sha256Fingerprint().matches("([0-9A-F]{2}:){31}[0-9A-F]{2}"));

            String base = "https://localhost:" + port;
            // A sender that trusts the exported certificate gets through.
            HttpClient trusting = HttpClient.newBuilder().sslContext(trusting(cer)).build();
            assertEquals(200, trusting.send(HttpRequest.newBuilder(URI.create(base + "/socketLink/health")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200, post(trusting, base, token, "{\"type\":\"connected\",\"seq\":1,\"instance\":\"x\"}").statusCode());
            assertEquals(401, post(trusting, base, "not-the-token-not-the-token", "{\"type\":\"connected\"}").statusCode());

            // One that does not trust it is refused at the TLS handshake, before any token is sent.
            try {
                HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + "/socketLink/health")).build(),
                        HttpResponse.BodyHandlers.ofString());
                fail("an untrusted certificate must be rejected");
            } catch (javax.net.ssl.SSLException expected) { }
            // And plain HTTP is not served at all.
            try {
                HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/socketLink/health")).build(),
                        HttpResponse.BodyHandlers.ofString());
                fail("plain HTTP must not work on the HTTPS port");
            } catch (java.io.IOException expected) { }
        }

        // A second start reuses the same certificate (the sender does not have to trust a new one).
        String before = LinkTls.describe(dir).sha256Fingerprint();
        LinkTls.ensure(dir, List.of());
        assertEquals(before, LinkTls.describe(dir).sha256Fingerprint());
        // Adding a name the certificate must cover creates a new one.
        LinkTls.ensure(dir, List.of("serverc.corp.example"));
        assertNotEquals(before, LinkTls.describe(dir).sha256Fingerprint());
        assertTrue(LinkTls.describe(dir).names().contains("serverc.corp.example"));
    }

    @Test public void aRegeneratedTokenTakesEffectWithoutARestart() throws Exception {
        LinkMonitorConfig.Issued first = LinkMonitorConfig.empty().addSource("Orders", 60, 0, 30, 5, 10, 0);
        Properties base = new Properties();
        LinkMonitorConfig cfg = new LinkMonitorConfig(18946, "127.0.0.1", false, null, null, List.of(), first.config().sources, List.of());
        File dir = dataDirWith(cfg);
        try (LinkMonitorService svc = new LinkMonitorService(dir, a -> { })) {
            svc.refresh();
            String url = "http://127.0.0.1:" + svc.listeningPort();
            HttpClient http = HttpClient.newHttpClient();
            assertEquals(200, post(http, url, first.token(), "{\"type\":\"heartbeat\",\"state\":\"connected\"}").statusCode());

            LinkMonitorConfig.Issued second = cfg.regenerateToken(first.sourceId(), 0, System.currentTimeMillis());
            second.config().save(dir);
            new File(dir, LinkMonitorConfig.FILE_NAME).setLastModified(System.currentTimeMillis() + 5000);   // as a later edit would
            svc.refresh();
            assertEquals("the new token is accepted", 200, post(http, url, second.token(), "{\"type\":\"heartbeat\",\"state\":\"connected\"}").statusCode());
            assertEquals("the revoked one is not", 401, post(http, url, first.token(), "{\"type\":\"heartbeat\",\"state\":\"connected\"}").statusCode());
        }
    }

    private static javax.net.ssl.SSLContext trusting(File cer) throws Exception {
        Certificate cert;
        try (InputStream in = Files.newInputStream(cer.toPath())) {
            cert = CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        KeyStore ts = KeyStore.getInstance(KeyStore.getDefaultType());
        ts.load(null, null);
        ts.setCertificateEntry("link", cert);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    private static HttpResponse<String> post(HttpClient http, String base, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/socketLink/events"))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
