package service;

import util.CryptoUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * The HTTPS certificate of the socket-link endpoint. On first use a self-signed certificate is created with the JDK's
 * {@code keytool}, valid for this machine's names and addresses (plus any the operator adds), so end users do not have
 * to create a keystore. The public part is exported as {@code link-server.cer}: install it as trusted on the sending
 * server (see docs/socket-link-api.md). It is regenerated if the names it must cover change or it is about to expire;
 * the sender has to trust the new file then.
 *
 * <p>Files, all in the data directory: {@code link-tls.p12} (key pair), {@code link-tls.pass} (its password, encrypted
 * with the installation key), {@code link-tls.names} (names the certificate covers), {@code link-server.cer}.
 */
public final class LinkTls {

    private static final Logger log = Logger.getLogger(LinkTls.class.getName());
    private static final String ALIAS = "opstool-link";
    private static final int VALIDITY_DAYS = 3650;
    private static final long RENEW_BEFORE_MILLIS = 30L * 24 * 60 * 60 * 1000;

    private LinkTls() {
    }

    public static File certificateFile(File dataDir) {
        return new File(dataDir, "link-server.cer");
    }

    /**
     * Returns the existing certificate material, creating or renewing it when needed.
     */
    public static synchronized Material ensure(File dataDir, List<String> extraNames) throws Exception {
        CryptoUtil.configure(dataDir);
        File ks = new File(dataDir, "link-tls.p12");
        File pass = new File(dataDir, "link-tls.pass");
        File namesFile = new File(dataDir, "link-tls.names");
        File cer = certificateFile(dataDir);
        List<String> names = hostNames(extraNames);

        if (ks.isFile() && pass.isFile() && cer.isFile()) {
            try {
                String pw = CryptoUtil.decrypt(Files.readString(pass.toPath(), StandardCharsets.UTF_8).trim());
                String saved = namesFile.isFile() ? Files.readString(namesFile.toPath(), StandardCharsets.UTF_8).trim() : "";
                X509Certificate c = certificate(ks, pw);
                boolean namesOk = saved.equals(String.join(",", names));
                boolean fresh = c.getNotAfter().getTime() - System.currentTimeMillis() > RENEW_BEFORE_MILLIS;
                if (namesOk && fresh) return material(ks, pw, cer, c, names);
                log.info("Regenerating the HTTPS certificate of the socket-link endpoint ("
                        + (namesOk ? "it is about to expire" : "the names it must cover changed") + "). "
                        + "Install the new link-server.cer on the sending server.");
            } catch (Exception e) {
                log.warning("Existing socket-link certificate unusable (" + e.getMessage() + "); creating a new one.");
            }
        }

        String pw = randomPassword();
        Files.deleteIfExists(ks.toPath());
        Files.deleteIfExists(cer.toPath());
        String host = names.get(0);
        StringBuilder san = new StringBuilder();
        for (String n : names) {
            if (san.length() > 0) san.append(',');
            san.append(isIp(n) ? "ip:" : "dns:").append(n);
        }
        runKeytool(pw, "-genkeypair", "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "2048", "-sigalg", "SHA256withRSA",
                "-validity", String.valueOf(VALIDITY_DAYS), "-dname", "CN=" + host + ", O=OpsTransferTool",
                "-ext", "san=" + san, "-storetype", "PKCS12", "-keystore", ks.getAbsolutePath(),
                "-storepass:env", "OPSTOOL_KS_PW", "-keypass:env", "OPSTOOL_KS_PW");
        runKeytool(pw, "-exportcert", "-alias", ALIAS, "-keystore", ks.getAbsolutePath(), "-storepass:env", "OPSTOOL_KS_PW",
                "-rfc", "-file", cer.getAbsolutePath());
        Files.writeString(pass.toPath(), CryptoUtil.encrypt(pw), StandardCharsets.UTF_8);
        Files.writeString(namesFile.toPath(), String.join(",", names), StandardCharsets.UTF_8);
        return material(ks, pw, cer, certificate(ks, pw), names);
    }

    /**
     * What the certificate file currently in the data folder says, for display; null when none exists yet.
     */
    public static Material describe(File dataDir) {
        File cer = certificateFile(dataDir);
        if (!cer.isFile()) return null;
        try (InputStream in = Files.newInputStream(cer.toPath())) {
            X509Certificate c = (X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(in);
            File namesFile = new File(dataDir, "link-tls.names");
            List<String> names = namesFile.isFile()
                    ? List.of(Files.readString(namesFile.toPath(), StandardCharsets.UTF_8).trim().split(",")) : List.of();
            return material(new File(dataDir, "link-tls.p12"), "", cer, c, names);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The machine's own names and addresses, then the operator's extras, without duplicates; the first is the CN.
     */
    static List<String> hostNames(List<String> extra) {
        Set<String> out = new LinkedHashSet<>();
        try {
            InetAddress me = InetAddress.getLocalHost();
            out.add(me.getHostName());
            String fqdn = me.getCanonicalHostName();
            if (fqdn != null && !isIp(fqdn)) out.add(fqdn);
        } catch (Exception ignored) {
        }
        out.add("localhost");
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (InetAddress a : Collections.list(nic.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address && !a.isLinkLocalAddress()) out.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) {
        }
        out.add("127.0.0.1");
        if (extra != null) for (String e : extra) if (e != null && !e.isBlank()) out.add(e.trim());
        out.removeIf(n -> n == null || n.isBlank());
        return new ArrayList<>(out);
    }

    static boolean isIp(String s) {
        return s.matches("\\d{1,3}(\\.\\d{1,3}){3}") || (s.contains(":") && s.matches("[0-9a-fA-F:]+"));
    }

    private static String randomPassword() {
        String alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 28; i++) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        return sb.toString();
    }

    private static X509Certificate certificate(File ks, String pw) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(ks.toPath())) {
            store.load(in, pw.toCharArray());
        }
        return (X509Certificate) store.getCertificate(ALIAS);
    }

    private static Material material(File ks, String pw, File cer, X509Certificate c, List<String> names) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(c.getEncoded());
        StringBuilder fp = new StringBuilder();
        for (int i = 0; i < d.length; i++) fp.append(i == 0 ? "" : ":").append(String.format("%02X", d[i]));
        return new Material(ks.getAbsolutePath(), pw, cer, fp.toString(), c.getNotAfter(), names);
    }

    private static void runKeytool(String password, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(keytoolPath());
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("OPSTOOL_KS_PW", password);   // not on the command line, so not visible in the process list
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("keytool timed out");
        }
        if (p.exitValue() != 0) throw new IOException("keytool failed: " + out.trim());
    }

    private static String keytoolPath() {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path bundled = Path.of(System.getProperty("java.home"), "bin", win ? "keytool.exe" : "keytool");
        return Files.isRegularFile(bundled) ? bundled.toString() : "keytool";
    }

    /**
     * What the endpoint needs, and what the operator needs to give the sender.
     */
    public record Material(String keystorePath, String password, File certificate,
                           String sha256Fingerprint, Date notAfter, List<String> names) {
    }
}
