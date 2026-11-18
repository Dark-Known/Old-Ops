package util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads the PowerShell snippets the application runs or shows, which live as files in {@code src/main/resources/scripts/}
 * instead of being assembled from strings in Java. {@code {{NAME}}} placeholders are filled from the key/value pairs.
 * (The remote change listeners have their own loader, {@code service.watch.ListenerScripts}.)
 */
public final class Scripts {

    private Scripts() {}

    /** Comment lines and blank lines removed, remaining lines joined with a newline. For scripts passed as -EncodedCommand. */
    public static String compact(String name, String... kv) {
        return fill(strip(read(name), "\n"), kv);
    }

    /** Comment lines and blank lines removed, remaining lines joined with "; ". For a short script given to {@code -Command}. */
    public static String oneLine(String name, String... kv) {
        return fill(strip(read(name), "; "), kv);
    }

    /** The file as written (text shown to the user, such as a command to copy). Trailing newline removed. */
    public static String raw(String name, String... kv) {
        String s = read(name).replace("\r\n", "\n");
        return fill(s.endsWith("\n") ? s.substring(0, s.length() - 1) : s, kv);
    }

    private static String strip(String text, String joiner) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (sb.length() > 0) sb.append(joiner);
            sb.append(t);
        }
        return sb.toString();
    }

    private static String fill(String s, String... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("placeholders come in name/value pairs");
        for (int i = 0; i < kv.length; i += 2) s = s.replace("{{" + kv[i] + "}}", kv[i + 1]);
        return s;
    }

    private static String read(String name) {
        try (InputStream in = Scripts.class.getResourceAsStream("/scripts/" + name)) {
            if (in == null) throw new IllegalStateException("Missing script resource scripts/" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
