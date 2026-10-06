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
    RUN_STARTED      ("Started",          Category.RUN,    Severity.INFO),
    FILES_DETECTED   ("Files detected",   Category.RUN,    Severity.INFO),
    RUN_SUCCEEDED    ("Succeeded",        Category.RUN,    Severity.INFO),
    RUN_FAILED       ("Failed",           Category.RUN,    Severity.ERROR),
    RUN_SKIPPED      ("Skipped",          Category.RUN,    Severity.INFO),
    RETRY_SCHEDULED  ("Retry scheduled",  Category.RUN,    Severity.WARN),
    RETRIES_EXHAUSTED("Retries exhausted",Category.RUN,    Severity.ERROR),
    RUN_CANCELLED    ("Cancelled",        Category.RUN,    Severity.WARN),
    STALE_RECOVERED  ("Stale run reset",  Category.RUN,    Severity.WARN),

    // ── Watchers ─────────────────────────────────────────────────────
    WATCH_ARMED      ("Watcher armed",    Category.WATCH,  Severity.INFO),
    WATCH_TRIGGERED  ("Change detected",  Category.WATCH,  Severity.INFO),
    WATCH_DEGRADED   ("Watcher degraded", Category.WATCH,  Severity.WARN),
    WATCH_RESTORED   ("Watcher restored", Category.WATCH,  Severity.INFO),
    WATCH_UNSUPPORTED("Push unsupported", Category.WATCH,  Severity.WARN),

    // ── Queue bookkeeping (hidden at the default level) ──────────────
    FIRE_DEFERRED    ("Fire deferred",    Category.QUEUE,  Severity.DEBUG),
    FIRE_DROPPED     ("Fire dropped",     Category.QUEUE,  Severity.DEBUG),

    // ── System / audit ───────────────────────────────────────────────
    CONFIG_CHANGE    ("Config change",    Category.SYSTEM, Severity.INFO),
    HEALTH           ("Health",           Category.SYSTEM, Severity.WARN),
    SYSTEM_ERROR     ("System error",     Category.SYSTEM, Severity.ERROR);

    public enum Category {
        RUN("Runs"), WATCH("Watchers"), QUEUE("Queue"), SYSTEM("System");
        private final String label;
        Category(String label) { this.label = label; }
        public String label() { return label; }
    }

    /** Ordered least → most severe so {@code compareTo} can be used for "at least this level". */
    public enum Severity {
        DEBUG("Debug"), INFO("Info"), WARN("Warning"), ERROR("Error");
        private final String label;
        Severity(String label) { this.label = label; }
        public String label() { return label; }
    }

    private final String label;
    private final Category category;
    private final Severity defaultSeverity;

    EventKind(String label, Category category, Severity defaultSeverity) {
        this.label = label;
        this.category = category;
        this.defaultSeverity = defaultSeverity;
    }

    public String label()                 { return label; }
    public Category category()            { return category; }
    public Severity defaultSeverity()     { return defaultSeverity; }

    /** Null-safe, forward-compatible parse: an unknown name (written by a newer build) yields null. */
    public static EventKind parse(String name) {
        if (name == null || name.isBlank()) return null;
        try { return valueOf(name); } catch (IllegalArgumentException e) { return null; }
    }
}
