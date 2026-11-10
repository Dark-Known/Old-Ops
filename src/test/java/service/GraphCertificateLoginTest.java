package service;

import com.sun.net.httpserver.HttpServer;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

/**
 * Uses a throw-away self-signed certificate (keytool) and fake local Microsoft endpoints.
 */
public class GraphCertificateLoginTest {

    private static final char[] PW = "test-pw".toCharArray();
    private static Path pfx;

    @BeforeClass
    public static void makeCertificate() throws Exception {
        pfx = Files.createTempDirectory("opstool-cert").resolve("test.pfx");
        Process p = new ProcessBuilder(System.getProperty("java.home") + "/bin/keytool", "-genkeypair", "-alias", "t",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=opstool-test",
                "-storetype", "PKCS12", "-keystore", pfx.toString(), "-storepass", "test-pw", "-keypass", "test-pw")
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());
    }

    private static Map<String, String> form(String body) {
        Map<String, String> m = new HashMap<>();
        for (String kv : body.split("&")) {
            int i = kv.indexOf('=');
            m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    @Test
    public void assertionIsSignedByTheCertificateAndAccepted() throws Exception {
        List<Map<String, String>> requests = new CopyOnWriteArrayList<>();
        List<String> verdicts = new CopyOnWriteArrayList<>();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(pfx)) {
            ks.load(in, PW);
        }
        X509Certificate cert = (X509Certificate) ks.getCertificate("t");

        HttpServer fake = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        fake.createContext("/", ex -> {
            Map<String, String> f = form(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(f);
            String[] parts = f.get("client_assertion").split("\\.");
            try {
                Signature sig = Signature.getInstance("SHA256withRSA");
                sig.initVerify(cert.getPublicKey());
                sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
                boolean ok = sig.verify(Base64.getUrlDecoder().decode(parts[2]));
                verdicts.add(ok + "|" + new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)
                        + "|" + new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8) + "|" + ex.getRequestURI());
            } catch (Exception e) {
                verdicts.add("error " + e);
            }
            byte[] out = "{\"access_token\":\"APP-TOKEN\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        fake.start();
        try {
            OAuth2TokenService svc = new OAuth2TokenService(Files.createTempDirectory("opstool-tok").resolve("oauth"));
            svc.setAuthBaseForTesting("http://127.0.0.1:" + fake.getAddress().getPort() + "/");

            String token = svc.getAppOnlyAccessToken("tenant-1", "client-1", pfx, PW, "https://graph.microsoft.com/.default");
            assertEquals("APP-TOKEN", token);
            assertEquals(1, requests.size());
            Map<String, String> r = requests.get(0);
            assertEquals("client_credentials", r.get("grant_type"));
            assertEquals("client-1", r.get("client_id"));
            assertEquals("urn:ietf:params:oauth:client-assertion-type:jwt-bearer", r.get("client_assertion_type"));
            assertEquals("https://graph.microsoft.com/.default", r.get("scope"));
            assertNull("no secret and no refresh token involved", r.get("refresh_token"));

            String v = verdicts.get(0);
            assertTrue("signature must verify against the certificate: " + v, v.startsWith("true|"));
            assertTrue(v, v.contains("\"alg\":\"RS256\"") && v.contains("x5t#S256"));
            assertTrue(v, v.contains("\"iss\":\"client-1\"") && v.contains("\"sub\":\"client-1\""));
            assertTrue("audience is the token endpoint: " + v, v.contains("/tenant-1/oauth2/v2.0/token\""));
            assertTrue(v, v.endsWith("/tenant-1/oauth2/v2.0/token"));

            // cached: a second call does not hit the endpoint again
            assertEquals("APP-TOKEN", svc.getAppOnlyAccessToken("tenant-1", "client-1", pfx, PW, "https://graph.microsoft.com/.default"));
            assertEquals(1, requests.size());
        } finally {
            fake.stop(0);
        }
    }

    @Test
    public void wrongPfxPasswordAndErrorResponsesAreReported() throws Exception {
        OAuth2TokenService svc = new OAuth2TokenService(Files.createTempDirectory("opstool-tok").resolve("oauth"));
        try {
            svc.getAppOnlyAccessToken("t", "c", pfx, "wrong".toCharArray(), "s");
            fail();
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("client assertion"));
        }
        HttpServer fake = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        fake.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] out = "{\"error\":\"invalid_client\",\"error_description\":\"AADSTS700027: bad assertion\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(401, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        fake.start();
        try {
            svc.setAuthBaseForTesting("http://127.0.0.1:" + fake.getAddress().getPort());
            svc.getAppOnlyAccessToken("t", "c", pfx, PW, "s");
            fail();
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("AADSTS700027"));
        } finally {
            fake.stop(0);
        }
    }

    @Test
    public void appOnlyMailboxAddressingUsesUsersPath() throws Exception {
        List<String> paths = new CopyOnWriteArrayList<>();
        List<String> auth = new CopyOnWriteArrayList<>();
        HttpServer fake = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        fake.createContext("/", ex -> {
            paths.add(ex.getRequestURI().getRawPath());
            auth.add(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] out = "{\"value\":[]}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        fake.start();
        try {
            GraphMailService base = new GraphMailService();
            assertEquals("/me", base.userPath());
            base.setGraphBaseForTesting("http://127.0.0.1:" + fake.getAddress().getPort());
            GraphMailService app = base.forMailbox("ops@contoso.com");
            assertEquals("/users/ops%40contoso.com", app.userPath());
            app.fetchMessages("APP-TOKEN", "INBOX", "UNSEEN", util.MailFetchMode.BODY_ONLY, 5, 0, l -> {
            });
            assertFalse(paths.isEmpty());
            for (String p : paths) assertTrue(p, p.startsWith("/users/ops%40contoso.com/"));
            for (String a : auth) assertEquals("Bearer APP-TOKEN", a);
        } finally {
            fake.stop(0);
        }
    }
}
