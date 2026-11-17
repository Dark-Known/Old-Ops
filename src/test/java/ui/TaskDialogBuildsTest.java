package ui;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;

import model.ScheduledTask;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import persistence.AppStorage;
import persistence.SettingsStore;
import persistence.SettingsStores;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A smoke test for the task dialog: it must build (every tab, for a new task and for each task type) without
 * throwing. Needs a display, so it is skipped on a headless machine; run it under {@code xvfb-run} on a server.
 */
public class TaskDialogBuildsTest {

    @Before public void memorySettings() {
        SettingsStore memory = new SettingsStore() {
            final Map<String, String> data = new LinkedHashMap<>();
            public Map<String, String> readAll() { return new LinkedHashMap<>(data); }
            public boolean replaceAll(Map<String, String> v) { data.clear(); data.putAll(v); return true; }
            public boolean isConnected() { return true; }
            public String connectionError() { return null; }
            public String location() { return "memory"; }
        };
        SettingsStores.install(dir -> memory);
        util.AppSettingsTestHooks.reset();
    }

    @After public void restore() {
        SettingsStores.reset();
        util.AppSettingsTestHooks.reset();
    }

    private static AppStorage storage() {
        return (AppStorage) Proxy.newProxyInstance(AppStorage.class.getClassLoader(), new Class<?>[]{AppStorage.class}, (p, m, a) -> {
            Class<?> r = m.getReturnType();
            if (r == java.io.File.class) return new java.io.File(System.getProperty("java.io.tmpdir"));
            if (r == boolean.class) return false;
            if (r == int.class) return 0;
            if (r == long.class) return 0L;
            if (List.class.isAssignableFrom(r)) return new ArrayList<>();
            return null;
        });
    }

    @Test public void theDialogBuildsForANewTaskAndForEveryTaskType() throws Exception {
        assumeFalse("needs a display", GraphicsEnvironment.isHeadless());
        List<Throwable> failures = new ArrayList<>();
        for (String type : new String[]{null, "FILE_TRANSFER", "OUTLOOK_MAIL", "BACKUP", "FOLDER_WATCH"}) {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    ScheduledTask existing = null;
                    if (type != null) {
                        existing = new ScheduledTask();
                        existing.setId("id");
                        existing.setName("t");
                        existing.setTaskType(ScheduledTask.TaskType.valueOf(type));
                        existing.setScheduleType(ScheduledTask.ScheduleType.INTERVAL_MINUTES);
                        existing.setIntervalMinutes(5);
                    }
                    TaskDialog d = new TaskDialog(null, storage(), existing);
                    assertNotNull(d.getContentPane());
                    d.dispose();
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }
        assertTrue("dialog failed to build: " + failures, failures.isEmpty());
    }
}
