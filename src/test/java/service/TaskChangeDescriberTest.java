package service;

import static org.junit.Assert.*;

import model.ScheduledTask;
import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class TaskChangeDescriberTest {

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test public void describeSkipsBlankAndZeroValuesAndUsesLabels() {
        String d = TaskChangeDescriber.describeNonBlank(map("sourcePath", "/in", "targetPath", "", "retryCount", "0", "cronExpression", null));
        assertEquals("Source path: /in", d);
    }

    @Test public void describeOfNothingSaysSo() {
        assertEquals("no fields set", TaskChangeDescriber.describeNonBlank(map("sourcePath", null)));
    }

    @Test public void unknownKeysFallBackToTheirName() {
        assertEquals("weird: 1", TaskChangeDescriber.describeNonBlank(map("weird", "1")));
    }

    @Test public void diffListsOnlyRealChanges() {
        String d = TaskChangeDescriber.diff(
                map("sourcePath", "/a", "targetPath", "/t", "retryCount", "0"),
                map("sourcePath", "/b", "targetPath", "/t", "retryCount", "0"));
        assertEquals("Source path: /a → /b", d);
    }

    @Test public void diffShowsNotSetForAddedAndClearedValues() {
        String d = TaskChangeDescriber.diff(map("sourcePath", "", "targetPath", "/t"), map("sourcePath", "/a", "targetPath", ""));
        assertTrue(d, d.contains("Source path: (not set) → /a"));
        assertTrue(d, d.contains("Target path: /t → (not set)"));
    }

    @Test public void diffOfIdenticalOrAllBlankIsNoChange() {
        assertEquals("no fields actually changed (re-saved as-is)",
                TaskChangeDescriber.diff(map("sourcePath", "/a", "x", null), map("sourcePath", "/a", "x", "")));
    }

    @Test public void snapshotResolvesCredentialIdsAndSkipsRuntimeFields() {
        ScheduledTask t = new ScheduledTask();
        t.setSourceCredentialId("id-1");
        t.setWatcherEnabled(true);
        Map<String, String> names = new HashMap<>();
        names.put("id-1", "alice@host");
        Map<String, String> f = TaskChangeDescriber.snapshot(t, names);
        assertEquals("alice@host", f.get("sourceCredential"));
        assertEquals("Yes", f.get("watcherEnabled"));
        assertFalse(f.containsKey("lastRunTime"));
        assertFalse(f.containsKey("createdAt"));
    }

    @Test public void snapshotKeepsAnUnknownCredentialIdAsIs() {
        ScheduledTask t = new ScheduledTask();
        t.setSourceCredentialId("gone");
        assertEquals("gone", TaskChangeDescriber.snapshot(t, new HashMap<>()).get("sourceCredential"));
    }
}
