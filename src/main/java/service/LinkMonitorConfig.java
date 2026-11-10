package service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Settings of the socket-link monitor, kept in {@code <dataDir>/socket-monitor.properties}. The file is written by the
 * Settings screen (Socket link monitor); the Daemon re-reads it when it changes, so no restart is needed.
 *
 * <pre>
 * port=8443
 * bind=0.0.0.0
 * tls.enabled=true                      # HTTPS is the default; false is for a closed test network only
 * tls.hostnames=serverc.corp,10.0.0.5   # extra names/IPs the certificate must be valid for
 * tls.keystore=                         # optional: your own PKCS12 certificate instead of the generated one
 * tls.password=
 *
 * source.orders.label=Server A to Server B order feed
 * source.orders.enabled=true
 * source.orders.tokenHash=&lt;sha-256 of the token&gt;
 * source.orders.prevTokenHash=&lt;hash of the previous token&gt;
 * source.orders.prevValidUntil=&lt;epoch millis&gt;
 * source.orders.heartbeatSeconds=60
 * source.orders.silentAfterSeconds=180
 * source.orders.repeatMinutes=30
 * source.orders.flapCount=5
 * source.orders.flapWindowMinutes=10
 * </pre>
 * <p>
 * A plain {@code source.<id>.token} (an older format, or one typed by hand) is still accepted and hashed in memory; the
 * next save from the Settings screen replaces it with its hash.
 */
public final class LinkMonitorConfig {

    public static final String FILE_NAME = "socket-monitor.properties";
    public static final int DEFAULT_PORT = 8443;
    /**
     * How long the previous token keeps working after a new one is issued, unless the operator chooses otherwise.
     */
    public static final long DEFAULT_GRACE_MILLIS = 24L * 60 * 60 * 1000;

    public final int port;
    public final String bind;
    public final boolean tlsEnabled;
    public final String tlsKeystore;
    public final String tlsPassword;
    public final List<String> tlsHostnames;
    public final List<LinkMonitor.Source> sources;
    public final List<String> problems;

    public LinkMonitorConfig(int port, String bind, boolean tlsEnabled, String tlsKeystore, String tlsPassword,
                             List<String> tlsHostnames, List<LinkMonitor.Source> sources, List<String> problems) {
        this.port = port;
        this.bind = bind;
        this.tlsEnabled = tlsEnabled;
        this.tlsKeystore = tlsKeystore;
        this.tlsPassword = tlsPassword;
        this.tlsHostnames = List.copyOf(tlsHostnames);
        this.sources = List.copyOf(sources);
        this.problems = List.copyOf(problems);
    }

    public static LinkMonitorConfig empty() {
        return new LinkMonitorConfig(DEFAULT_PORT, "0.0.0.0", true, null, null, List.of(), List.of(), List.of());
    }

