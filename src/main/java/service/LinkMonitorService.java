package service;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs the socket-link monitor inside the scheduler process, which in practice is the Daemon (the window
 * is a thin client and never starts a scheduler): reads {@code socket-monitor.properties}, serves the ingest endpoint and passes every alert to a sink
 * that writes it to the Events feed.
 *
 * <p>If the configured port is already taken by something else this instance does not serve, and quietly tries
 * again on later ticks. Nothing here can affect scheduling: every failure is logged and swallowed.
 */
public final class LinkMonitorService implements AutoCloseable {

    private static final Logger log = Logger.getLogger(LinkMonitorService.class.getName());

    private final File dataDir;
    private final Consumer<LinkMonitor.Alert> sink;
    private final LongSupplier clock;
    private final LinkMonitor monitor;
    private final LinkActivityLog activity;
    private final java.util.Map<String, Long> rejectedAt = new java.util.HashMap<>();
    private LinkMonitorConfig config = LinkMonitorConfig.empty();
    private long configStamp = Long.MIN_VALUE;
    private LinkIngestServer server;
    private String lastProblemText = "";
    private boolean bindFailureLogged;
    private long statusWrittenAt = Long.MIN_VALUE;

    public LinkMonitorService(File dataDir, Consumer<LinkMonitor.Alert> sink) {
        this(dataDir, sink, System::currentTimeMillis);
    }

    LinkMonitorService(File dataDir, Consumer<LinkMonitor.Alert> sink, LongSupplier clock) {
        this.dataDir = dataDir;
        this.sink = sink;
        this.clock = clock;
        this.monitor = new LinkMonitor(clock.getAsLong());
        this.activity = new LinkActivityLog(dataDir);
    }

    public LinkMonitor monitor() {
        return monitor;
    }

    /**
     * Reads the settings if they changed and (re)starts the endpoint when needed. Call at start-up and then periodically.
     */
    public synchronized void refresh() {
        try {
            File f = new File(dataDir, LinkMonitorConfig.FILE_NAME);
            long stamp = f.isFile() ? f.lastModified() * 31 + f.length() : -1;
            boolean listenerDown = config.enabled() && server == null;   // retry quietly: the other instance may have gone
            if (stamp == configStamp && !listenerDown) return;
            configStamp = stamp;

            LinkMonitorConfig next = LinkMonitorConfig.load(dataDir);
            String problems = String.join("; ", next.problems);
            if (!problems.equals(lastProblemText) && !problems.isEmpty()) {
                log.warning("Socket-link settings: " + problems);
            }
            lastProblemText = problems;

            boolean restart = server != null && !next.sameListener(config);
            if (restart || !next.enabled()) stopServer();
            config = next;
            monitor.setSources(next.sources, clock.getAsLong());
            if (next.enabled() && server == null) startServer();
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Socket-link monitor refresh failed", e);
        }
    }

    private void startServer() {
        try {
            String keystore = null, password = null;
            if (config.tlsEnabled) {
                if (config.tlsKeystore != null) {
                    keystore = config.tlsKeystore;
                    password = config.tlsPassword;
                } else {
                    LinkTls.Material m = LinkTls.ensure(dataDir, config.tlsHostnames);
                    keystore = m.keystorePath();
                    password = m.password();
                }
            } else {
                log.warning("Socket-link endpoint runs WITHOUT HTTPS (tls.enabled=false): tokens and events travel in clear text.");
            }
            server = LinkIngestServer.start(config.bind, config.port, keystore, password, new LinkIngestServer.Handler() {
                @Override
                public LinkMonitor.Ingest handle(String t, String remote, List<LinkMonitor.Report> r) {
                    return LinkMonitorService.this.handle(t, remote, r);
                }

                @Override
                public void rejected(String remote, int status, String why) {
                    LinkMonitorService.this.rejected(remote, status, why);
                }

                @Override
                public void exchange(String remote, String method, String path, String token,
                                     String request, int status, String response) {
                    LinkMonitorService.this.exchange(remote, method, path, token, request, status, response);
                }
            });
            bindFailureLogged = false;
        } catch (Exception e) {
            server = null;
            if (!bindFailureLogged) {
                bindFailureLogged = true;
                log.warning("Socket-link endpoint not started on port " + config.port + " (" + e.getMessage()
                        + "). Another instance may already be serving it.");
            }
        }
    }

    private void stopServer() {
        if (server != null) {
            server.close();
            server = null;
        }
        bindFailureLogged = false;
    }

    /**
     * Finds the source this token belongs to (current token, or the previous one inside its grace period) and feeds the reports to it.
     */
    private LinkMonitor.Ingest handle(String token, String remote, List<LinkMonitor.Report> reports) {
        long now = clock.getAsLong();
        LinkMonitor.Source match = null;
        for (LinkMonitor.Source s : config.sources) {
            if (s.accepts(token, now)) match = s;
        }
        if (match == null) return null;
        LinkMonitor.Ingest r = monitor.ingest(match.id(), reports, now);
        recordActivity(match, remote, reports, r);
        deliver(r.alerts());
        writeStatus(!r.alerts().isEmpty());   // a state change must show in the window at once, not up to a minute later
        return r;
    }

