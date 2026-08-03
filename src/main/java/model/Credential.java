package model;

/**
 * Represents stored credentials for a server.
 * Passwords are stored as plain text in per-user XML files (creds_<username>.xml).
 */
public class Credential {
    private String id;
    private String name;       // display label
    private String host;       // hostname / IP
    private String username;   // login username
    private String password;   // plain-text password (stored in per-user XML)
    private String osType;     // WINDOWS or LINUX

    public Credential() {}

    public Credential(String id, String name, String host,
                      String username, String password, String osType) {
        this.id = id;
        this.name = name;
        this.host = host;
        this.username = username;
        this.password = password;
        this.osType = osType;
    }

    public String getId()                   { return id; }
    public void   setId(String id)          { this.id = id; }

    public String getName()                 { return name; }
    public void   setName(String name)      { this.name = name; }

    public String getHost()                 { return host; }
    public void   setHost(String host)      { this.host = host; }

    public String getUsername()             { return username; }
    public void   setUsername(String u)     { this.username = u; }

    /** Plain-text password – resolved from the per-user creds XML at runtime. */
    public String getPassword()             { return password; }
    public void   setPassword(String p)     { this.password = p; }

    /** @deprecated Use {@link #getPassword()} – kept for internal call-site compatibility. */
    @Deprecated
    public String getEncryptedPassword()    { return password; }
    @Deprecated
    public void   setEncryptedPassword(String p) { this.password = p; }

    public String getOsType()               { return osType; }
    public void   setOsType(String osType)  { this.osType = osType; }

    @Override public String toString()      { return name + " (" + host + ")"; }
}
