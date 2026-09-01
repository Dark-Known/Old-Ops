package util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Live, database-backed store for settings that should take effect
 * immediately — without an application (or JVM) restart — and that the
 * Settings panel lets the user edit directly.
 *
 * <p>This is deliberately a separate store from {@code app-config.xml}:
 * <ul>
 *   <li><b>app-config.xml</b> stays the *install-time* file consumed by
 *       {@code setup.ps1} (install paths, daemon registration, JVM heap
 *       flags the launcher passes on the java command line, etc). Most of
 *       it can only take effect on install/relaunch, so there's little value
 *       in hot-reloading it, and doing so would risk the installer and the
 *       running app disagreeing about what's "current".</li>
 *   <li><b>app-settings.db</b> (this class, a small SQLite database — see
 *       {@code <dataDir>/app-settings.db}) holds the handful of values that
 *       genuinely are read on every message/task run and can safely change
 *       underneath a running app: mail routing folder names, the default
 *       SITA station address, the attachment download location, and the
 *       log level. Moving this from a hand-edited JSON file to SQLite means
 *       the Settings panel's writes are atomic/durable the same way task
 *       and credential data already are, and there's one consistent place
 *       (and one consistent backup step) for all of the app's persistent
 *       state instead of a mix of JSON + XML + DB files.</li>
 * </ul>
 *
 * <p><b>Real-time behavior:</b> every getter re-checks a short-lived
 * in-memory cache (see {@link #CACHE_TTL_MILLIS}) and re-queries the
 * database once it expires. A local SQLite query is cheap enough to do
 * every couple of seconds (this is not a hot loop — at most a few reads per
 * processed message), so no file watcher/polling thread is needed: edits
 * made in the Settings panel are picked up within {@link #CACHE_TTL_MILLIS}
 * by any reader, whether that's the GUI thread or the background daemon
 * process (each process has its own connection to the same database file).
 *
 * <p>On first run against a data directory that still has the legacy
 * {@code app-settings.json} from a previous version, that file's contents
 * are imported into the database once (see {@link #migrateLegacyJsonIfPresent}),
 * so upgrading in place doesn't reset anyone's settings.
 *
 * <p><b>JVM heap is the one exception.</b> {@code -Xms}/{@code -Xmx} are
 * fixed when the JVM starts; no in-process reload can change already
 * allocated heap bounds. Those two values are still stored here (so the
 * Settings panel has one consistent place to edit everything, and the
 * daemon/launcher can read the current values on its *next* start), but
 * callers must not expect them to apply to the already-running process. The
 * Settings panel labels them accordingly.
 */
public final class AppSettings {

    private static final Logger log = Logger.getLogger(AppSettings.class.getName());
    private static final String DB_FILE_NAME = "app-settings.db";
    private static final String LEGACY_JSON_FILE_NAME = "app-settings.json";
    private static final long CACHE_TTL_MILLIS = 2000; // live-reload granularity

    // Keys
    public static final String KEY_LDM_FOLDER            = "ldmFolder";
    public static final String KEY_PTM_FOLDER             = "ptmFolder";
    public static final String KEY_OTHERS_FOLDER          = "othersFolder";
    public static final String KEY_MAIL_ROUTING_RULES     = "mailRoutingRulesJson";
    public static final String KEY_DEFAULT_STATION_ADDR   = "defaultStationAddress";
    public static final String KEY_ATTACHMENT_DOWNLOAD_DIR = "attachmentDownloadLocation";
    public static final String KEY_LOG_LEVEL              = "logLevel";
    public static final String KEY_JVM_MIN_HEAP           = "jvmMinHeap";
    public static final String KEY_JVM_MAX_HEAP           = "jvmMaxHeap";

    // ── Size-based transfer batching (replaces the old file-count based
    //    "transferBatchSize") ──────────────────────────────────────────────
    // A batch is capped by total bytes rather than file count, because a
    // handful of huge files can take far longer than many small ones. The
    // byte cap is derived from two tunable numbers so an admin can reason
    // about it in human terms ("finish a batch in ~5s over a ~5MB/s link")
    // instead of guessing a raw byte count:
    //   maxBytes = assumedThroughputMBps * 1024 * 1024 * batchTargetSeconds
    // An explicit KEY_TRANSFER_BATCH_MAX_BYTES override is honored if set
    // (> 0) so an admin who knows their real throughput can just set bytes
    // directly instead of the two derived inputs.
    public static final String KEY_TRANSFER_BATCH_TARGET_SECONDS   = "transferBatchTargetSeconds";
    public static final String KEY_TRANSFER_ASSUMED_THROUGHPUT_MBPS = "transferAssumedThroughputMBps";
    public static final String KEY_TRANSFER_BATCH_MAX_BYTES        = "transferBatchMaxBytes";
    public static final String KEY_TRANSFER_BATCH_INTERVAL_SECONDS = "transferBatchIntervalSeconds";

    // How many batches run at once (separate WinSCP/SFTP sessions in
    // parallel) instead of one strictly sequential session at a time. Big
    // win for backlogs with many small files, where per-file protocol
    // round-trip latency — not bandwidth — is the bottleneck, since the
    // byte-size batch cap above still lets a single "small" batch contain
    // thousands of files. 1 = old sequential behavior (default, safest).
    public static final String KEY_TRANSFER_BATCH_CONCURRENCY = "transferBatchConcurrency";

    // How long a task may sit in RUNNING before the scheduler assumes it
    // crashed/hung and force-cancels it. For long-running transfers/backups
    // (e.g. large backlogs of many small files) this may need to be raised
    // well above the 30-minute default so a legitimately slow — but still
    // progressing — run isn't killed and reported as FAILED partway through.
    public static final String KEY_STALE_RUNNING_THRESHOLD_MINUTES = "staleRunningThresholdMinutes";

    // How many minutes a RUNNING task may go without emitting ANY new log
    // line before the scheduler treats it as network-stalled and
    // force-cancels it — independent of, and much shorter than, the overall
    // KEY_STALE_RUNNING_THRESHOLD_MINUTES "since start" ceiling above. A
    // transfer whose connection silently died mid-file (no TCP reset, just
    // packets going nowhere) can otherwise sit producing no output for the
    // *entire* multi-batch run, and the old start-time-only check wouldn't
    // catch that until the full 30-minute (or longer, for scaled interval
    // tasks) threshold elapsed — which reads as "stuck in a dead loop" to
    // an operator watching the Logs tab. This catches that case fast while
    // still letting a genuinely slow-but-progressing transfer run as long
    // as it needs to, since every WinSCP output line resets the clock.
    public static final String KEY_STALE_INACTIVITY_THRESHOLD_MINUTES = "staleInactivityThresholdMinutes";

    // Ceiling on how many task-execution worker threads the scheduler will
    // ever have alive at once (see TaskSchedulerService's fixed thread
    // pool). Read once at scheduler startup, not hot-reloaded — restart
    // required to change. Kept modest by default since most tasks here are
    // I/O-bound (waiting on a remote server), not CPU-bound, so a large
    // number of truly-parallel threads rarely helps throughput but does
    // cost memory (each thread reserves its own stack).
    public static final String KEY_MAX_CONCURRENT_TASK_THREADS = "maxConcurrentTaskThreads";

    // Built-in fallbacks, used only if neither app-settings.json nor
    // app-config.xml has a value (keeps behavior identical to before this
    // file existed, for anyone upgrading in place).
    private static final Map<String, String> HARD_DEFAULTS = new LinkedHashMap<>();
    static {
        HARD_DEFAULTS.put(KEY_LDM_FOLDER, "LDM");
        HARD_DEFAULTS.put(KEY_PTM_FOLDER, "PTM");
        HARD_DEFAULTS.put(KEY_OTHERS_FOLDER, "Others");
        HARD_DEFAULTS.put(KEY_DEFAULT_STATION_ADDR, "");
        HARD_DEFAULTS.put(KEY_ATTACHMENT_DOWNLOAD_DIR, "");
        HARD_DEFAULTS.put(KEY_LOG_LEVEL, "INFO");
        HARD_DEFAULTS.put(KEY_JVM_MIN_HEAP, "512M");
        // Lowered from the previous 2G default. This app's actual live
        // object footprint (a task list, a few in-memory logs each capped
        // at 500KB, a handful of SQLite connections) is a small fraction
        // of that — 2G was simply how high the JVM was ALLOWED to grow,
        // and the JVM tends to grow committed heap toward Xmx over a long
        // session before a full GC reclaims it, which reads as "using 2GB"
        // in Task Manager even when live data is far smaller. 768M gives
        // comfortable headroom for normal operation while capping the
        // worst case much lower; raise it in Settings if a deployment
        // genuinely needs more (e.g. very large attachment downloads).
        HARD_DEFAULTS.put(KEY_JVM_MAX_HEAP, "768M");
        // Default: aim for each batch to finish in ~5s assuming a conservative
        // ~5 MB/s link -> 5 * 5*1024*1024 = 26214400 bytes (~25 MB) per batch.
        HARD_DEFAULTS.put(KEY_TRANSFER_BATCH_TARGET_SECONDS, "5");
        HARD_DEFAULTS.put(KEY_TRANSFER_ASSUMED_THROUGHPUT_MBPS, "5");
        HARD_DEFAULTS.put(KEY_TRANSFER_BATCH_MAX_BYTES, "0"); // 0 = derive from the two settings above
        HARD_DEFAULTS.put(KEY_TRANSFER_BATCH_INTERVAL_SECONDS, "5");
        HARD_DEFAULTS.put(KEY_TRANSFER_BATCH_CONCURRENCY, "1"); // 1 = sequential (old behavior)
        HARD_DEFAULTS.put(KEY_STALE_RUNNING_THRESHOLD_MINUTES, "30");
        HARD_DEFAULTS.put(KEY_STALE_INACTIVITY_THRESHOLD_MINUTES, "5");
        HARD_DEFAULTS.put(KEY_MAX_CONCURRENT_TASK_THREADS, "20");
    }

    // app-config.xml tag each key is seeded from on first run.
    private static final Map<String, String> XML_SEED_TAG = new LinkedHashMap<>();
    static {
        XML_SEED_TAG.put(KEY_LDM_FOLDER, "ldmFolder");
        XML_SEED_TAG.put(KEY_PTM_FOLDER, "ptmFolder");
        XML_SEED_TAG.put(KEY_OTHERS_FOLDER, "othersFolder");
        XML_SEED_TAG.put(KEY_DEFAULT_STATION_ADDR, "defaultStationAddress");
        XML_SEED_TAG.put(KEY_ATTACHMENT_DOWNLOAD_DIR, "attachmentDownloadLocation");
        XML_SEED_TAG.put(KEY_LOG_LEVEL, "logLevel");
        XML_SEED_TAG.put(KEY_JVM_MIN_HEAP, "minHeap");
        XML_SEED_TAG.put(KEY_JVM_MAX_HEAP, "maxHeap");
        XML_SEED_TAG.put(KEY_TRANSFER_BATCH_TARGET_SECONDS, "transferBatchTargetSeconds");
        XML_SEED_TAG.put(KEY_TRANSFER_ASSUMED_THROUGHPUT_MBPS, "transferAssumedThroughputMBps");
        XML_SEED_TAG.put(KEY_TRANSFER_BATCH_MAX_BYTES, "transferBatchMaxBytes");
        XML_SEED_TAG.put(KEY_TRANSFER_BATCH_INTERVAL_SECONDS, "transferBatchIntervalSeconds");
        XML_SEED_TAG.put(KEY_TRANSFER_BATCH_CONCURRENCY, "transferBatchConcurrency");
        XML_SEED_TAG.put(KEY_STALE_RUNNING_THRESHOLD_MINUTES, "staleRunningThresholdMinutes");
        XML_SEED_TAG.put(KEY_STALE_INACTIVITY_THRESHOLD_MINUTES, "staleInactivityThresholdMinutes");
    }

    private static final Object LOCK = new Object();
    private static volatile Map<String, String> cache;
    private static volatile long cacheLoadedAtMillis = -1;
    private static volatile File resolvedDataDir;
    private static volatile Connection conn;

    private AppSettings() {}

    // ─── Data directory / connection resolution ─────────────────────────────

    private static File dataDir() {
        if (resolvedDataDir != null) return resolvedDataDir;
        synchronized (LOCK) {
            if (resolvedDataDir != null) return resolvedDataDir;
            String dataDir = AppConfig.readValue("dataDir");
            if (dataDir == null || dataDir.isEmpty()) {
                // Must match the fallback used by MainWindow.loadDataDir() and
                // Daemon.loadDataDirFromConfig() exactly — otherwise, whenever
                // app-config.xml's <dataDir> can't be resolved (e.g. process
                // launched from a working directory where AppConfig can't
                // locate the XML), this DB would silently end up in a
                // different folder than tasks.xml/credentials/daemon.log,
                // and edits made in the Settings panel would look like
                // they're going nowhere.
                dataDir = "C:\\OpsTools\\Data";
            }
            resolvedDataDir = new File(dataDir);
            return resolvedDataDir;
        }
    }

    private static Connection connection() {
        if (conn != null) return conn;
        synchronized (LOCK) {
            if (conn != null) return conn;
            File dir = dataDir();
            dir.mkdirs();
            File dbFile = new File(dir, DB_FILE_NAME);
            try {
                Class.forName("org.sqlite.JDBC");
                Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT)");
                }
                conn = c;
                migrateLegacyJsonIfPresent(dir, c);
            } catch (Exception e) {
                log.log(Level.SEVERE, "Failed to open/initialize app-settings database", e);
            }
            return conn;
        }
    }

    /**
     * One-time import of the legacy app-settings.json (used by versions
     * prior to the move to SQLite) into the settings table, so upgrading in
     * place doesn't silently reset every saved setting back to defaults.
     * Runs only if the table is currently empty; the JSON file itself is
     * left in place afterward (renamed with a .migrated suffix) rather than
     * deleted, purely as a safety net.
     */
    private static void migrateLegacyJsonIfPresent(File dir, Connection c) {
        File legacy = new File(dir, LEGACY_JSON_FILE_NAME);
        if (!legacy.exists()) return;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM settings")) {
            if (rs.next() && rs.getInt(1) > 0) return; // already has data — don't overwrite
        } catch (SQLException e) {
            log.log(Level.WARNING, "Could not check settings table before JSON migration", e);
            return;
        }
        try {
            String json = Files.readString(legacy.toPath(), StandardCharsets.UTF_8);
            Map<String, Object> parsed = MiniJson.parseObject(json);
            if (!parsed.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO settings (key, value) VALUES (?, ?) " +
                        "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
                    for (Map.Entry<String, Object> e : parsed.entrySet()) {
                        if (e.getValue() == null) continue;
                        ps.setString(1, e.getKey());
                        ps.setString(2, String.valueOf(e.getValue()));
                        ps.executeUpdate();
                    }
                }
                log.info("Migrated " + parsed.size() + " setting(s) from legacy app-settings.json into " + DB_FILE_NAME);
            }
            File renamed = new File(dir, LEGACY_JSON_FILE_NAME + ".migrated");
            legacy.renameTo(renamed);
        } catch (Exception e) {
            log.log(Level.WARNING, "Failed to migrate legacy app-settings.json", e);
        }
    }

    // ─── Load (with live reload) ────────────────────────────────────────────

    private static Map<String, String> current() {
        Map<String, String> snapshot = cache;
        long now = System.currentTimeMillis();
        if (snapshot != null && (now - cacheLoadedAtMillis) < CACHE_TTL_MILLIS) {
            return snapshot; // still fresh — no requery needed
        }
        synchronized (LOCK) {
            if (cache != null && (System.currentTimeMillis() - cacheLoadedAtMillis) < CACHE_TTL_MILLIS) {
                return cache;
            }
            Map<String, String> loaded = load();
            cache = loaded;
            cacheLoadedAtMillis = System.currentTimeMillis();
            return loaded;
        }
    }

    private static Map<String, String> load() {
        Map<String, String> result = new LinkedHashMap<>();
        Connection c = connection();
        if (c != null) {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT key, value FROM settings")) {
                while (rs.next()) {
                    String v = rs.getString("value");
                    if (v != null) result.put(rs.getString("key"), v);
                }
                if (!result.isEmpty()) return result;
            } catch (SQLException e) {
                log.log(Level.WARNING, "Failed to read app settings from database", e);
            }
        }
        // Empty table (first run) or DB unavailable: seed from app-config.xml
        // so upgrading in place doesn't silently change behavior, then
        // persist so the Settings panel has real rows to edit from now on.
        for (Map.Entry<String, String> seed : XML_SEED_TAG.entrySet()) {
            String xmlVal = AppConfig.readValue(seed.getValue());
            result.put(seed.getKey(), (xmlVal != null && !xmlVal.isEmpty())
                    ? xmlVal : HARD_DEFAULTS.get(seed.getKey()));
        }
        if (c != null) writeThroughLocked(c, result);
        return result;
    }

    // ─── Public read API ─────────────────────────────────────────────────────

    public static String get(String key) {
        String v = current().get(key);
        if (v != null && !v.isEmpty()) return v;
        return HARD_DEFAULTS.get(key);
    }

    public static String getLdmFolder()             { return get(KEY_LDM_FOLDER); }
    public static String getPtmFolder()              { return get(KEY_PTM_FOLDER); }
    public static String getOthersFolder()           { return get(KEY_OTHERS_FOLDER); }

    /**
     * The full, user-editable list of mail-routing rules (classification key
     * → Outlook folder name) shown in the Settings panel's routing table.
     * Replaces the old fixed LDM/PTM/Others text fields with an
     * arbitrary-length list.
     *
     * <p>On an install that hasn't been touched since before this list
     * existed, this seeds itself once from the old
     * {@code ldmFolder}/{@code ptmFolder}/{@code othersFolder} values (or
     * their hard defaults) — so upgrading in place doesn't reset anyone's
     * configured folder names — and persists that seeded list immediately
     * so future reads come straight from {@link #KEY_MAIL_ROUTING_RULES}.
     *
     * <p>Always guaranteed to contain at least one rule whose key is
     * {@link MailRoutingRule#OTHERS_KEY} (the fallback bucket) — a row with
     * that key is appended if the stored/seeded list is missing one.
     */
    public static List<MailRoutingRule> getMailRoutingRules() {
        String json = get(KEY_MAIL_ROUTING_RULES);
        List<MailRoutingRule> rules = new ArrayList<>();
        if (json != null && !json.trim().isEmpty()) {
            try {
                Object parsed = MiniJson.parse(json);
                if (parsed instanceof List) {
                    for (Object o : (List<?>) parsed) {
                        if (o instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> m = (Map<String, Object>) o;
                            String key = MiniJson.getString(m, "key", "");
                            String folder = MiniJson.getString(m, "folder", "");
                            if (!key.trim().isEmpty()) rules.add(new MailRoutingRule(key.trim(), folder.trim()));
                        }
                    }
                }
            } catch (Exception e) {
                log.log(Level.WARNING, "Could not parse " + KEY_MAIL_ROUTING_RULES + " — using defaults", e);
            }
        }

        if (rules.isEmpty()) {
            // First read ever, or the stored value was empty/corrupt: seed from
            // the legacy fixed fields (or their hard defaults) and persist so
            // this branch is only hit once.
            String ldm = get(KEY_LDM_FOLDER);
            String ptm = get(KEY_PTM_FOLDER);
            String others = get(KEY_OTHERS_FOLDER);
            if (ldm != null && !ldm.trim().isEmpty()) rules.add(new MailRoutingRule("LDM", ldm.trim()));
            if (ptm != null && !ptm.trim().isEmpty()) rules.add(new MailRoutingRule("PTM", ptm.trim()));
            rules.add(new MailRoutingRule(MailRoutingRule.OTHERS_KEY,
                    (others != null && !others.trim().isEmpty()) ? others.trim() : "Others"));
            setMailRoutingRules(rules);
            return rules;
        }

        // Guarantee exactly one fallback row exists, even if a user somehow
        // deleted it via the editor — routing code always needs somewhere to
        // send an unmatched message.
        boolean hasOthers = rules.stream().anyMatch(MailRoutingRule::isOthers);
        if (!hasOthers) {
            rules.add(new MailRoutingRule(MailRoutingRule.OTHERS_KEY, "Others"));
            setMailRoutingRules(rules);
        }
        return rules;
    }

    /** Persists the full mail-routing rule list. Takes effect on the very next message processed — no restart needed. */
    public static void setMailRoutingRules(List<MailRoutingRule> rules) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (MailRoutingRule r : rules) {
            if (r.getKey() == null || r.getKey().trim().isEmpty()) continue;
            Map<String, String> row = new LinkedHashMap<>();
            row.put("key", r.getKey().trim());
            row.put("folder", r.getFolder() == null ? "" : r.getFolder().trim());
            rows.add(row);
        }
        set(KEY_MAIL_ROUTING_RULES, MiniJson.writeArrayOfObjects(rows));
    }

    /**
     * Resolves the destination Outlook folder for a classified message: the
     * folder configured for the rule whose key matches {@code messageType}
     * (case-insensitive), or the {@link MailRoutingRule#OTHERS_KEY} rule's
     * folder if nothing matches (including when {@code messageType} is
     * {@code null}, i.e. the message didn't match any classification marker
     * at all).
     */
    public static String resolveRoutingFolder(String messageType) {
        List<MailRoutingRule> rules = getMailRoutingRules();
        if (messageType != null) {
            for (MailRoutingRule r : rules) {
                if (messageType.equalsIgnoreCase(r.getKey())) return r.getFolder();
            }
        }
        for (MailRoutingRule r : rules) {
            if (r.isOthers()) return r.getFolder();
        }
        return "Others"; // unreachable in practice — getMailRoutingRules() always guarantees an Others row
    }
    public static String getDefaultStationAddress()  { String v = get(KEY_DEFAULT_STATION_ADDR); return v == null || v.isEmpty() ? null : v; }
    /** Base folder attachments are downloaded under (each task's Attachments/&lt;LDM|PTM|Others&gt; subtree is created inside it). Empty/unset = fall back to the task's own output directory. */
    public static String getAttachmentDownloadLocation() { String v = get(KEY_ATTACHMENT_DOWNLOAD_DIR); return v == null || v.isEmpty() ? null : v; }
    public static String getLogLevel()               { return get(KEY_LOG_LEVEL); }
    /** Applies only on the JVM's NEXT start — see class javadoc. */
    public static String getJvmMinHeap()             { return get(KEY_JVM_MIN_HEAP); }
    /** Applies only on the JVM's NEXT start — see class javadoc. */
    public static String getJvmMaxHeap()             { return get(KEY_JVM_MAX_HEAP); }

    private static int intOrDefault(String key) {
        try {
            int v = Integer.parseInt(get(key));
            return v > 0 ? v : Integer.parseInt(HARD_DEFAULTS.get(key));
        } catch (Exception e) {
            return Integer.parseInt(HARD_DEFAULTS.get(key));
        }
    }

    public static int getTransferBatchTargetSeconds() {
        return intOrDefault(KEY_TRANSFER_BATCH_TARGET_SECONDS);
    }

    public static int getTransferAssumedThroughputMBps() {
        return intOrDefault(KEY_TRANSFER_ASSUMED_THROUGHPUT_MBPS);
    }

    public static int getTransferBatchIntervalSeconds() {
        try {
            int v = Integer.parseInt(get(KEY_TRANSFER_BATCH_INTERVAL_SECONDS));
            return v >= 0 ? v : Integer.parseInt(HARD_DEFAULTS.get(KEY_TRANSFER_BATCH_INTERVAL_SECONDS));
        } catch (Exception e) {
            return Integer.parseInt(HARD_DEFAULTS.get(KEY_TRANSFER_BATCH_INTERVAL_SECONDS));
        }
    }

    /**
     * How many batches are run concurrently (each its own WinSCP/SFTP
     * session) instead of one at a time. Defaults to 1 (sequential, matches
     * pre-existing behavior). Values &lt;= 1 mean sequential. Read live —
     * editable from the Settings panel, app-settings.json, or app-config.xml
     * — no restart required.
     */
    public static int getTransferBatchConcurrency() {
        return Math.max(1, intOrDefault(KEY_TRANSFER_BATCH_CONCURRENCY));
    }

    /**
     * How many minutes a task may remain RUNNING before the scheduler treats
     * it as stale (crashed/hung) and force-cancels it. Defaults to 30.
     * Interval-scheduled tasks additionally scale this up relative to their
     * own interval (see {@code TaskSchedulerService.getStaleRunningThreshold}),
     * but this value is always the floor. Read live — editable from the
     * Settings panel, app-settings.json, or app-config.xml — no restart
     * required.
     */
    public static int getStaleRunningThresholdMinutes() {
        return Math.max(1, intOrDefault(KEY_STALE_RUNNING_THRESHOLD_MINUTES));
    }

    /**
     * How many minutes a RUNNING task may go without emitting any new log
     * line before it's treated as network-stalled and force-cancelled —
     * see {@link #KEY_STALE_INACTIVITY_THRESHOLD_MINUTES}. Defaults to 5.
     * Read live — editable from the Settings panel, app-settings.json, or
     * app-config.xml — no restart required.
     */
    public static int getStaleInactivityThresholdMinutes() {
        return Math.max(1, intOrDefault(KEY_STALE_INACTIVITY_THRESHOLD_MINUTES));
    }

    /**
     * Ceiling on concurrent task-execution worker threads — see
     * {@link #KEY_MAX_CONCURRENT_TASK_THREADS}. Defaults to 20. Read once
     * when TaskSchedulerService starts; changing it requires a restart.
     */
    public static int getMaxConcurrentTaskThreads() {
        return Math.max(1, intOrDefault(KEY_MAX_CONCURRENT_TASK_THREADS));
    }

    /**
     * Max total bytes moved per batch (WinSCP session, or local-copy progress
     * chunk) before pausing for {@link #getTransferBatchIntervalSeconds()}
     * and starting the next batch. If an explicit override (&gt; 0) is set via
     * {@link #KEY_TRANSFER_BATCH_MAX_BYTES}, it is used as-is; otherwise the
     * cap is derived from the target-seconds / assumed-throughput pair so the
     * *default* results in each batch completing in roughly
     * {@link #getTransferBatchTargetSeconds()} seconds. Read live on every
     * transfer — editable from the Settings panel, app-settings.json, or
     * app-config.xml — no restart required.
     */
    public static long getTransferBatchMaxBytes() {
        try {
            long override = Long.parseLong(get(KEY_TRANSFER_BATCH_MAX_BYTES));
            if (override > 0) return override;
        } catch (Exception ignored) { /* fall through to derived value */ }
        long throughputBytesPerSec = (long) getTransferAssumedThroughputMBps() * 1024L * 1024L;
        long targetSeconds = getTransferBatchTargetSeconds();
        long derived = throughputBytesPerSec * targetSeconds;
        return derived > 0 ? derived : 26_214_400L; // 25MB absolute fallback
    }

    // ─── Public write API ────────────────────────────────────────────────────

    /** Sets and immediately persists a single value. Visible to the next read from any process/thread. */
    public static void set(String key, String value) {
        synchronized (LOCK) {
            Map<String, String> base = new LinkedHashMap<>(current());
            if (value == null || value.isEmpty()) base.remove(key); else base.put(key, value);
            Connection c = connection();
            if (c != null) writeThroughLocked(c, base);
            cache = base;
            cacheLoadedAtMillis = System.currentTimeMillis();
        }
    }

    /** Sets and immediately persists several values in one transaction (preferred for a Settings-panel "Save"). */
    public static void setAll(Map<String, String> values) {
        synchronized (LOCK) {
            Map<String, String> base = new LinkedHashMap<>(current());
            for (Map.Entry<String, String> e : values.entrySet()) {
                if (e.getValue() == null || e.getValue().isEmpty()) base.remove(e.getKey());
                else base.put(e.getKey(), e.getValue());
            }
            Connection c = connection();
            if (c != null) writeThroughLocked(c, base);
            cache = base;
            cacheLoadedAtMillis = System.currentTimeMillis();
        }
    }

    /** Caller must hold LOCK. Replaces the entire settings table contents with {@code values}. */
    private static void writeThroughLocked(Connection c, Map<String, String> values) {
        try {
            boolean priorAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try (Statement del = c.createStatement()) {
                del.execute("DELETE FROM settings");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO settings (key, value) VALUES (?, ?)")) {
                for (Map.Entry<String, String> e : values.entrySet()) {
                    ps.setString(1, e.getKey());
                    ps.setString(2, e.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
            c.setAutoCommit(priorAutoCommit);
        } catch (SQLException ex) {
            throw new RuntimeException("Could not save settings to " + DB_FILE_NAME + ": " + ex.getMessage(), ex);
        }
    }

    /** Absolute path of the backing SQLite database, for display in the Settings panel. */
    public static String filePath() {
        return new File(dataDir(), DB_FILE_NAME).getAbsolutePath();
    }
}
