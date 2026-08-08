package util;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.io.File;
import java.net.URISyntaxException;

/**
 * Locates and reads app-config.xml reliably regardless of the process's
 * current working directory at launch.
 *
 * A plain {@code new File("app-config.xml")} only works when the process's
 * working directory happens to be the install directory. That's true for the
 * GUI when launched via its shortcut (whose "Start in" defaults to the
 * shortcut target's folder), but isn't guaranteed for every launch path —
 * and when it fails, callers silently fall back to hardcoded defaults with
 * no indication anything went wrong (e.g. SITA header generation resolving
 * neither the station-codes JSON path nor the configured default address,
 * even though both are set in app-config.xml).
 *
 * This resolves the file two ways: first relative to the working directory
 * (cheap, covers the common case), then — if that fails — next to the
 * running JAR itself, which is correct regardless of how/where the process
 * was launched from.
 */
public final class AppConfig {
    private static final String FILE_NAME = "app-config.xml";
    private static volatile File resolved;
    private static volatile boolean attempted = false;

    private AppConfig() {}

    public static synchronized File locate() {
        if (attempted) return resolved;
        attempted = true;

        File cwdCandidate = new File(FILE_NAME);
        if (cwdCandidate.exists()) {
            resolved = cwdCandidate;
            return resolved;
        }

        try {
            File codeLocation = new File(AppConfig.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            File dir = codeLocation.isFile() ? codeLocation.getParentFile() : codeLocation;
            File jarCandidate = new File(dir, FILE_NAME);
            if (jarCandidate.exists()) {
                resolved = jarCandidate;
                return resolved;
            }
        } catch (URISyntaxException | SecurityException | NullPointerException ignored) {
            // best-effort — fall through to "not found"
        }

        resolved = null;
        return null;
    }

    /** Reads a single top-level-named element's text from app-config.xml, or null if absent/unreadable. */
    public static String readValue(String tagName) {
        try {
            File configFile = locate();
            if (configFile == null) return null;
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = builder.parse(configFile);
            NodeList nodes = doc.getElementsByTagName(tagName);
            if (nodes.getLength() > 0) {
                String val = nodes.item(0).getTextContent().trim();
                if (!val.isEmpty()) return val;
            }
        } catch (Exception ignored) {
            // best-effort — missing/invalid config just means the caller falls back
        }
        return null;
    }
}