    /**
     * Every accepted request lands in the Events feed: a heartbeat-only request at debug level, anything else as info.
     */
    private void recordActivity(LinkMonitor.Source src, String remote, List<LinkMonitor.Report> reports, LinkMonitor.Ingest r) {
        boolean onlyHeartbeats = true;
        StringBuilder body = new StringBuilder();
        for (LinkMonitor.Report rep : reports) {
            String type = rep.type() == null ? "heartbeat" : rep.type();
            if (!"heartbeat".equalsIgnoreCase(type)) onlyHeartbeats = false;
            if (body.length() > 0) body.append('\n');
            body.append(type);
            if (rep.state() != null) body.append(" state=").append(rep.state());
            if (rep.seq() >= 0) body.append(" seq=").append(rep.seq());
            if (rep.reason() != null) body.append(" reason=").append(rep.reason());
        }
        String summary = src.label() + ": " + (onlyHeartbeats ? "heartbeat" : "report") + " received (" + reports.size()
                + (reports.size() == 1 ? " message)" : " messages)");
        String details = "From " + remote + ". Request: " + reports.size() + " message(s).\n" + body
                + "\nResponse: 200 {\"accepted\":" + r.accepted() + ",\"duplicates\":" + r.duplicates() + "}";
        deliver(List.of(new LinkMonitor.Alert(onlyHeartbeats ? model.EventKind.LINK_HEARTBEAT : model.EventKind.LINK_REPORT,
                src.id(), src.label(), summary, details)));
    }

    /**
     * One line in the activity log for every request, with the source it belongs to when the token is known.
     */
    private void exchange(String remote, String method, String path, String token, String request, int status, String response) {
        long now = clock.getAsLong();
        String id = "", label = "";
        if (token != null) {
            for (LinkMonitor.Source s : config.sources) {
                if (s.accepts(token, now)) {
                    id = s.id();
                    label = s.label();
                }
            }
        }
        activity.append(new LinkActivityLog.Entry(now, remote, id, label, method, path, status,
                LinkActivityLog.tokenHint(token), request, response));
    }

    /**
     * A turned-away request. Repeats from the same address and reason are recorded at most once a minute.
     */
    private void rejected(String remote, int status, String why) {
        long now = clock.getAsLong();
        String key = remote + "|" + status;
        synchronized (rejectedAt) {
            Long last = rejectedAt.get(key);
            if (last != null && now - last < 60_000L) return;
            if (rejectedAt.size() > 500) rejectedAt.clear();
            rejectedAt.put(key, now);
        }
        deliver(List.of(new LinkMonitor.Alert(model.EventKind.LINK_REJECTED, "", "Link endpoint",
                "Link endpoint: request from " + remote + " rejected (" + status + ")",
                "From " + remote + ". Response: " + status + " " + why + ".")));
    }

    /**
     * Periodic check for silence and repeat reminders; also picks up settings changes.
     */
    public void tick() {
        refresh();
        try {
            deliver(monitor.tick(clock.getAsLong()));
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Socket-link tick failed", e);
        }
        writeStatus(true);
    }

    /**
     * Publishes the live state for the window. Requests trigger it too, at most every few seconds.
     */
    void writeStatus(boolean force) {
        long now = clock.getAsLong();
        synchronized (this) {
            if (!force && statusWrittenAt != Long.MIN_VALUE && now - statusWrittenAt < 5_000L) return;
            statusWrittenAt = now;
        }
        try {
            LinkMonitorConfig c = config;
            List<LinkStatusFile.Source> list = new java.util.ArrayList<>();
            for (LinkMonitor.Snapshot s : monitor.snapshots()) {
                list.add(new LinkStatusFile.Source(s.id(), s.label(), s.state() == null ? null : s.state().name(),
                        s.stateSince(), s.lastHeardAt(), s.silent()));
            }
            List<String> addresses = new java.util.ArrayList<>();
            String scheme = c.tlsEnabled ? "https://" : "http://";
            for (String h : LinkTls.hostNames(c.tlsHostnames)) addresses.add(scheme + h + ":" + c.port);
            LinkStatusFile.write(dataDir, new LinkStatusFile.Status(now, c.port, c.tlsEnabled, listeningPort() > 0,
                    addresses, list));
        } catch (Exception e) {
            log.log(Level.WARNING, "Socket-link status not written", e);
        }
    }

    private void deliver(List<LinkMonitor.Alert> alerts) {
        for (LinkMonitor.Alert a : alerts) {
            try {
                sink.accept(a);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "Socket-link alert not recorded", e);
            }
        }
    }

    /**
     * Port the endpoint is serving on, or -1 when it is not (disabled, or taken by another instance).
     */
    public synchronized int listeningPort() {
        return server == null ? -1 : server.port();
    }

    @Override
    public synchronized void close() {
        stopServer();
        writeStatus(true);
    }
}
