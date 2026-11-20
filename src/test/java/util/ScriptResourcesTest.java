package util;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

/**
 * Guards the {@code {{PLACEHOLDER}}} and {{@code {{include:file}}}} tokens in {@code src/main/resources/scripts}.
 *
 * <p>An IDE's PowerShell formatter reads {@code {{NAME}}} as two nested script blocks and rewrites it to
 * {@code { { NAME } }}. The loaders ({@link Scripts}, {@code service.watch.ListenerScripts}) only match the exact
 * form, so the token is silently left in the script that runs. This test fails the build instead.
 */
public class ScriptResourcesTest {

    private static final Path SCRIPTS = Path.of("src", "main", "resources", "scripts");

    /**
     * A placeholder written with any amount of whitespace between its braces.
     */
    private static final Pattern TOKEN =
            Pattern.compile("\\{\\s*\\{\\s*(include:[A-Za-z0-9_.\\-]+|[A-Z][A-Z0-9_]*)\\s*}\\s*}");

    @Test
    public void everyPlaceholderIsWrittenWithoutInnerWhitespace() throws IOException {
        List<String> broken = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> s = Files.walk(SCRIPTS)) {
            files = s.filter(Files::isRegularFile).toList();
        }
        assertTrue("no script resources found under " + SCRIPTS.toAbsolutePath(), !files.isEmpty());
        for (Path f : files) {
            String text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            Matcher m = TOKEN.matcher(text);
            while (m.find()) {
                String canonical = "{{" + m.group(1) + "}}";
                if (!m.group().equals(canonical)) {
                    int line = 1 + (int) text.substring(0, m.start()).chars().filter(c -> c == '\n').count();
                    broken.add(SCRIPTS.relativize(f) + ":" + line + "  \"" + m.group() + "\"  should be  " + canonical);
                }
            }
        }
        assertTrue("Placeholders were reformatted (turn off 'Reformat code' for scripts/**/*.ps1):\n  "
                + String.join("\n  ", broken), broken.isEmpty());
    }
}
