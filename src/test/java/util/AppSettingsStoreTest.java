package util;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import persistence.SettingsStore;
import persistence.SettingsStores;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AppSettings keeps defaults, caching and typed access; where the values live is the store's business.
 * These tests run it on an in-memory store, so no database (and no SQLite driver) is involved.
 */
public class AppSettingsStoreTest {

    private static final class MemoryStore implements SettingsStore {
        final Map<String, String> data = new LinkedHashMap<>();
        boolean available = true;
        int writes;
        @Override public Map<String, String> readAll() { return available ? new LinkedHashMap<>(data) : new LinkedHashMap<>(); }
        @Override public boolean replaceAll(Map<String, String> values) {
            if (!available) return false;
            data.clear(); data.putAll(values); writes++;
            return true;
        }
        @Override public boolean isConnected() { return available; }
        @Override public String connectionError() { return available ? null : "down for the test"; }
        @Override public String location() { return "memory"; }
    }

    private MemoryStore store;

    @Before public void installFake() {
        store = new MemoryStore();
        SettingsStores.install(dir -> store);
        AppSettings.resetForTests();
    }

    @After public void restoreDefault() {
        SettingsStores.reset();
        AppSettings.resetForTests();
    }

    @Test public void anEmptyStoreIsSeededWithDefaultsAndWrittenBack() {
        assertTrue(AppSettings.getTransferBatchMaxBytes() > 0);
        assertFalse("the seeded defaults were persisted for the Settings panel", store.data.isEmpty());
        assertEquals(1, store.writes);
    }

    @Test public void storedValuesWinOverDefaults() {
        store.data.put(AppSettings.KEY_TRANSFER_BATCH_MAX_BYTES, "12345");
        assertEquals(12345L, AppSettings.getTransferBatchMaxBytes());
    }

    @Test public void aSetValueIsStoredAndImmediatelyVisible() {
        assertTrue(AppSettings.set(AppSettings.KEY_TRANSFER_BATCH_MAX_BYTES, "777"));
        assertEquals("777", store.data.get(AppSettings.KEY_TRANSFER_BATCH_MAX_BYTES));
        assertEquals(777L, AppSettings.getTransferBatchMaxBytes());
    }

    @Test public void settingSeveralValuesWritesOnce() {
        AppSettings.getTransferBatchMaxBytes();      // load (and seed) first
        int before = store.writes;
        Map<String, String> more = new LinkedHashMap<>();
        more.put(AppSettings.KEY_TRANSFER_BATCH_MAX_BYTES, "9");
        more.put("someOtherKey", "x");
        assertTrue(AppSettings.setAll(more));
        assertEquals(before + 1, store.writes);
        assertEquals("x", store.data.get("someOtherKey"));
    }

    @Test public void anEmptyValueRemovesTheKey() {
        AppSettings.set("k", "v");
        AppSettings.set("k", "");
        assertFalse(store.data.containsKey("k"));
    }

    @Test public void whenTheStoreIsDownAWriteFailsAndTheCachedValueIsKept() {
        store.data.put(AppSettings.KEY_TRANSFER_BATCH_MAX_BYTES, "100");
        assertEquals(100L, AppSettings.getTransferBatchMaxBytes());
        store.available = false;
        assertFalse(AppSettings.set(AppSettings.KEY_TRANSFER_BATCH_MAX_BYTES, "200"));
        assertEquals("no change that exists only in this process", 100L, AppSettings.getTransferBatchMaxBytes());
    }

    @Test public void connectionStateAndLocationComeFromTheStore() {
        assertTrue(AppSettings.isConnected());
        assertNull(AppSettings.getConnectionError());
        assertEquals("memory", AppSettings.filePath());
        store.available = false;
        assertFalse(AppSettings.isConnected());
        assertEquals("down for the test", AppSettings.getConnectionError());
    }

    @Test public void withNoStoreInstalledSettingsFallBackToDefaults() {
        SettingsStores.reset();
        AppSettings.resetForTests();
        assertTrue(AppSettings.getTransferBatchMaxBytes() > 0);
        assertFalse(AppSettings.isConnected());
        assertFalse(AppSettings.set("k", "v"));
    }
}
