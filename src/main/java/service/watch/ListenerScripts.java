package service.watch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Builds the remote commands that listen for file changes. The scripts themselves live in
 * {@code src/main/resources/scripts/watcher/}: {@code windows/listener.ps1} is a skeleton that pulls in one file per concern
 * (watch the folder, take over from a stale listener, guard against orphaning, the event loop, cleanup) and
 * {@code linux/listener.sh} is the Linux counterpart. Comment lines, blank lines and indentation are removed
 * before sending, so the files can be commented freely without making the command longer.
 */
final class ListenerScripts {

    private ListenerScripts() {
    }

    /**
     * Windows: {@code powershell -EncodedCommand ...}; {@code dir} is the already normalised folder.
     */
    static String windows(String dir, String heartbeat, String endPrefix, String unavailableMarker) {
        String script = expand(load("windows/listener.ps1"))
                .replace("{{PATH}}", dir.replace("'", "''"))
                .replace("{{HB}}", heartbeat)
                .replace("{{END}}", endPrefix)
                .replace("{{UNAVAILABLE}}", unavailableMarker);
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        // -NonInteractive + -NoProfile keep this fast and free of profile noise on stdout; -ExecutionPolicy Bypass
        // is scoped to this process only (it does not change the host's persistent policy).
        return "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand " + encoded;
    }

    /**
     * Linux: {@code sh -c '...'}; {@code dir} is the already normalised folder.
     */
    static String linux(String dir, String heartbeat, String unavailableMarker) {
        String inner = load("linux/listener.sh").replace('\n', ' ').trim()
                .replace("{{DIR}}", quote(dir))
                .replace("{{HB}}", heartbeat)
                .replace("{{UNAVAILABLE}}", unavailableMarker);
        return "sh -c " + quote(inner);
    }

    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * Replaces every {@code {{include:name}}} line with the content of that file from the same folder.
     */
    private static String expand(String skeleton) {
        StringBuilder out = new StringBuilder();
        for (String line : skeleton.split("\n")) {
            if (line.startsWith("{{include:") && line.endsWith("}}")) {
                out.append(load("windows/" + line.substring(10, line.length() - 2)));
            } else {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * The resource without comment lines, blank lines and leading/trailing spaces.
     */
    private static String load(String name) {
        try (InputStream in = ListenerScripts.class.getResourceAsStream("/scripts/watcher/" + name)) {
            if (in == null) throw new IllegalStateException("Missing script resource watch/" + name);
            StringBuilder sb = new StringBuilder();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\r?\\n")) {
                String t = line.strip();
                if (!t.isEmpty() && !t.startsWith("#")) sb.append(t).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
