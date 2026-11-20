package persistence.sqlite;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.time.LocalDate;

import static org.junit.Assert.*;

public class DbHealthTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void backupNamesSortByDate() {
        assertEquals("app-20261113.db", DbHealth.backupName(LocalDate.of(2026, 11, 13)));
        assertTrue(DbHealth.backupName(LocalDate.of(2026, 12, 1)).compareTo(DbHealth.backupName(LocalDate.of(2026, 11, 30))) > 0);
    }

    @Test
    public void onlyTheNewestSevenBackupsAreKept() throws Exception {
        File dir = tmp.newFolder();
        for (int d = 1; d <= 10; d++) new File(dir, "app-202611" + String.format("%02d", d) + ".db").createNewFile();
        new File(dir, "notes.txt").createNewFile();            // not ours: never touched
        new File(dir, "app-20261101.db.tmp").createNewFile();   // a half-written copy is not a backup and is not touched here
        DbHealth.prune(dir, DbHealth.KEEP);
        String[] left = dir.list((x, n) -> n.matches("app-\\d{8}\\.db"));
        assertNotNull(left);
        assertEquals(7, left.length);
        assertTrue(new File(dir, "app-20261110.db").exists());
        assertTrue(new File(dir, "app-20261104.db").exists());
        assertFalse(new File(dir, "app-20261103.db").exists());
        assertTrue(new File(dir, "notes.txt").exists());
        assertTrue(new File(dir, "app-20261101.db.tmp").exists());
    }

    @Test
    public void newestBackupIsTheLatestDate() throws Exception {
        File dir = tmp.newFolder();
        assertNull(DbHealth.newestBackup(dir));
        new File(dir, "app-20261105.db").createNewFile();
        new File(dir, "app-20261109.db").createNewFile();
        assertEquals("app-20261109.db", DbHealth.newestBackup(dir).getName());
        assertNull(DbHealth.newestBackup(new File(dir, "missing")));
    }

    @Test
    public void withoutADatabaseThereIsNothingToCheck() throws Exception {
        DbHealth.Report r = DbHealth.run(tmp.newFolder());
        assertTrue(r.healthy());
        assertNull(r.backup());
    }
}
