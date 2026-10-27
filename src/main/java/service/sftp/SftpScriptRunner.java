package service.sftp;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs the small WinSCP-style command scripts that {@code TransferService} writes
 * ({@code option}, {@code open sftp://user@host/}, {@code get}, {@code put}, {@code mkdir},
 * {@code rm}, {@code close}, {@code exit}) on an {@link SftpClient} instead of WinSCP.com, so the
 * existing planning/batching code keeps working while the transfer engine is pure Java.
 *
 * <p>Semantics kept from WinSCP: {@code option batch abort} stops at the first failing command
 * (the run fails), {@code option batch continue} logs the failure and goes on (the run still
 * succeeds). {@code get}/{@code put} keep timestamps. A local destination that is an existing
 * directory, or a remote destination that is an existing directory, receives the file under its
 * original name.
 */
public final class SftpScriptRunner {

    /** Opens connections; replaceable in tests. */
    public interface Connector {
        SftpClient connect(String host, int port, String user, String password) throws IOException;
    }

    private final Connector connector;
    private final Consumer<SftpClient> onOpen;
    private final Consumer<SftpClient> onClose;

    public SftpScriptRunner(Connector connector, Consumer<SftpClient> onOpen, Consumer<SftpClient> onClose) {
        this.connector = connector;
        this.onOpen = onOpen;
        this.onClose = onClose;
    }

    /** Result of running a script. */
    public record Outcome(boolean ok, String host, String user) {}

    public Outcome run(List<String> script, String password, Consumer<String> log) throws IOException {
        boolean abortOnError = true;
        SftpClient client = null;
        String host = null, user = null;
        int failures = 0;
        try {
            for (String raw : script) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Transfer cancelled");
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                List<String> t = tokens(line);
                String cmd = t.get(0).toLowerCase();
                try {
                    switch (cmd) {
                        case "option" -> {
                            if (t.size() >= 3 && t.get(1).equalsIgnoreCase("batch")) {
                                abortOnError = !t.get(2).equalsIgnoreCase("continue");
                            }
                        }
                        case "open" -> {
                            String[] hu = parseOpen(t.get(1));
                            host = hu[1];
                            user = hu[0];
                            try {
                                client = connector.connect(hu[1], hu[2].isEmpty() ? 22 : Integer.parseInt(hu[2]), hu[0], password);
                            } catch (IOException connectFailed) {
                                throw new ConnectFailed(connectFailed);
                            }
                            if (onOpen != null) onOpen.accept(client);
                        }
                        case "get" -> {
                            need(client);
                            String remote = unescapeRemote(t.get(1));
                            File local = new File(unescapeLocal(t.get(2)));
                            RemoteEntry re = client.stat(remote);
                            if (re == null) throw new IOException("Remote file not found: " + remote);
                            if (local.isDirectory()) local = new File(local, re.name());
                            client.download(remote, local);
                            log.accept("[INFO] Downloaded " + remote + " (" + re.size() + " bytes)");
                        }
                        case "put" -> {
                            need(client);
                            File local = new File(unescapeLocal(t.get(1)));
                            String remote = unescapeRemote(t.get(2));
                            if (!local.isFile()) throw new IOException("Local file not found: " + local);
                            RemoteEntry existing = client.stat(remote);
                            if (existing != null && existing.directory()) {
                                remote = remote.endsWith("/") ? remote + local.getName() : remote + "/" + local.getName();
                            }
                            client.upload(local, remote);
                            log.accept("[INFO] Uploaded " + local.getName() + " -> " + remote + " (" + local.length() + " bytes)");
                        }
                        case "mkdir" -> {
                            need(client);
                            client.mkdirs(unescapeRemote(t.get(1)));
                        }
                        case "rm" -> {
                            need(client);
                            String p = unescapeRemote(t.get(1));
                            if (!client.delete(p)) throw new IOException("Remote file not found: " + p);
                        }
                        case "mv" -> {
                            need(client);
                            String from = unescapeRemote(t.get(1)), to = unescapeRemote(t.get(2));
                            client.rename(from, to);
                            log.accept("[INFO] Moved " + from + " -> " + to);
                        }
                        case "close" -> {
                            if (client != null) { closeClient(client); client = null; }
                        }
                        case "exit" -> { return new Outcome(abortOnError ? failures == 0 : true, host, user); }
                        default -> throw new IOException("Unsupported script command: " + cmd);
                    }
                } catch (ConnectFailed cf) {
                    // No session at all: let the caller classify (auth / network / timeout).
                    throw (IOException) cf.getCause();
                } catch (IOException | RuntimeException e) {
                    failures++;
                    if (abortOnError) {
                        log.accept("[ERROR] " + cmd + " failed: " + e.getMessage());
                        return new Outcome(false, host, user);
                    }
                    log.accept("[WARN] " + cmd + " failed (continuing): " + e.getMessage());
                }
            }
            return new Outcome(abortOnError ? failures == 0 : true, host, user);
        } finally {
            if (client != null) closeClient(client);
        }
    }

    /** Marks an {@code open} failure so it is rethrown instead of being treated as a command failure. */
    private static final class ConnectFailed extends RuntimeException {
        ConnectFailed(IOException cause) { super(cause); }
    }

    private void closeClient(SftpClient c) {
        try { c.close(); } finally { if (onClose != null) onClose.accept(c); }
    }

    private static void need(SftpClient c) throws IOException {
        if (c == null) throw new IOException("No open session (script has no 'open' line before this command)");
    }

    /** {@code sftp://user@host[:port]/ ...} -> [user, host, port-or-empty]. The password is never in the URL. */
    static String[] parseOpen(String url) throws IOException {
        String rest = url.startsWith("sftp://") ? url.substring("sftp://".length()) : url;
        int slash = rest.indexOf('/');
        if (slash >= 0) rest = rest.substring(0, slash);
        int at = rest.lastIndexOf('@');
        String hostPart = at >= 0 ? rest.substring(at + 1) : rest;
        String user = at >= 0 ? rest.substring(0, at) : "";
        int colonUser = user.indexOf(':'); // tolerate old-style user:password
        if (colonUser >= 0) user = user.substring(0, colonUser);
        try {
            user = URLDecoder.decode(user, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IOException("Bad user name in open line");
        }
        String host = hostPart, port = "";
        int c = hostPart.lastIndexOf(':');
        if (c > 0 && !hostPart.endsWith("]")) { host = hostPart.substring(0, c); port = hostPart.substring(c + 1); }
        if (host.isEmpty()) throw new IOException("No host in open line");
        return new String[]{user, host, port};
    }

    /** Splits on spaces; a double-quoted token keeps its spaces and loses its quotes (escapes stay raw). */
    static List<String> tokens(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false, have = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuote) {
                if (ch == '\\' && i + 1 < line.length()) { cur.append(ch).append(line.charAt(++i)); }
                else if (ch == '"') inQuote = false;
                else cur.append(ch);
            } else if (ch == '"') { inQuote = true; have = true; }
            else if (Character.isWhitespace(ch)) {
                if (have || cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); have = false; }
            } else cur.append(ch);
        }
        if (have || cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** Local paths were written with {@code \\} and {@code \"} escapes. */
    static String unescapeLocal(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length() && (s.charAt(i + 1) == '\\' || s.charAt(i + 1) == '"')) b.append(s.charAt(++i));
            else b.append(ch);
        }
        return b.toString();
    }

    /** Remote paths were written with only {@code \"} escaped. */
    static String unescapeRemote(String s) {
        return s.replace("\\\"", "\"");
    }
}
