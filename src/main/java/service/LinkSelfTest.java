package service;

import javax.net.ssl.*;
import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks the endpoint from this PC the way a sender would: is the port answering, which certificate does it present,
 * is that the certificate in the data folder, and does the health check pass when that certificate is trusted.
 * Used by the API panel's "Test now" buttons.
 */
public final class LinkSelfTest {

    private LinkSelfTest() {
    }

    public static Result run(String host, int port, boolean tls, File cerFile) {
        List<String> out = new ArrayList<>();
        String presented = null;
        boolean ok = true;
        String base = (tls ? "https://" : "http://") + host + ":" + port;
        out.add("Testing " + base);
        if (tls) {
            try {
                presented = presentedFingerprint(host, port);
                out.add("OK    The endpoint answered and presented a certificate.");
                out.add("      SHA-256 seen on the wire: " + presented);
            } catch (Exception e) {
                out.add("FAIL  Could not connect: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                out.add("      Check that the background service is running, the port is right and the firewall allows it.");
                return new Result(false, out, null);
            }
            String fileFp = cerFile != null && cerFile.isFile() ? fingerprintOf(cerFile) : null;
            if (fileFp == null) {
                out.add("WARN  No link-server.cer in the data folder to compare with.");
            } else if (fileFp.equalsIgnoreCase(presented)) {
                out.add("OK    It is the same certificate as the exported link-server.cer.");
            } else {
                out.add("FAIL  The certificate on the wire is NOT the one in link-server.cer. Something else is answering on this port, "
                        + "or the certificate was renewed: export it again and install the new one on the sender.");
                ok = false;
            }
        }
        try {
            HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
            if (tls && cerFile != null && cerFile.isFile()) b.sslContext(trusting(cerFile));
            HttpResponse<String> r = b.build().send(HttpRequest.newBuilder(URI.create(base + "/socketLink/health"))
                    .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) out.add("OK    GET /socketLink/health -> 200 " + r.body().trim()
                    + (tls ? " (certificate trusted, name matches)" : ""));
            else {
                out.add("FAIL  GET /socketLink/health -> " + r.statusCode());
                ok = false;
            }
        } catch (Exception e) {
            String m = String.valueOf(e.getMessage());
            out.add("FAIL  Health check failed: " + e.getClass().getSimpleName() + ": " + m);
            if (m.contains("No subject alternative") || m.contains("doesn't match") || m.contains("does not match")) {
                out.add("      The name '" + host + "' is not in the certificate. Add it under Port & names... and install the new certificate.");
            }
            ok = false;
        }
        return new Result(ok, out, presented);
    }

    /**
     * Opens a TLS connection that accepts any certificate only to read which one the server presents.
     */
    static String presentedFingerprint(String host, int port) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {
            }

            public void checkServerTrusted(X509Certificate[] c, String a) {
            }

            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, null);
        try (SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket()) {
            s.connect(new InetSocketAddress(host, port), 5000);
            s.setSoTimeout(5000);
            s.startHandshake();
            Certificate[] chain = s.getSession().getPeerCertificates();
            return sha256(chain[0].getEncoded());
        }
    }

    static SSLContext trusting(File cer) throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        try (InputStream in = Files.newInputStream(cer.toPath())) {
            ks.setCertificateEntry("link", CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    static String fingerprintOf(File cer) {
        try (InputStream in = Files.newInputStream(cer.toPath())) {
            return sha256(CertificateFactory.getInstance("X.509").generateCertificate(in).getEncoded());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Upper-case hex pairs separated by colons, the way certificate viewers show it.
     */
    static String sha256(byte[] der) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(der);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02X", d[i]));
        }
        return sb.toString();
    }

    public record Result(boolean ok, List<String> lines, String presentedFingerprint) {
    }
}
