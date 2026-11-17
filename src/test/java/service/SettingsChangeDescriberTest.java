package service;

import static org.junit.Assert.*;

import org.junit.Test;
import util.AppSettings;

import java.util.LinkedHashMap;
import java.util.Map;

public class SettingsChangeDescriberTest {

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test public void listsOnlyChangedKeysWithFriendlyLabels() {
        String d = SettingsChangeDescriber.describe(
                map(AppSettings.KEY_LOG_LEVEL, "INFO", AppSettings.KEY_JVM_MAX_HEAP, "1g"),
                map(AppSettings.KEY_LOG_LEVEL, "DEBUG", AppSettings.KEY_JVM_MAX_HEAP, "1g"));
        assertEquals("Application settings updated — Log level: INFO → DEBUG.", d);
    }

    @Test public void blankSidesShowAsNotSet() {
        String d = SettingsChangeDescriber.describe(map(AppSettings.KEY_DEFAULT_STATION_ADDR, null),
                map(AppSettings.KEY_DEFAULT_STATION_ADDR, "a@b.c"));
        assertTrue(d, d.contains("Default station address: (not set) → a@b.c"));
    }

    @Test public void noDifferenceSaysReSaved() {
        assertEquals("Application settings re-saved with no changes.",
                SettingsChangeDescriber.describe(map("k", ""), map("k", null)));
    }

    @Test public void unknownKeyUsesItsRawName() {
        assertTrue(SettingsChangeDescriber.describe(map(), map("mystery", "1")).contains("mystery: (not set) → 1"));
    }
}
