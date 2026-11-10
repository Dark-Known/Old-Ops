package service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * The live state of the link monitor, written by whichever process runs it (the Daemon) and read by the window,
 * which never runs the monitor itself. One small properties file, replaced atomically.
 */
public final class LinkStatusFile {

    public static final String FILE_NAME = "socket-monitor.status";
    /**
     * A status older than this means the process that writes it is not running.
     */
    public static final long STALE_MILLIS = 3 * 60_000L;

    private LinkStatusFile() {
    }

    public static void write(File dataDir, Status st) throws IOException {
        Properties p = new Properties();
        p.setProperty("updatedAt", Long.toString(st.updatedAt()));
        p.setProperty("port", Integer.toString(st.port()));
        p.setProperty("tls", Boolean.toString(st.tls()));
        p.setProperty("listening", Boolean.toString(st.listening()));
        p.setProperty("addresses", String.join(",", st.addresses()));
        p.setProperty("sources", Integer.toString(st.sources().size()));
        int i = 0;
        for (Source s : st.sources()) {
            String k = "s" + i++ + ".";
            p.setProperty(k + "id", s.id());
            p.setProperty(k + "label", s.label());
            p.setProperty(k + "state", s.state() == null ? "" : s.state());
            p.setProperty(k + "since", Long.toString(s.stateSince()));
            p.setProperty(k + "heard", Long.toString(s.lastHeardAt()));
            p.setProperty(k + "silent", Boolean.toString(s.silent()));
        }
        File tmp = new File(dataDir, FILE_NAME + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            p.store(out, "Live socket-link status (written by the background service)");
        }
        Files.move(tmp.toPath(), new File(dataDir, FILE_NAME).toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * The last status written, or null when there is none or it cannot be read.
     */
    public static Status read(File dataDir) {
        File f = new File(dataDir, FILE_NAME);
        if (!f.isFile()) return null;
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(f)) {
            p.load(in);
        } catch (IOException e) {
            return null;
        }
        try {
            List<String> addr = new ArrayList<>();
            for (String a : p.getProperty("addresses", "").split(",")) if (!a.isBlank()) addr.add(a.trim());
            List<Source> src = new ArrayList<>();
            int n = Integer.parseInt(p.getProperty("sources", "0"));
            for (int i = 0; i < n; i++) {
                String k = "s" + i + ".";
                String state = p.getProperty(k + "state", "");
                src.add(new Source(p.getProperty(k + "id", ""), p.getProperty(k + "label", ""), state.isEmpty() ? null : state,
                        Long.parseLong(p.getProperty(k + "since", "0")), Long.parseLong(p.getProperty(k + "heard", "0")),
                        Boolean.parseBoolean(p.getProperty(k + "silent", "false"))));
            }
            return new Status(Long.parseLong(p.getProperty("updatedAt", "0")), Integer.parseInt(p.getProperty("port", "0")),
                    Boolean.parseBoolean(p.getProperty("tls", "true")), Boolean.parseBoolean(p.getProperty("listening", "false")),
                    addr, src);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public record Source(String id, String label, String state, long stateSince, long lastHeardAt, boolean silent) {
    }

    public record Status(long updatedAt, int port, boolean tls, boolean listening, List<String> addresses,
                         List<Source> sources) {
        public boolean fresh(long now) {
            return updatedAt > 0 && now - updatedAt <= STALE_MILLIS;
        }

        public Source find(String id) {
            for (Source s : sources) if (s.id().equals(id)) return s;
            return null;
        }

        /**
         * Number of enabled sources that are down, stalled or silent.
         */
        public int problems() {
            int n = 0;
            for (Source s : sources) {
                if (s.silent() || "DISCONNECTED".equals(s.state()) || "STALLED".equals(s.state())) n++;
            }
            return n;
        }
    }
}
