package util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Live, JSON-backed store for settings that should take effect immediately —
 * without an application (or JVM) restart — and that the Settings panel lets
 * the user edit directly.
 *
 * <p>This is deliberately a separate file/format from {@code app-config.xml}:
 * <ul>
 *   <li><b>app-config.xml</b> stays the *install-time* file consumed by
 *       {@code setup.ps1} (install paths, daemon registration, JVM heap
 *       flags the launcher passes on the java command line, etc). Most of
 *       it can only take effect on install/relaunch, so there's little value
 *       in hot-reloading it, and doing so would risk the installer and the
 *       running app disagreeing about what's "current".</li>
 *   <li><b>app-settings.json</b> (this class) holds the handful of values
 *       that genuinely are read on every message/task run and can safely
 *       change underneath a running app: mail routing folder names, the
 *       default SITA station address, the attachment download location, and
 *       the log level. JSON also happens to be a much more natural fit than
 *       XML for a settings blob a GUI reads/writes on every save.</li>
 * </ul>
 *
 * <p><b>Real-time behavior:</b> every getter re-stats the backing file and
 * reparses it if its {@code lastModified} timestamp has moved on since the
 * last read. A {@code stat()} call is cheap enough to do on every read (this
 * is not a hot loop — at most a few times per processed message), so no file
 * watcher/polling thread is needed: edits made in the Settings panel (or by
 * hand-editing the JSON) are picked up by the very next read, whether that
 * read happens in the GUI thread or the background daemon process.
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

    private static final String FILE_NAME = "app-settings.json";

    // Keys
    public static final String KEY_LDM_FOLDER            = "ldmFolder";
    public static final String KEY_PTM_FOLDER             = "ptmFolder";
    public static final String KEY_OTHERS_FOLDER          = "othersFolder";
    public static final String KEY_DEFAULT_STATION_ADDR   = "defaultStationAddress";
    public static final String KEY_ATTACHMENT_DOWNLOAD_DIR = "attachmentDownloadLocation";
    public static final String KEY_LOG_LEVEL              = "logLevel";
    public static final String KEY_JVM_MIN_HEAP           = "jvmMinHeap";
    public static final String KEY_JVM_MAX_HEAP           = "jvmMaxHeap";
    public static final String KEY_TRANSFER_BATCH_SIZE     = "transferBatchSize";

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
        HARD_DEFAULTS.put(KEY_JVM_MAX_HEAP, "2G");
        HARD_DEFAULTS.put(KEY_TRANSFER_BATCH_SIZE, "50");
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
        XML_SEED_TAG.put(KEY_TRANSFER_BATCH_SIZE, "transferBatchSize");
    }

    private static final Object LOCK = new Object();
    private static volatile Map<String, String> cache;
    private static volatile long cachedFileMtime = -1;
    private static volatile File resolvedFile;

    private AppSettings() {}

    // ─── File resolution ────────────────────────────────────────────────────

    private static File file() {
        if (resolvedFile != null) return resolvedFile;
        synchronized (LOCK) {
            if (resolvedFile != null) return resolvedFile;
            String dataDir = AppConfig.readValue("dataDir");
            if (dataDir == null || dataDir.isEmpty()) {
                // Must match the fallback used by MainWindow.loadDataDir() and
                // Daemon.loadDataDirFromConfig() exactly — otherwise, whenever
                // app-config.xml's <dataDir> can't be resolved (e.g. process
                // launched from a working directory where AppConfig can't
                // locate the XML), this file would silently end up in a
                // different folder than tasks.xml/credentials/daemon.log,
                // and edits made in the Settings panel would look like
                // they're going nowhere.
                dataDir = "C:\\OpsTools\\Data";
            }
            resolvedFile = new File(dataDir, FILE_NAME);
            return resolvedFile;
        }
    }

    // ─── Load (with live reload) ────────────────────────────────────────────

    private static Map<String, String> current() {
        File f = file();
        long mtime = f.exists() ? f.lastModified() : 0L;
        Map<String, String> snapshot = cache;
        if (snapshot != null && mtime == cachedFileMtime) {
            return snapshot; // unchanged since last read — no reparse needed
        }
        synchronized (LOCK) {
            mtime = f.exists() ? f.lastModified() : 0L;
            if (cache != null && mtime == cachedFileMtime) return cache;
            Map<String, String> loaded = load(f);
            cache = loaded;
            cachedFileMtime = mtime;
            return loaded;
        }
    }

    private static Map<String, String> load(File f) {
        Map<String, String> result = new LinkedHashMap<>();
        if (f.exists()) {
            try {
                String json = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                Map<String, Object> parsed = MiniJson.parseObject(json);
                for (Map.Entry<String, Object> e : parsed.entrySet()) {
                    if (e.getValue() != null) result.put(e.getKey(), String.valueOf(e.getValue()));
                }
                return result;
            } catch (Exception ignored) {
                // fall through to a fresh seed if the file is missing/corrupt
            }
        }
        // First run (or unreadable file): seed from app-config.xml so
        // upgrading in place doesn't silently change behavior, then persist
        // so the Settings panel has a real file to edit from now on.
        for (Map.Entry<String, String> seed : XML_SEED_TAG.entrySet()) {
            String xmlVal = AppConfig.readValue(seed.getValue());
            result.put(seed.getKey(), (xmlVal != null && !xmlVal.isEmpty())
                    ? xmlVal : HARD_DEFAULTS.get(seed.getKey()));
        }
        try {
            persist(f, result);
        } catch (IOException ignored) {
            // best-effort — in-memory defaults still apply for this run
        }
        return result;
    }

    private static void persist(File f, Map<String, String> values) throws IOException {
        File parent = f.getParentFile();
        if (parent != null) Files.createDirectories(parent.toPath());
        Files.writeString(f.toPath(), MiniJson.writeObject(values), StandardCharsets.UTF_8);
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
    public static String getDefaultStationAddress()  { String v = get(KEY_DEFAULT_STATION_ADDR); return v == null || v.isEmpty() ? null : v; }
    /** Base folder attachments are downloaded under (each task's Attachments/&lt;LDM|PTM|Others&gt; subtree is created inside it). Empty/unset = fall back to the task's own output directory. */
    public static String getAttachmentDownloadLocation() { String v = get(KEY_ATTACHMENT_DOWNLOAD_DIR); return v == null || v.isEmpty() ? null : v; }
    public static String getLogLevel()               { return get(KEY_LOG_LEVEL); }
    /** Applies only on the JVM's NEXT start — see class javadoc. */
    public static String getJvmMinHeap()             { return get(KEY_JVM_MIN_HEAP); }
    /** Applies only on the JVM's NEXT start — see class javadoc. */
    public static String getJvmMaxHeap()             { return get(KEY_JVM_MAX_HEAP); }

    /**
     * Max number of files sent per WinSCP session (or per local-copy progress
     * chunk) when a transfer would otherwise move more files than this in one
     * go. Read live on every transfer — editable from the Settings panel or
     * by hand-editing app-settings.json, no restart required.
     */
    public static int getTransferBatchSize() {
        try {
            int v = Integer.parseInt(get(KEY_TRANSFER_BATCH_SIZE));
            return v > 0 ? v : Integer.parseInt(HARD_DEFAULTS.get(KEY_TRANSFER_BATCH_SIZE));
        } catch (Exception e) {
            return Integer.parseInt(HARD_DEFAULTS.get(KEY_TRANSFER_BATCH_SIZE));
        }
    }

    // ─── Public write API ────────────────────────────────────────────────────

    /** Sets and immediately persists a single value. Visible to the next read from any process/thread. */
    public static void set(String key, String value) {
        Map<String, String> updated;
        synchronized (LOCK) {
            Map<String, String> base = new LinkedHashMap<>(current());
            if (value == null || value.isEmpty()) base.remove(key); else base.put(key, value);
            updated = base;
            writeThrough(updated);
        }
    }

    /** Sets and immediately persists several values in one file write (preferred for a Settings-panel "Save"). */
    public static void setAll(Map<String, String> values) {
        synchronized (LOCK) {
            Map<String, String> base = new LinkedHashMap<>(current());
            for (Map.Entry<String, String> e : values.entrySet()) {
                if (e.getValue() == null || e.getValue().isEmpty()) base.remove(e.getKey());
                else base.put(e.getKey(), e.getValue());
            }
            writeThrough(base);
        }
    }

    /** Caller must hold LOCK. */
    private static void writeThrough(Map<String, String> values) {
        File f = file();
        try {
            persist(f, values);
            cache = values;
            cachedFileMtime = f.exists() ? f.lastModified() : System.currentTimeMillis();
        } catch (IOException ex) {
            throw new RuntimeException("Could not save " + f + ": " + ex.getMessage(), ex);
        }
    }

    /** Absolute path of the backing JSON file, for display in the Settings panel. */
    public static String filePath() {
        return file().getAbsolutePath();
    }
}
