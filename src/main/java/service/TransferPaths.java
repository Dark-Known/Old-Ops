package service;

import java.nio.file.Paths;

/**
 * Pure path and quoting helpers for transfer scripts, split out of {@link TransferService} so they can be
 * tested on their own. Local paths use backslashes (the app runs on Windows); remote paths use forward
 * slashes. The WinSCP-style script quoting is also what the built-in SFTP engine parses back.
 */
public final class TransferPaths {

    private TransferPaths() {
    }

    /**
     * Trims, turns {@code /} into {@code \\} and collapses repeated backslashes.
     */
    public static String normalizeLocalPath(String path) {
        if (path == null) return "";
        return path.trim()
                .replace("/", "\\")
                .replaceAll("\\\\{2,}", "\\\\");
    }

    /**
     * Trims, turns {@code \\} into {@code /}, collapses repeated slashes; {@code C:/x} becomes {@code /C:/x}.
     */
    public static String normalizeRemotePath(String path) {
        if (path == null) return "";
        String normalized = path.trim().replace("\\", "/");
        normalized = normalized.replaceAll("/+", "/");
        if (normalized.matches("^[A-Za-z]:/.*")) {
            normalized = "/" + normalized;
        }
        return normalized;
    }

    /**
     * Quotes a local path for a script line: backslashes and quotes are escaped.
     */
    public static String escapeWinScpPath(String path) {
        if (path == null) return "\"\"";
        return '"' + path.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /**
     * Quotes a remote path for a script line: only quotes are escaped (backslashes are not special).
     */
    public static String escapeWinScpRemotePath(String path) {
        if (path == null) return "\"\"";
        return '"' + path.replace("\"", "\\\"") + '"';
    }

    public static String buildLocalDestinationPath(String dir, String fileName) {
        return normalizeLocalPath(Paths.get(dir, fileName).toString());
    }

    /**
     * A remote destination that ends in a folder separator or has no extension gets the file name appended.
     */
    public static String prepareRemoteDestination(String remotePath, String fileName) {
        if (remotePath.endsWith("/") || remotePath.endsWith("\\")) return remotePath + fileName;
        String last = remotePath.contains("/")
                ? remotePath.substring(remotePath.lastIndexOf('/') + 1)
                : remotePath.contains("\\")
                ? remotePath.substring(remotePath.lastIndexOf('\\') + 1) : remotePath;
        return last.contains(".") ? remotePath : remotePath + "/" + fileName;
    }

    /**
     * The folder to list for a remote path: a trailing {@code *} drops the pattern, otherwise a slash is added.
     */
    public static String remoteListingDirFor(String remotePath) {
        return remotePath.endsWith("*")
                ? remotePath.substring(0, remotePath.lastIndexOf('/') + 1)
                : remotePath.endsWith("/") ? remotePath : remotePath + "/";
    }
}
