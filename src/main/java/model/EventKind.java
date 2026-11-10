package model;

/**
 * Every kind of event the Event Monitor can show. Stored by name in the
 * {@code event_kind} column of both run-history tables (see
 * {@link service.RunHistoryService}); rows written before that column
 * existed carry {@code null} and are classified from their text by
 * {@code ui.monitor.EventClassifier}.
 *
 * <p>Each kind carries a {@link Category} (what part of the system it is
 * about — drives the filter chips) and a default {@link Severity} (drives
 * the level filter and which events are hidden unless the operator asks
 * for them). A concrete row can still override severity — e.g. a config
 * change that failed to save is {@code ERROR} even though
 * {@link #CONFIG_CHANGE} defaults to {@code INFO}.
 */
public enum EventKind {

    // ── Run lifecycle ────────────────────────────────────────────────
    RUN_STARTED("Started", Category.RUN, Severity.INFO),
    FILES_DETECTED("Files detected", Category.RUN, Severity.INFO),
    FILES_CHANGED("Files changed", Category.RUN, Severity.INFO),
    RUN_SUCCEEDED("Succeeded", Category.RUN, Severity.INFO),
    RUN_FAILED("Failed", Category.RUN, Severity.ERROR),
    RUN_SKIPPED("Skipped", Category.RUN, Severity.INFO),
    RETRY_SCHEDULED("Retry scheduled", Category.RUN, Severity.WARN),
    RETRIES_EXHAUSTED("Retries exhausted", Category.RUN, Severity.ERROR),
    RUN_CANCELLED("Cancelled", Category.RUN, Severity.WARN),
    STALE_RECOVERED("Stale run reset", Category.RUN, Severity.WARN),

    NO_FILES("No files found", Category.RUN, Severity.WARN),
    FILES_OVERDUE("No new files", Category.RUN, Severity.WARN),
    FILES_RESUMED("Files arriving again", Category.RUN, Severity.INFO),

    // ── Mail tasks: one event per message handled by a run ───────────
    MAIL_PROCESSED("Mail processed", Category.RUN, Severity.INFO),
    MAIL_NOT_SAVED("Mail not saved", Category.RUN, Severity.WARN),

    // ── Watchers ─────────────────────────────────────────────────────
    WATCH_ARMED("Watcher armed", Category.WATCH, Severity.INFO),
    WATCH_TRIGGERED("Change detected", Category.WATCH, Severity.INFO),
    WATCH_DEGRADED("Watcher degraded", Category.WATCH, Severity.WARN),
    WATCH_RESTORED("Watcher restored", Category.WATCH, Severity.INFO),
    WATCH_UNSUPPORTED("Push unsupported", Category.WATCH, Severity.WARN),

    // ── SSH sessions opened/closed on behalf of a task ───────────────
    SSH_OPENED("SSH session opened", Category.SSH, Severity.INFO),
    SSH_CLOSED("SSH session closed", Category.SSH, Severity.INFO),
    SSH_FAILED("SSH connect failed", Category.SSH, Severity.WARN),

    // ── Queue bookkeeping (hidden at the default level) ──────────────
    FIRE_DEFERRED("Fire deferred", Category.QUEUE, Severity.DEBUG),
    FIRE_DROPPED("Fire dropped", Category.QUEUE, Severity.DEBUG),

    // ── System / audit ───────────────────────────────────────────────
    CONFIG_CHANGE("Config change", Category.SYSTEM, Severity.INFO),
    HEALTH("Health", Category.SYSTEM, Severity.WARN),
    SYSTEM_ERROR("System error", Category.SYSTEM, Severity.ERROR),

    // ── Socket / link monitor (reports pushed by another server's application) ──
    LINK_DOWN("Link down", Category.SYSTEM, Severity.WARN),
    LINK_STALLED("Link stalled", Category.SYSTEM, Severity.WARN),
    LINK_FLAPPING("Link flapping", Category.SYSTEM, Severity.WARN),
    LINK_SILENT("Link source silent", Category.SYSTEM, Severity.WARN),
    LINK_UP("Link restored", Category.SYSTEM, Severity.INFO),
    LINK_REPORT("Link report received", Category.SYSTEM, Severity.INFO),
    LINK_HEARTBEAT("Link heartbeat", Category.SYSTEM, Severity.DEBUG),
    LINK_REJECTED("Link request rejected", Category.SYSTEM, Severity.WARN),

    // ── Outlook mail API and the background service (monitored, can alert) ──
    GRAPH_FAILING("Outlook mail API failing", Category.SYSTEM, Severity.WARN),
    GRAPH_RECOVERED("Outlook mail API working again", Category.SYSTEM, Severity.INFO),
    SERVICE_COMMAND_FAILED("Service command failed", Category.SYSTEM, Severity.WARN),

    // ── Alert delivery (cards sent to Teams) ──
    ALERT_QUEUED("Alert queued", Category.ALERT, Severity.INFO),
    ALERT_RETRY("Alert retry", Category.ALERT, Severity.WARN),
    REPORT_SENT("Report sent", Category.ALERT, Severity.INFO),
    REPORT_VIEWED("Report opened", Category.ALERT, Severity.INFO),
    REPORT_DOWNLOADED("Report downloaded", Category.ALERT, Severity.INFO),
    REPORT_REJECTED("Report request refused", Category.ALERT, Severity.WARN),
    ALERT_SENT("Alert sent", Category.ALERT, Severity.INFO),
    ALERT_FAILED("Alert not delivered", Category.ALERT, Severity.WARN),

    // ── Background work (the app's own worker threads) ───────────────
    BACKGROUND_STARTED("Background job started", Category.BACKGROUND, Severity.INFO),
    BACKGROUND_COMPLETED("Background job finished", Category.BACKGROUND, Severity.INFO),
    BACKGROUND_FAILED("Background job failed", Category.BACKGROUND, Severity.ERROR),
    BACKGROUND_SLOW("Slow background job", Category.BACKGROUND, Severity.WARN),
    FEED_STARTED("Live feed started", Category.BACKGROUND, Severity.INFO),
    UI_STALL("UI stall", Category.BACKGROUND, Severity.WARN);

    private final String label;
    private final Category category;
    private final Severity defaultSeverity;
    EventKind(String label, Category category, Severity defaultSeverity) {
        this.label = label;
        this.category = category;
        this.defaultSeverity = defaultSeverity;
    }

    /**
     * Null-safe, forward-compatible parse: an unknown name (written by a newer build) yields null.
     */
    public static EventKind parse(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String label() {
        return label;
    }

    public Category category() {
        return category;
    }

    public Severity defaultSeverity() {
        return defaultSeverity;
    }

    public enum Category {
        RUN("Runs"), WATCH("Watchers"), QUEUE("Queue"), SSH("SSH"), SYSTEM("System"), BACKGROUND("Background"), ALERT("Teams alerts");
        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /**
         * True for categories whose events are not about any one scheduled task.
         */
        public boolean taskless() {
            return this == SYSTEM || this == BACKGROUND || this == ALERT;
        }
    }

    /**
     * Ordered least → most severe so {@code compareTo} can be used for "at least this level".
     */
    public enum Severity {
        DEBUG("Debug"), INFO("Info"), WARN("Warning"), ERROR("Error");
        private final String label;

        Severity(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
