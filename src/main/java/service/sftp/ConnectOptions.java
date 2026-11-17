package service.sftp;

/**
 * How to open an SFTP connection: the one place the port and the two timeouts are decided, so every caller
 * (transfers, the folder browser, the connection test, the metadata lookup) opens a connection the same way
 * and differs only by these numbers.
 */
public final class ConnectOptions {
    public static final int DEFAULT_PORT = 22;

    public final int port;
    /** Time allowed to reach the host and authenticate. */
    public final int sessionTimeoutMs;
    /** Time allowed to open the SFTP subsystem once authenticated. */
    public final int channelTimeoutMs;

    private ConnectOptions(int port, int sessionTimeoutMs, int channelTimeoutMs) {
        this.port = port;
        this.sessionTimeoutMs = sessionTimeoutMs;
        this.channelTimeoutMs = channelTimeoutMs;
    }

    public static ConnectOptions of(int port, int sessionTimeoutMs, int channelTimeoutMs) {
        return new ConnectOptions(port, sessionTimeoutMs, channelTimeoutMs);
    }

    /** Port 22 with the same timeout for the session and the channel. */
    public static ConnectOptions timeout(int timeoutMs) {
        return new ConnectOptions(DEFAULT_PORT, timeoutMs, timeoutMs);
    }

    public ConnectOptions withPort(int newPort) {
        return new ConnectOptions(newPort, sessionTimeoutMs, channelTimeoutMs);
    }
}
