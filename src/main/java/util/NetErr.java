package util;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;

/** Turns a bare network exception (often "ConnectException: null") into a message that says what actually happened. */
public final class NetErr {
    private NetErr() {}

    /** True when the connection was never established, so the request cannot have reached the server (safe to repeat). */
    public static boolean neverConnected(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            if (x instanceof ConnectException || x instanceof HttpConnectTimeoutException
                    || x instanceof java.net.UnknownHostException || x instanceof java.net.NoRouteToHostException) return true;
        }
        return false;
    }

    /** Failures that usually clear on their own: connect problems, resets, a connection the server closed. */
    public static boolean transientFailure(Throwable t) {
        if (neverConnected(t)) return true;
        for (Throwable x = t; x != null; x = x.getCause()) {
            if (x instanceof HttpTimeoutException || x instanceof java.net.SocketException || x instanceof ClosedChannelException
                    || x instanceof javax.net.ssl.SSLException) return true;
            String m = x.getMessage();
            if (m != null && (m.contains("GOAWAY") || m.contains("closed") || m.contains("reset"))) return true;
        }
        return false;
    }

    /**
     * "ConnectException: connection to graph.microsoft.com:443 (20.190.x.x, 52.x.x.x) failed after 3.1 s - nothing answered
     * (firewall, proxy, DNS or the network dropped it); cause: ..." instead of "ConnectException: null".
     */
    public static String describe(Throwable t, URI uri, long millis, long connectTimeoutMs) {
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        String host = uri == null ? null : uri.getHost();
        int port = uri == null ? -1 : uri.getPort() > 0 ? uri.getPort() : "http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443;
        if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
        if (neverConnected(t) && host != null) {
            sb.append(" - could not connect to ").append(host).append(':').append(port);
            try {
                InetAddress[] all = InetAddress.getAllByName(host);
                sb.append(" (resolves to ");
                for (int i = 0; i < all.length && i < 4; i++) sb.append(i > 0 ? ", " : "").append(all[i].getHostAddress());
                sb.append(')');
            } catch (IOException e) {
                sb.append(" (the name does not resolve right now: ").append(e.getClass().getSimpleName()).append(')');
            }
            sb.append(" after ").append(String.format("%.1f", millis / 1000.0)).append(" s");
            if (connectTimeoutMs > 0 && millis >= connectTimeoutMs - 500) sb.append(" - the connect timeout (").append(connectTimeoutMs / 1000).append(" s) ran out");
            else sb.append(" - the connection was refused or dropped straight away");
            sb.append(". Usually a firewall, proxy, DNS or network blip between this server and the host");
        }
        Throwable c = t.getCause();
        for (int i = 0; c != null && i < 3; c = c.getCause(), i++) {
            sb.append("; caused by ").append(c.getClass().getSimpleName());
            if (c.getMessage() != null) sb.append(": ").append(c.getMessage());
        }
        return sb.toString();
    }
}
