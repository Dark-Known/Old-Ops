package service;

import model.Credential;
import util.AppConfig;
import util.CryptoUtil;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Stores server credentials in a small SQLite database instead of one
 * {@code creds_<username>.xml} file per user.
 *
 * <p>Database file lives at {@code <dataDir>/app.db} — shared with
 * {@link util.AppSettings}'s own {@code settings} table, each service using
 * its own table ({@code credentials} here) in the same file rather than a
 * separate {@code .db} file per concern. One row per credential in the
 * {@code credentials} table, identified by a unique credential
 * <i>name</i> (the login username is not unique: the same one can exist on many servers). A single shared {@link Connection} is
 * kept open and all access is synchronized — write volume here is a handful
 * of edits per session, so a connection pool would be overkill, and SQLite
 * only supports one writer at a time regardless.
 *
 * <p>On first use: if a legacy standalone {@code credentials.db} file is
 * found (from before the merge into {@code app.db}) and the
 * {@code credentials} table is currently empty, its rows are imported once
 * via {@code ATTACH DATABASE} (see {@link #migrateLegacyCredentialsDbIfPresent})
 * and the old file renamed with a {@code .migrated} suffix as a safety net.
 * Failing that, any legacy {@code creds_*.xml} files are imported the same
 * way they always were (see {@link #migrateLegacyXmlIfPresent}).
 */
public class CredentialDbService {

    private static final Logger log = Logger.getLogger(CredentialDbService.class.getName());

    private final Connection conn;
    private final File dataDir;
    // Mirrors TaskDbService's connectionError — see its javadoc for why this
    // is tracked instead of just logging once and moving on.
    private final String connectionError;

    /**
     * Tunes this connection for a desktop app with three independent writers
     * sharing one app.db file — see {@code TaskDbService#applyPragmas}'s
     * javadoc for the full reasoning (WAL mode, synchronous=NORMAL, and a
     * busy_timeout). PRAGMA journal_mode persists at the database-file level
     * once set by any connection, but synchronous and busy_timeout are
     * per-connection, so each of the three services opening their own
     * connection to app.db needs to set these itself.
     */
    private static void applyPragmas(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA busy_timeout=5000");
        }
    }

    /**
     * A credential is identified by its <b>name</b>, which is unique (the same login username can exist on many
     * servers). Tasks refer to a credential by that name. Column order is the same as in older builds so a legacy
     * {@code credentials.db} can still be imported column by column.
     */
    private static final String CREATE_TABLE_SQL = "CREATE TABLE IF NOT EXISTS %T% (" +
            "id TEXT PRIMARY KEY," +
            "name TEXT NOT NULL UNIQUE," +
            "host TEXT," +
            "username TEXT NOT NULL," +
            "password TEXT," +
            "os_type TEXT" +
            ")";

    /** Marks a password column value as encrypted; anything else is legacy plaintext. */
    private static final String ENC_PREFIX = "enc:";

    /**
     * Encryption is on unless app-config.xml explicitly sets
     * {@code <credentialEncryption>false</credentialEncryption>}. Reading always
     * understands both forms, so flipping the setting never makes rows unreadable.
     */
    private static boolean encryptionEnabled() {
        String v = AppConfig.readValue("credentialEncryption");
        return v == null || !v.trim().equalsIgnoreCase("false");
    }

    private static String protect(String plain) {
        if (plain == null || plain.isEmpty() || !encryptionEnabled()) return plain;
        return ENC_PREFIX + CryptoUtil.encrypt(plain);
    }

    private static String reveal(String stored) {
        if (stored == null || !stored.startsWith(ENC_PREFIX)) return stored; // legacy plaintext
        return CryptoUtil.decrypt(stored.substring(ENC_PREFIX.length()));
    }

    public CredentialDbService(File dataDir) {
        this.dataDir = dataDir;
        CryptoUtil.configure(dataDir);
        util.SshHostKeys.configure(dataDir);
        File dbFile = new File(dataDir, "app.db");
        Connection c = null;
        String initError = null;
        try {
            Class.forName("org.sqlite.JDBC");
            c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            applyPragmas(c);
            try (Statement st = c.createStatement()) {
                st.execute(CREATE_TABLE_SQL.replace("%T%", "credentials"));
            }
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to open/initialize credentials table in app.db", e);
            initError = (e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        this.conn = c;
        this.connectionError = initError;
        if (this.conn != null) {
            migrateToNamedCredentials();
            migrateLegacyCredentialsDbIfPresent();
            migrateLegacyXmlIfPresent();
            encryptPlaintextRows();
        }
    }

    /** One-time upgrade: re-writes any plaintext password rows in the encrypted form. */
    private synchronized void encryptPlaintextRows() {
        if (!encryptionEnabled()) return;
        try {
            List<String[]> todo = new ArrayList<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, password FROM credentials")) {
                while (rs.next()) {
                    String pw = rs.getString(2);
                    if (pw != null && !pw.isEmpty() && !pw.startsWith(ENC_PREFIX)) {
                        todo.add(new String[]{rs.getString(1), pw});
                    }
                }
            }
            if (todo.isEmpty()) return;
            try (PreparedStatement ps = conn.prepareStatement("UPDATE credentials SET password = ? WHERE id = ?")) {
                for (String[] row : todo) {
                    ps.setString(1, protect(row[1]));
                    ps.setString(2, row[0]);
                    ps.executeUpdate();
                }
            }
            log.info("Encrypted " + todo.size() + " plaintext credential password(s) in app.db");
        } catch (Exception e) {
            log.log(Level.WARNING, "Could not encrypt existing credential passwords", e);
        }
    }

    // See TaskDbService#lastSaveError for why this is tracked separately from
    // connectionError.
    private volatile String lastSaveError;

    /** True if app.db's credentials table opened successfully AND the
     *  connection is still alive right now — see TaskDbService#isConnected
     *  for why this is checked live rather than from a startup snapshot. */
    public boolean isConnected() {
        if (conn == null) return false;
        try {
            return conn.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    /** Short exception summary from the failed connection attempt, or null if connected fine. */
    public String getConnectionError() {
        return connectionError;
    }

    /** Lets the storage facade report a failure of a larger operation (a rename that could not update its tasks). */
    void setLastSaveError(String message) { this.lastSaveError = message; }

    /** Short exception summary from the most recent failed {@link #save}, or
     *  null if the last save succeeded. See TaskDbService#getLastSaveError. */
    public String getLastSaveError() {
        return lastSaveError;
    }

    /**
     * One-time upgrade of a database from before credentials had names: the old table made the login username the
     * unique key. The table is rebuilt so the <i>name</i> is the key, and every existing credential gets its old
     * username as its name. Tasks already refer to credentials by that username, so no task needs to change; rename
     * a credential afterwards from the Credentials page and the tasks that use it follow.
     */
    private void migrateToNamedCredentials() {
        try {
            String ddl = null;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='credentials'")) {
                if (rs.next()) ddl = rs.getString(1);
            }
            if (ddl == null || !ddl.replaceAll("\\s+", " ").toUpperCase().contains("USERNAME TEXT UNIQUE")) return;

            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS credentials_new");
                st.execute(CREATE_TABLE_SQL.replace("%T%", "credentials_new"));
                st.execute("INSERT INTO credentials_new (id, name, host, username, password, os_type) "
                        + "SELECT id, username, host, username, password, os_type FROM credentials");
                st.execute("DROP TABLE credentials");
                st.execute("ALTER TABLE credentials_new RENAME TO credentials");
                conn.commit();
                log.info("Credentials are now identified by a unique name; existing credentials were named after their username.");
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.log(Level.SEVERE, "Could not upgrade the credentials table to named credentials", e);
        }
    }

    private static String clean(String s) { return s == null ? "" : s.trim(); }

    /** Look up a credential by its name (exact match first, then ignoring case), or null if none is stored. */
    public synchronized Credential loadByName(String name) {
        name = clean(name);
        if (conn == null || name.isEmpty()) return null;
        try {
            for (String sql : new String[]{"SELECT * FROM credentials WHERE name = ?",
                                           "SELECT * FROM credentials WHERE name = ? COLLATE NOCASE"}) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, name);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) return mapRow(rs);
                    }
                }
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to load credential named " + name, e);
        }
        return null;
    }

    /** Look up a credential by its id, or null. */
    public synchronized Credential loadById(String id) {
        if (conn == null || id == null || id.isEmpty()) return null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM credentials WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to load credential " + id, e);
        }
        return null;
    }

    /** True when another credential (not {@code exceptId}) already uses this name, ignoring case. */
    public synchronized boolean isNameTaken(String name, String exceptId) {
        name = clean(name);
        if (conn == null || name.isEmpty()) return false;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM credentials WHERE name = ? COLLATE NOCASE AND id <> ?")) {
            ps.setString(1, name);
            ps.setString(2, exceptId == null ? "" : exceptId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to check credential name " + name, e);
            return false;
        }
    }

    /**
     * Inserts a new credential or updates the one with the same id (so a credential can be renamed).
     * A blank name defaults to {@code username@host}. Refuses a name another credential already has.
     * Returns whether it was actually persisted (see TaskDbService#save's javadoc for why this matters).
     */
    public synchronized boolean save(Credential cred) {
        if (conn == null) return false;
        if (cred.getId() == null || cred.getId().isEmpty()) {
            cred.setId(java.util.UUID.randomUUID().toString());
        }
        cred.setName(clean(cred.getName()).isEmpty()
                ? clean(cred.getUsername()) + "@" + clean(cred.getHost()) : clean(cred.getName()));
        if (isNameTaken(cred.getName(), cred.getId())) {
            lastSaveError = "A credential named \"" + cred.getName() + "\" already exists - choose a different name";
            return false;
        }
        String sql = "INSERT INTO credentials (id, name, host, username, password, os_type) VALUES (?,?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET name=excluded.name, host=excluded.host, " +
                "username=excluded.username, password=excluded.password, os_type=excluded.os_type";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cred.getId());
            ps.setString(2, cred.getName());
            ps.setString(3, cred.getHost());
            ps.setString(4, cred.getUsername());
            ps.setString(5, protect(cred.getPassword()));
            ps.setString(6, cred.getOsType());
            ps.executeUpdate();
            lastSaveError = null;
            return true;
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to save credential " + cred.getName(), e);
            lastSaveError = e.getClass().getSimpleName() + ": " + e.getMessage();
            return false;
        }
    }

    /** Deletes the credential with the given name, if any. Returns whether
     *  it was actually removed — see TaskDbService#save's javadoc for why
     *  this matters. */
    public synchronized boolean delete(String name) {
        if (conn == null || name == null || name.isEmpty()) return false;
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM credentials WHERE name = ?")) {
            ps.setString(1, name);
            ps.executeUpdate();
            lastSaveError = null;
            return true;
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to delete credential " + name, e);
            lastSaveError = e.getClass().getSimpleName() + ": " + e.getMessage();
            return false;
        }
    }

    /** All stored credentials. */
    public synchronized List<Credential> loadAll() {
        List<Credential> list = new ArrayList<>();
        if (conn == null) return list;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM credentials ORDER BY name COLLATE NOCASE")) {
            while (rs.next()) list.add(mapRow(rs));
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to load credentials", e);
        }
        return list;
    }

    private Credential mapRow(ResultSet rs) throws SQLException {
        Credential c = new Credential();
        c.setId(rs.getString("id"));
        c.setName(rs.getString("name"));
        c.setHost(rs.getString("host"));
        c.setUsername(rs.getString("username"));
        try {
            c.setPassword(reveal(rs.getString("password")));
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Could not decrypt password for " + c.getName()
                    + " (key file missing or changed?) - re-enter it in the credentials screen", e);
            c.setPassword("");
        }
        c.setOsType(rs.getString("os_type"));
        return c;
    }

    /**
     * One-time import from a legacy standalone {@code credentials.db} file
     * (from before credentials and app settings were merged into one
     * {@code app.db}), run only if the {@code credentials} table in the new
     * file is currently empty. Uses {@code ATTACH DATABASE} to copy rows
     * directly rather than re-implementing the old file's read logic in
     * Java. The old file is left on disk afterward (renamed with a
     * {@code .migrated} suffix) purely as a safety net.
     */
    private void migrateLegacyCredentialsDbIfPresent() {
        File legacy = new File(dataDir, "credentials.db");
        if (!legacy.exists()) return;

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM credentials")) {
            if (rs.next() && rs.getInt(1) > 0) return; // already has data — don't overwrite
        } catch (SQLException e) {
            log.log(Level.WARNING, "Could not check credentials table before legacy DB migration", e);
            return;
        }

        try (Statement st = conn.createStatement()) {
            st.execute("ATTACH DATABASE '" + legacy.getAbsolutePath().replace("'", "''") + "' AS legacy_creds");
            int migrated;
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM legacy_creds.credentials")) {
                migrated = rs.next() ? rs.getInt(1) : 0;
            }
            st.execute("INSERT INTO credentials (id, name, host, username, password, os_type) "
                    + "SELECT id, username, host, username, password, os_type FROM legacy_creds.credentials");
            st.execute("DETACH DATABASE legacy_creds");
            if (migrated > 0) {
                log.info("Migrated " + migrated + " credential(s) from legacy credentials.db into app.db");
            }
            File renamed = new File(legacy.getParentFile(), legacy.getName() + ".migrated");
            legacy.renameTo(renamed);
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to migrate legacy credentials.db into app.db", e);
        }
    }

    /**
     * One-time import of any {@code creds_<username>.xml} files found in
     * the data directory, run only if the credentials table is currently
     * empty. Mirrors the parsing XmlStorageService previously did for that
     * file layout.
     */
    private void migrateLegacyXmlIfPresent() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM credentials")) {
            if (rs.next() && rs.getInt(1) > 0) return; // already has data
        } catch (SQLException e) {
            log.log(Level.WARNING, "Could not check credentials table before XML migration", e);
            return;
        }

        File[] files = dataDir.listFiles((dir, name) -> name.startsWith("creds_") && name.endsWith(".xml"));
        if (files == null || files.length == 0) return;

        int migrated = 0;
        for (File f : files) {
            try {
                javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
                org.w3c.dom.Document doc = dbf.newDocumentBuilder().parse(f);
                org.w3c.dom.NodeList nodes = doc.getElementsByTagName("credential");
                for (int i = 0; i < nodes.getLength(); i++) {
                    org.w3c.dom.Element e = (org.w3c.dom.Element) nodes.item(i);
                    Credential c = new Credential();
                    c.setId(e.getAttribute("id"));
                    c.setHost(text(e, "host"));
                    c.setUsername(text(e, "username"));
                    c.setName(c.getUsername());   // tasks of that era refer to the username
                    c.setPassword(text(e, "password"));
                    c.setOsType(text(e, "osType"));
                    if (c.getUsername() != null && !c.getUsername().isEmpty()) {
                        save(c);
                        migrated++;
                    }
                }
                File renamed = new File(f.getParentFile(), f.getName() + ".migrated");
                f.renameTo(renamed);
            } catch (Exception ex) {
                log.log(Level.WARNING, "Failed to migrate legacy credential file " + f.getName(), ex);
            }
        }
        if (migrated > 0) {
            log.info("Migrated " + migrated + " credential(s) from legacy creds_*.xml files into app.db");
        }
    }

    private static String text(org.w3c.dom.Element parent, String tag) {
        org.w3c.dom.NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() == 0) return null;
        return nl.item(0).getTextContent();
    }

    public synchronized void close() {
        if (conn != null) {
            try { conn.close(); } catch (SQLException ignored) {}
        }
    }
}
