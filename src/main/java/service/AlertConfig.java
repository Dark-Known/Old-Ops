package service;

import model.EventKind;
import util.CryptoUtil;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Where alerts go and which events produce one: the Teams webhook, the default list of alert types, and a per-task
 * choice (follow the defaults, send nothing, or use the task's own list). Stored in {@code alerts.properties} in the
 * data folder; the webhook address is encrypted because it contains a secret. Immutable: every change makes a copy.
 */
public final class AlertConfig {

    public static final String FILE_NAME = "alerts.properties";
    /**
     * Things that go wrong and need someone to look.
     */
    public static final Set<EventKind> PROBLEMS = Collections.unmodifiableSet(EnumSet.of(
            EventKind.RUN_FAILED, EventKind.RETRIES_EXHAUSTED, EventKind.NO_FILES, EventKind.FILES_OVERDUE,
            EventKind.MAIL_NOT_SAVED, EventKind.STALE_RECOVERED, EventKind.WATCH_DEGRADED, EventKind.WATCH_UNSUPPORTED,
            EventKind.SSH_FAILED, EventKind.LINK_DOWN, EventKind.LINK_STALLED, EventKind.LINK_FLAPPING,
            EventKind.LINK_SILENT, EventKind.HEALTH, EventKind.SYSTEM_ERROR, EventKind.BACKGROUND_FAILED,
            EventKind.GRAPH_FAILING, EventKind.SERVICE_COMMAND_FAILED));
    /**
     * Events that say a problem is over; they produce a short "recovered" card.
     */
    public static final Set<EventKind> RECOVERIES = Collections.unmodifiableSet(EnumSet.of(
            EventKind.FILES_RESUMED, EventKind.WATCH_RESTORED, EventKind.LINK_UP, EventKind.GRAPH_RECOVERED));
    /**
     * Never an alert, so a delivery or a heartbeat can not trigger another alert.
     */
    public static final Set<EventKind> NEVER = Collections.unmodifiableSet(EnumSet.of(
            EventKind.ALERT_SENT, EventKind.ALERT_FAILED, EventKind.ALERT_QUEUED, EventKind.ALERT_RETRY, EventKind.REPORT_SENT, EventKind.REPORT_VIEWED,
            EventKind.REPORT_DOWNLOADED, EventKind.REPORT_REJECTED, EventKind.LINK_HEARTBEAT, EventKind.LINK_REPORT));
    public static final String SOCKET = "@socket", OUTLOOK = "@outlook", SERVICE = "@service", SYSTEM = "@system";
    /**
     * Name of the row in app.db that holds the settings (the file name above is only the pre-database location).
     */
    static final String KV_NAME = "alerts";
    public final boolean enabled;
    public final String webhookUrl;
    public final Set<EventKind> kinds;
    public final boolean recoveryCards;
    public final int throttleMinutes;
    public final String reportDir;       // blank = <data folder>/alert-reports
    public final String reportBaseUrl;   // blank = link to the file itself
    public final int reportKeepDays;
    public final Map<String, TaskRule> tasks;
    /**
     * Serve the reports from this application over HTTPS, so "Open logs" opens them from here.
     */
    public final boolean reportServer;
    public final int reportPort;
    /**
     * The name people type to reach this PC (blank = this PC's own name).
     */
    public final String reportHost;
    public AlertConfig(boolean enabled, String webhookUrl, Set<EventKind> kinds, boolean recoveryCards, int throttleMinutes,
                       String reportDir, String reportBaseUrl, int reportKeepDays, Map<String, TaskRule> tasks) {
        this(enabled, webhookUrl, kinds, recoveryCards, throttleMinutes, reportDir, reportBaseUrl, reportKeepDays, tasks, true, 8444, "");
    }

    public AlertConfig(boolean enabled, String webhookUrl, Set<EventKind> kinds, boolean recoveryCards, int throttleMinutes,
                       String reportDir, String reportBaseUrl, int reportKeepDays, Map<String, TaskRule> tasks,
                       boolean reportServer, int reportPort, String reportHost) {
        this.reportServer = reportServer;
        this.reportPort = reportPort <= 0 || reportPort > 65535 ? 8444 : reportPort;
        this.reportHost = reportHost == null ? "" : reportHost.trim();
        this.enabled = enabled;
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        this.kinds = Collections.unmodifiableSet(kinds.isEmpty() ? EnumSet.noneOf(EventKind.class) : EnumSet.copyOf(kinds));
        this.recoveryCards = recoveryCards;
        this.throttleMinutes = Math.max(0, throttleMinutes);
        this.reportDir = reportDir == null ? "" : reportDir.trim();
        this.reportBaseUrl = reportBaseUrl == null ? "" : reportBaseUrl.trim();
        this.reportKeepDays = reportKeepDays <= 0 ? 30 : reportKeepDays;
        this.tasks = Collections.unmodifiableMap(new HashMap<>(tasks));
    }

    public static AlertConfig empty() {
        return new AlertConfig(false, "", PROBLEMS, true, 15, "", "", 30, Map.of());
    }

    /**
     * Which monitor an event belongs to (a key usable in {@link #ruleFor}), or null for events about a task.
     */
    public static String areaOf(EventKind k) {
        if (k == null) return null;
        switch (k) {
            case LINK_DOWN:
            case LINK_STALLED:
            case LINK_FLAPPING:
            case LINK_SILENT:
            case LINK_UP:
            case LINK_REJECTED:
                return SOCKET;
            case GRAPH_FAILING:
            case GRAPH_RECOVERED:
                return OUTLOOK;
            case SERVICE_COMMAND_FAILED:
            case BACKGROUND_STARTED:
            case BACKGROUND_COMPLETED:
            case BACKGROUND_FAILED:
            case BACKGROUND_SLOW:
            case FEED_STARTED:
            case UI_STALL:
                return SERVICE;
            case HEALTH:
            case SYSTEM_ERROR:
            case CONFIG_CHANGE:
                return SYSTEM;
            default:
                return null;
        }
    }

    public static AlertConfig load(File dataDir) {
        String text = AppDb.get(dataDir, KV_NAME);
        if (text == null) {
            // settings written by an older version were a loose file; move them into app.db once
            text = AppDb.takeLegacyFile(new File(dataDir, FILE_NAME));
            if (text == null) return empty();
            try {
                AppDb.put(dataDir, KV_NAME, text);
            } catch (IOException e) { /* still usable this time */ }
        }
        Properties p = new Properties();
        try {
            p.load(new java.io.StringReader(text));
        } catch (IOException e) {
            return empty();
        }
        return parse(p, dataDir);
    }

    static AlertConfig parse(Properties p, File dataDir) {
        CryptoUtil.configure(dataDir);
        String url = p.getProperty("teams.webhook", "").trim();
        if (!url.isEmpty()) {
            try {
                url = CryptoUtil.isV2(url) ? CryptoUtil.decrypt(url) : url;
            } catch (RuntimeException e) {
                url = "";
            }
        }
        Set<EventKind> kinds = p.containsKey("kinds") ? parseKinds(p.getProperty("kinds")) : EnumSet.copyOf(PROBLEMS);
        Map<String, TaskRule> tasks = new HashMap<>();
        for (String key : p.stringPropertyNames()) {
            if (!key.startsWith("task.") || !key.endsWith(".mode")) continue;
            String id = key.substring(5, key.length() - 5);
            Mode m;
            try {
                m = Mode.valueOf(p.getProperty(key).trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (m == Mode.INHERIT) continue;
            tasks.put(id, new TaskRule(m, parseKinds(p.getProperty("task." + id + ".kinds", ""))));
        }
        return new AlertConfig(Boolean.parseBoolean(p.getProperty("enabled", "false")), url, kinds,
                Boolean.parseBoolean(p.getProperty("recovery", "true")), intOf(p.getProperty("throttleMinutes"), 15),
                p.getProperty("reportDir", ""), p.getProperty("reportBaseUrl", ""), intOf(p.getProperty("reportKeepDays"), 30), tasks,
                Boolean.parseBoolean(p.getProperty("reportServer", "true")), intOf(p.getProperty("reportPort"), 8444), p.getProperty("reportHost", ""));
    }

    static Set<EventKind> parseKinds(String s) {
        Set<EventKind> out = EnumSet.noneOf(EventKind.class);
        for (String part : s.split(",")) {
            try {
                if (!part.isBlank()) out.add(EventKind.valueOf(part.trim()));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return out;
    }

    private static String join(Set<EventKind> kinds) {
        List<String> names = new ArrayList<>();
        for (EventKind k : kinds) names.add(k.name());
        Collections.sort(names);
        return String.join(",", names);
    }

    private static int intOf(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean ready() {
        return enabled && !webhookUrl.isEmpty();
    }

    public TaskRule ruleFor(String taskId) {
        TaskRule r = taskId == null ? null : tasks.get(taskId);
        return r == null ? new TaskRule(Mode.INHERIT, Set.of()) : r;
    }

    /**
     * The rule that applies to this event: the task's (or socket source's) own setting, else its monitor's, else the defaults.
     */
    public TaskRule ruleFor(String taskId, EventKind kind) {
        TaskRule own = ruleFor(taskId);
        if (own.mode() != Mode.INHERIT) return own;
        String area = areaOf(kind);
        return area == null ? own : ruleFor(area);
    }

    /**
     * True when this event, for this task or monitor, should produce a card. {@code taskId} may be null for system events.
     */
    public boolean wants(String taskId, EventKind kind) {
        if (!ready() || kind == null || NEVER.contains(kind)) return false;
        TaskRule r = ruleFor(taskId, kind);
        if (r.mode() == Mode.OFF) return false;
        Set<EventKind> list = r.mode() == Mode.CUSTOM ? r.kinds() : kinds;
        if (list.contains(kind)) return true;
        // A recovery follows its problem: if the task would have been alerted about problems, tell it recovered too.
        return recoveryCards && RECOVERIES.contains(kind) && r.mode() != Mode.OFF;
    }

    // ── Files ──────────────────────────────────────────────────────────────

    public AlertConfig with(boolean enabled, String webhookUrl, Set<EventKind> kinds, boolean recoveryCards, int throttleMinutes,
                            String reportDir, String reportBaseUrl, int reportKeepDays) {
        return new AlertConfig(enabled, webhookUrl, kinds, recoveryCards, throttleMinutes, reportDir, reportBaseUrl,
                reportKeepDays, tasks, reportServer, reportPort, reportHost);
    }

    public AlertConfig withReportServer(boolean on, int port, String host) {
        return new AlertConfig(enabled, webhookUrl, kinds, recoveryCards, throttleMinutes, reportDir, reportBaseUrl, reportKeepDays, tasks,
                on, port, host);
    }

    public AlertConfig withTask(String taskId, TaskRule rule) {
        Map<String, TaskRule> m = new HashMap<>(tasks);
        if (rule == null || rule.mode() == Mode.INHERIT) m.remove(taskId);
        else m.put(taskId, rule);
        return new AlertConfig(enabled, webhookUrl, kinds, recoveryCards, throttleMinutes, reportDir, reportBaseUrl, reportKeepDays, m, reportServer, reportPort, reportHost);
    }

    public void save(File dataDir) throws IOException {
        CryptoUtil.configure(dataDir);
        Properties p = new Properties();
        p.setProperty("enabled", Boolean.toString(enabled));
        if (!webhookUrl.isEmpty()) p.setProperty("teams.webhook", CryptoUtil.encrypt(webhookUrl));
        p.setProperty("kinds", join(kinds));
        p.setProperty("recovery", Boolean.toString(recoveryCards));
        p.setProperty("throttleMinutes", Integer.toString(throttleMinutes));
        p.setProperty("reportDir", reportDir);
        p.setProperty("reportBaseUrl", reportBaseUrl);
        p.setProperty("reportKeepDays", Integer.toString(reportKeepDays));
        p.setProperty("reportServer", Boolean.toString(reportServer));
        p.setProperty("reportPort", Integer.toString(reportPort));
        p.setProperty("reportHost", reportHost);
        for (Map.Entry<String, TaskRule> e : tasks.entrySet()) {
            p.setProperty("task." + e.getKey() + ".mode", e.getValue().mode().name());
            if (e.getValue().mode() == Mode.CUSTOM)
                p.setProperty("task." + e.getKey() + ".kinds", join(e.getValue().kinds()));
        }
        java.io.StringWriter w = new java.io.StringWriter();
        p.store(w, "Alert settings (the webhook address is encrypted)");
        AppDb.put(dataDir, KV_NAME, w.toString());
    }

    /**
     * Host part of the webhook address for display ("prod-12.westeurope.logic.azure.com"); never the secret path or query.
     */
    public String webhookHost() {
        try {
            return java.net.URI.create(webhookUrl).getHost();
        } catch (RuntimeException e) {
            return "";
        }
    }

    @Override
    public String toString() {
        return "AlertConfig[enabled=" + enabled + ", kinds=" + Arrays.toString(kinds.toArray()) + "]";
    }

    /**
     * What a task does about alerts.
     */
    public enum Mode {INHERIT, OFF, CUSTOM}

    /**
     * One task's setting.
     */
    public record TaskRule(Mode mode, Set<EventKind> kinds) {
    }
}