    /**
     * @return the settings, or {@link #empty()} when the file is absent. Never throws.
     */
    public static LinkMonitorConfig load(File dataDir) {
        File f = new File(dataDir, FILE_NAME);
        if (!f.isFile()) return empty();
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(f.toPath())) {
            p.load(in);
        } catch (IOException e) {
            return new LinkMonitorConfig(0, "0.0.0.0", true, null, null, List.of(), List.of(),
                    List.of("cannot read " + f + ": " + e.getMessage()));
        }
        return parse(p);
    }

    static LinkMonitorConfig parse(Properties p) {
        List<String> problems = new ArrayList<>();
        int port = intOf(p.getProperty("port"), DEFAULT_PORT);
        if (port < 1 || port > 65535) {
            problems.add("port must be 1-65535");
            port = 0;
        }
        String bind = trimmed(p.getProperty("bind"), "0.0.0.0");
        boolean tls = !"false".equalsIgnoreCase(trimmed(p.getProperty("tls.enabled"), "true"));
        List<String> hostnames = new ArrayList<>();
        for (String h : trimmed(p.getProperty("tls.hostnames"), "").split("[,;\\s]+"))
            if (!h.isEmpty()) hostnames.add(h);

        TreeSet<String> ids = new TreeSet<>();
        for (String k : p.stringPropertyNames()) {
            if (!k.startsWith("source.")) continue;
            int dot = k.indexOf('.', 7);
            if (dot > 7) ids.add(k.substring(7, dot));
        }
        List<LinkMonitor.Source> sources = new ArrayList<>();
        TreeSet<String> hashes = new TreeSet<>();
        for (String id : ids) {
            String pre = "source." + id + ".";
            String hash = trimmed(p.getProperty(pre + "tokenHash"), "");
            String plain = trimmed(p.getProperty(pre + "token"), "");
            if (hash.isEmpty() && !plain.isEmpty()) {
                if (plain.length() < 16) {
                    problems.add("source " + id + ": token shorter than 16 characters, source ignored");
                    continue;
                }
                hash = LinkTokens.hash(plain);
                problems.add("source " + id + ": uses a plain-text token; regenerate it in Settings to store only its hash");
            }
            if (hash.isEmpty()) {
                problems.add("source " + id + ": no token, source ignored");
                continue;
            }
            if (!hashes.add(hash)) {
                problems.add("source " + id + ": same token as another source, source ignored");
                continue;
            }
            sources.add(new LinkMonitor.Source(id, trimmed(p.getProperty(pre + "label"), id),
                    !"false".equalsIgnoreCase(trimmed(p.getProperty(pre + "enabled"), "true")),
                    hash, trimmed(p.getProperty(pre + "prevTokenHash"), ""),
                    longOf(p.getProperty(pre + "prevValidUntil"), 0), longOf(p.getProperty(pre + "tokenIssuedAt"), 0),
                    intOf(p.getProperty(pre + "heartbeatSeconds"), 60),
                    intOf(p.getProperty(pre + "silentAfterSeconds"), 0),
                    intOf(p.getProperty(pre + "repeatMinutes"), 30),
                    intOf(p.getProperty(pre + "flapCount"), 5),
                    intOf(p.getProperty(pre + "flapWindowMinutes"), 10)));
        }
        return new LinkMonitorConfig(port, bind, tls, trimmed(p.getProperty("tls.keystore"), null),
                trimmed(p.getProperty("tls.password"), null), hostnames, sources, problems);
    }

    private static int intOf(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long longOf(String s, long def) {
        if (s == null) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ── Loading and saving ────────────────────────────────────────────────

    private static String trimmed(String s, String def) {
        if (s == null || s.trim().isEmpty()) return def;
        return s.trim();
    }

    /**
     * The monitor serves only when a port is set and at least one source is enabled.
     */
    public boolean enabled() {
        if (port <= 0) return false;
        for (LinkMonitor.Source s : sources) if (s.enabled()) return true;
        return false;
    }

    /**
     * Same listener settings (port, address, HTTPS)? A change means the endpoint must be restarted.
     */
    public boolean sameListener(LinkMonitorConfig o) {
        return o != null && port == o.port && bind.equals(o.bind) && tlsEnabled == o.tlsEnabled
                && java.util.Objects.equals(tlsKeystore, o.tlsKeystore)
                && java.util.Objects.equals(tlsPassword, o.tlsPassword)
                && tlsHostnames.equals(o.tlsHostnames);
    }

    // ── Changing sources (each returns the new settings; the caller saves) ─────

    public LinkMonitorConfig withSources(List<LinkMonitor.Source> next) {
        return new LinkMonitorConfig(port, bind, tlsEnabled, tlsKeystore, tlsPassword, tlsHostnames, next, problems);
    }

    public LinkMonitorConfig withEndpoint(int newPort, List<String> hostnames) {
        return new LinkMonitorConfig(newPort, bind, tlsEnabled, tlsKeystore, tlsPassword, hostnames, sources, problems);
    }

    /**
     * Writes the settings atomically (temp file, then rename), so the Daemon never reads half a file.
     */
    public void save(File dataDir) throws IOException {
        Properties p = new Properties();
        p.setProperty("port", String.valueOf(port));
        p.setProperty("bind", bind);
        p.setProperty("tls.enabled", String.valueOf(tlsEnabled));
        if (!tlsHostnames.isEmpty()) p.setProperty("tls.hostnames", String.join(",", tlsHostnames));
        if (tlsKeystore != null) p.setProperty("tls.keystore", tlsKeystore);
        if (tlsPassword != null) p.setProperty("tls.password", tlsPassword);
        for (LinkMonitor.Source s : sources) {
            String pre = "source." + s.id() + ".";
            p.setProperty(pre + "label", s.label());
            p.setProperty(pre + "enabled", String.valueOf(s.enabled()));
            p.setProperty(pre + "tokenHash", s.tokenHash());
            if (!s.prevTokenHash().isEmpty()) {
                p.setProperty(pre + "prevTokenHash", s.prevTokenHash());
                p.setProperty(pre + "prevValidUntil", String.valueOf(s.prevValidUntil()));
            }
            p.setProperty(pre + "tokenIssuedAt", String.valueOf(s.tokenIssuedAt()));
            p.setProperty(pre + "heartbeatSeconds", String.valueOf(s.heartbeatSeconds()));
            p.setProperty(pre + "silentAfterSeconds", String.valueOf(s.silentAfterSeconds()));
            p.setProperty(pre + "repeatMinutes", String.valueOf(s.repeatMinutes()));
            p.setProperty(pre + "flapCount", String.valueOf(s.flapCount()));
            p.setProperty(pre + "flapWindowMinutes", String.valueOf(s.flapWindowMinutes()));
        }
        File target = new File(dataDir, FILE_NAME);
        File tmp = new File(dataDir, FILE_NAME + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp.toPath())) {
            p.store(out, "Socket link monitor - managed by Settings > Socket link monitor. Tokens are stored as hashes only.");
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Lower-case letters, digits and dashes; made unique among existing sources.
     */
    public String newSourceId(String label) {
        String base = label == null ? "" : label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (base.isEmpty()) base = "source";
        if (base.length() > 40) base = base.substring(0, 40);
        String id = base;
        for (int n = 2; hasSource(id); n++) id = base + "-" + n;
        return id;
    }

    public boolean hasSource(String id) {
        for (LinkMonitor.Source s : sources) if (s.id().equals(id)) return true;
        return false;
    }

    public LinkMonitor.Source source(String id) {
        for (LinkMonitor.Source s : sources) if (s.id().equals(id)) return s;
        return null;
    }

    /**
     * Adds a source with a freshly generated token.
     */
    public Issued addSource(String label, int heartbeatSeconds, int silentAfterSeconds, int repeatMinutes,
                            int flapCount, int flapWindowMinutes, long now) {
        String id = newSourceId(label);
        String token = LinkTokens.generate();
        List<LinkMonitor.Source> next = new ArrayList<>(sources);
        next.add(new LinkMonitor.Source(id, label, true, LinkTokens.hash(token), "", 0, now,
                heartbeatSeconds, silentAfterSeconds, repeatMinutes, flapCount, flapWindowMinutes));
        return new Issued(withSources(next), id, token);
    }

    /**
     * Issues a new token for a source. The old one keeps working for {@code graceMillis} (0 = revoked at once), so the
     * sending application can be updated without a gap in monitoring.
     */
    public Issued regenerateToken(String id, long graceMillis, long now) {
        LinkMonitor.Source old = source(id);
        if (old == null) throw new IllegalArgumentException("unknown source " + id);
        String token = LinkTokens.generate();
        boolean keepOld = graceMillis > 0;
        LinkMonitor.Source fresh = new LinkMonitor.Source(old.id(), old.label(), old.enabled(),
                LinkTokens.hash(token), keepOld ? old.tokenHash() : "", keepOld ? now + graceMillis : 0, now,
                old.heartbeatSeconds(), old.silentAfterSeconds(), old.repeatMinutes(), old.flapCount(), old.flapWindowMinutes());
        return new Issued(replace(fresh), id, token);
    }

    /**
     * Changes label, thresholds or the enabled flag; the token is untouched.
     */
    public LinkMonitorConfig updateSource(String id, String label, boolean enabled, int heartbeatSeconds,
                                          int silentAfterSeconds, int repeatMinutes, int flapCount, int flapWindowMinutes) {
        LinkMonitor.Source old = source(id);
        if (old == null) throw new IllegalArgumentException("unknown source " + id);
        return replace(new LinkMonitor.Source(id, label, enabled, old.tokenHash(), old.prevTokenHash(), old.prevValidUntil(),
                old.tokenIssuedAt(), heartbeatSeconds, silentAfterSeconds, repeatMinutes, flapCount, flapWindowMinutes));
    }

    public LinkMonitorConfig removeSource(String id) {
        List<LinkMonitor.Source> next = new ArrayList<>(sources);
        next.removeIf(s -> s.id().equals(id));
        return withSources(next);
    }

    private LinkMonitorConfig replace(LinkMonitor.Source fresh) {
        List<LinkMonitor.Source> next = new ArrayList<>();
        for (LinkMonitor.Source s : sources) next.add(s.id().equals(fresh.id()) ? fresh : s);
        return withSources(next);
    }

    /**
     * A new settings object plus the token that was just issued (shown to the operator once, never stored).
     */
    public record Issued(LinkMonitorConfig config, String sourceId, String token) {
    }
}
