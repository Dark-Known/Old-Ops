package service;

import model.Credential;

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
 * <p>Database file lives at {@code <dataDir>/credentials.db}, one row per
 * credential in a {@code credentials} table keyed by username (matching the
 * old one-file-per-username invariant). A single shared {@link Connection}
 * is kept open and all access is synchronized — write volume here is a
 * handful of edits per session, so a connection pool would be overkill, and
 * SQLite only supports one writer at a time regardless.
 *
 * <p>On first use, if the table is empty and legacy {@code creds_*.xml}
 * files are found in the data directory, they're imported once (see
 * {@link #migrateLegacyXmlIfPresent}) so upgrading in place doesn't lose
 * anyone's saved credentials. The XML files are left on disk afterward
 * (renamed with a {@code .migrated} suffix) purely as a safety net.
 */
public class CredentialDbService {

    private static final Logger log = Logger.getLogger(CredentialDbService.class.getName());

    private final Connection conn;
    private final File dataDir;

    public CredentialDbService(File dataDir) {
        this.dataDir = dataDir;
        File dbFile = new File(dataDir, "credentials.db");
        Connection c = null;
        try {
            Class.forName("org.sqlite.JDBC");
            c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS credentials (" +
                        "id TEXT PRIMARY KEY," +
                        "name TEXT," +
                        "host TEXT," +
                        "username TEXT UNIQUE NOT NULL," +
                        "password TEXT," +
                        "os_type TEXT" +
                        ")");
            }
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to open/initialize credentials database", e);
        }
        this.conn = c;
        if (this.conn != null) migrateLegacyXmlIfPresent();
    }

    /** Look up a credential by username, or null if none is stored. */
    public synchronized Credential loadByUsername(String username) {
        if (conn == null || username == null || username.isEmpty()) return null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM credentials WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to load credential for username " + username, e);
        }
        return null;
    }

    /** Inserts or replaces (by username) a credential. Assigns an id if missing. */
    public synchronized void save(Credential cred) {
        if (conn == null) return;
        if (cred.getId() == null || cred.getId().isEmpty()) {
            cred.setId(java.util.UUID.randomUUID().toString());
        }
        String sql = "INSERT INTO credentials (id, name, host, username, password, os_type) VALUES (?,?,?,?,?,?) " +
                "ON CONFLICT(username) DO UPDATE SET id=excluded.id, name=excluded.name, host=excluded.host, " +
                "password=excluded.password, os_type=excluded.os_type";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cred.getId());
            ps.setString(2, cred.getName());
            ps.setString(3, cred.getHost());
            ps.setString(4, cred.getUsername());
            ps.setString(5, cred.getPassword());
            ps.setString(6, cred.getOsType());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to save credential for username " + cred.getUsername(), e);
        }
    }

    /** Deletes the credential for the given username, if any. */
    public synchronized void delete(String username) {
        if (conn == null || username == null || username.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM credentials WHERE username = ?")) {
            ps.setString(1, username);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Failed to delete credential for username " + username, e);
        }
    }

    /** All stored credentials. */
    public synchronized List<Credential> loadAll() {
        List<Credential> list = new ArrayList<>();
        if (conn == null) return list;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM credentials ORDER BY name")) {
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
        c.setPassword(rs.getString("password"));
        c.setOsType(rs.getString("os_type"));
        return c;
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
                    c.setName(text(e, "name"));
                    c.setHost(text(e, "host"));
                    c.setUsername(text(e, "username"));
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
            log.info("Migrated " + migrated + " credential(s) from legacy creds_*.xml files into credentials.db");
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
