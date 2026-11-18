package service.watch;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import persistence.SettingsStore;
import persistence.SettingsStores;

import java.util.LinkedHashMap;
import java.util.Map;
import persistence.AppStorage;
import service.TransferService;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

public class WatcherConnectionsTest {

    /** Records the calls; hands out no channels (these tests never open a connection). */
    private static final class Recording implements WatcherConnections {
        final List<String> calls = new ArrayList<>();
        public service.sftp.SftpClient getClient(String t, model.Credential c) { throw new UnsupportedOperationException(); }
        public service.sftp.SftpClient acquireClient(String t, int s, model.Credential c) { throw new UnsupportedOperationException(); }
        public int getSessionCount(String t) { return 0; }
        public void note(String t, String text) { calls.add("note " + t); }
        public void close(String t) { calls.add("close " + t); }
        public void close(String t, String reason) { calls.add("close " + t + " " + reason); }
        public void closeAll() { calls.add("closeAll"); }
        public void shutdown() { calls.add("shutdown"); }
    }

    /** TransferService reads settings while it is built: give it an in-memory store, not a database. */
    @Before public void inMemorySettings() {
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

    @After public void restoreSettings() {
        SettingsStores.reset();
        util.AppSettingsTestHooks.reset();
    }

    private static AppStorage storage() {
        return (AppStorage) Proxy.newProxyInstance(AppStorage.class.getClassLoader(), new Class<?>[]{AppStorage.class},
                (p, m, a) -> {
                    Class<?> r = m.getReturnType();
                    if (r == java.io.File.class) return new java.io.File(System.getProperty("java.io.tmpdir"));
                    if (r == boolean.class) return false;
                    if (r == int.class) return 0;
                    if (r == long.class) return 0L;
                    if (java.util.List.class.isAssignableFrom(r)) return new ArrayList<>();
                    return null;
                });
    }

    @Test public void theServiceUsesTheConnectionProviderItIsGiven() {
        Recording rec = new Recording();
        TransferService svc = new TransferService(storage(), rec);
        svc.closeWatcherConnection("task-1");
        assertEquals(List.of("close task-1"), rec.calls);
    }

    @Test public void theJschManagerIsAWatcherConnections() {
        assertTrue(WatcherConnections.class.isAssignableFrom(PersistentSftpConnectionManager.class));
    }
}
