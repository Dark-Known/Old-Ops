package architecture;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Keeps the storage layering from eroding. Reads the source tree (so it needs no database) and
 * fails when code outside {@code persistence.sqlite} starts depending on SQLite/JDBC again.
 *
 * <p>{@link #LEGACY_JDBC_USERS} lists the classes that still talk to JDBC directly. It is a
 * to-do list that may only shrink: adding a file fails the build, and a file that has been
 * cleaned up must be removed from the list (the test says so).
 */
public class LayeringTest {

    private static final Path SRC = Paths.get("src/main/java");

    /** Classes outside persistence/ that still import java.sql or org.sqlite. Move each behind a port, then delete its line. */
    private static final Set<String> LEGACY_JDBC_USERS = Set.of();

    /** The only places allowed to choose the SQLite implementation: where the application is assembled. */
    private static final Set<String> COMPOSITION_ROOTS = Set.of(
            "App.java",
            "Daemon.java",
            "ui/MainWindow.java");

    private static final Pattern JDBC_IMPORT = Pattern.compile("^import\\s+(java\\.sql|org\\.sqlite)\\b", Pattern.MULTILINE);
    private static final Pattern SQLITE_ADAPTER_IMPORT = Pattern.compile("^import\\s+persistence\\.sqlite\\b", Pattern.MULTILINE);

    private static Set<String> sourceFiles() throws IOException {
        assumeTrue("run from the project root", Files.isDirectory(SRC));
        try (Stream<Path> s = Files.walk(SRC)) {
            return s.filter(p -> p.toString().endsWith(".java"))
                    .map(p -> SRC.relativize(p).toString().replace('\\', '/'))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static String read(String rel) throws IOException {
        return new String(Files.readAllBytes(SRC.resolve(rel)), StandardCharsets.UTF_8);
    }

    private static boolean inSqliteAdapter(String rel) { return rel.startsWith("persistence/sqlite/"); }

    @Test public void theTechnologyNeutralPersistenceLayerKnowsNoDatabase() throws IOException {
        for (String f : sourceFiles()) {
            if (!f.startsWith("persistence/") || inSqliteAdapter(f)) continue;
            String text = read(f);
            assertFalse(f + " must not import JDBC/SQLite: it is the technology-neutral layer",
                    JDBC_IMPORT.matcher(text).find());
            assertFalse(f + " must not import persistence.sqlite: dependencies point from the adapter to the ports, never back",
                    SQLITE_ADAPTER_IMPORT.matcher(text).find());
        }
    }

    @Test public void onlyTheCompositionRootsChooseTheSqliteImplementation() throws IOException {
        Set<String> offenders = new TreeSet<>();
        for (String f : sourceFiles()) {
            if (f.startsWith("persistence/") || COMPOSITION_ROOTS.contains(f)) continue;
            if (SQLITE_ADAPTER_IMPORT.matcher(read(f)).find()) offenders.add(f);
        }
        assertTrue("These depend on the SQLite adapter; depend on persistence.AppStorage instead: " + offenders,
                offenders.isEmpty());
    }

    @Test public void noNewCodeTalksToJdbcOutsideTheAdapter() throws IOException {
        Set<String> users = new TreeSet<>();
        for (String f : sourceFiles()) {
            if (f.startsWith("persistence/")) continue;
            if (JDBC_IMPORT.matcher(read(f)).find()) users.add(f);
        }
        Set<String> added = new TreeSet<>(users);
        added.removeAll(LEGACY_JDBC_USERS);
        assertTrue("New direct JDBC use outside persistence.sqlite (put it behind a repository port): " + added,
                added.isEmpty());

        Set<String> cleaned = new TreeSet<>(LEGACY_JDBC_USERS);
        cleaned.removeAll(users);
        assertTrue("These no longer use JDBC directly - good! Remove them from LEGACY_JDBC_USERS: " + cleaned,
                cleaned.isEmpty());
    }

    /** Packages that must stay free of static settings and of any storage: everything they need is passed in. */
    /**
     * The only files that may reference JSch: the SFTP adapters (they open and drive a connection), the SSH
     * auth/host-key helpers, and the places that read a JSch exception or the push-watcher's channel.
     * Everything else, including the folder browser, connection test and metadata lookup, talks to
     * {@code service.sftp.SftpClient}.
     */
    private static final Set<String> JSCH_HOMES = Set.of(
            "service/sftp/JschSftpClient.java", "service/sftp/SessionLog.java",
            "service/watch/PersistentSftpConnectionManager.java", "service/watch/RemotePushWatcher.java",
            "util/SshAuth.java", "util/SshHostKeys.java", "model/FailureClassifier.java");

    @Test public void onlyTheSftpAdaptersImportJsch() throws IOException {
        Pattern jsch = Pattern.compile("com\\.jcraft\\.jsch\\b");
        Set<String> offenders = new TreeSet<>();
        for (String f : sourceFiles()) {
            if (jsch.matcher(read(f)).find() && !JSCH_HOMES.contains(f)) offenders.add(f);
        }
        assertTrue("These import JSch but are not SFTP adapters - use SftpClient (or add a deliberate entry to JSCH_HOMES): "
                + offenders, offenders.isEmpty());
        Set<String> stale = new TreeSet<>(JSCH_HOMES);
        stale.removeIf(f -> { try { return jsch.matcher(read(f)).find(); } catch (IOException e) { return true; } });
        assertTrue("These no longer import JSch - remove them from JSCH_HOMES: " + stale, stale.isEmpty());
    }

    private static final Set<String> INJECTED_ONLY_PACKAGES = Set.of("service/backup/");

    private static final Pattern SETTINGS_OR_STORAGE_IMPORT =
            Pattern.compile("^import\\s+(util\\.AppSettings|persistence\\.|java\\.sql|org\\.sqlite)", Pattern.MULTILINE);

    @Test public void backupLogicTakesItsSettingsAndStorageAsArguments() throws IOException {
        Set<String> offenders = new TreeSet<>();
        for (String f : sourceFiles()) {
            if (INJECTED_ONLY_PACKAGES.stream().noneMatch(f::startsWith)) continue;
            if (SETTINGS_OR_STORAGE_IMPORT.matcher(read(f)).find()) offenders.add(f);
        }
        assertTrue("These reach for AppSettings or storage directly; take what they need through the constructor: " + offenders,
                offenders.isEmpty());
    }

    /** Where WinSCP's script syntax may appear: the renderer, and the helper that defines the quoting. */
    private static final Set<String> WINSCP_SYNTAX_HOMES = Set.of(
            "service/transfer/WinScpScript.java",
            "service/TransferPaths.java");

    @Test public void onlyTheWinScpEngineKnowsWinScpScriptSyntax() throws IOException {
        Set<String> offenders = new TreeSet<>();
        for (String f : sourceFiles()) {
            if (WINSCP_SYNTAX_HOMES.contains(f)) continue;
            if (read(f).contains("escapeWinScp")) offenders.add(f);
        }
        assertTrue("Plan with TransferCommand/TransferJob; only WinScpScript quotes paths for WinSCP: " + offenders,
                offenders.isEmpty());
    }

    /** Screens that still read the data folder directly (each has a real need); every other screen goes through AppFolder. */
    private static final Set<String> UI_ALLOWED_TO_USE_THE_DATA_FOLDER = Set.of(
            "ui/MainWindow.java", "ui/TaskDialog.java", "ui/RetentionPanel.java", "ui/SettingsPanel.java");

    @Test public void screensAskAppFolderInsteadOfBuildingPathsInTheDataFolder() throws IOException {
        Set<String> offenders = new TreeSet<>();
        for (String f : sourceFiles()) {
            if (!f.startsWith("ui/") || UI_ALLOWED_TO_USE_THE_DATA_FOLDER.contains(f)) continue;
            String src = read(f);
            if (src.contains("getDataDir()") || src.contains("scheduler-status-daemon")) offenders.add(f);
        }
        assertTrue("Use service.AppFolder (daemonStatusFile, sendCommand, changes) instead: " + offenders, offenders.isEmpty());
    }
}
