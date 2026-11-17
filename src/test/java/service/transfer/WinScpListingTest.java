package service.transfer;

import static org.junit.Assert.*;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

public class WinScpListingTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 12, 0, 0);

    private static long ms(LocalDateTime t) {
        return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static Map<String, RemoteFileStat> parse(String... lines) {
        return WinScpListing.parse(Arrays.asList(lines), "/in/", NOW);
    }

    @Test public void aRecentFileUsesTheCurrentYear() {
        Map<String, RemoteFileStat> m = parse("-rw-r--r--   1 ops  ops  1234 Nov  6 10:15 data.csv");
        RemoteFileStat s = m.get("/in/data.csv");
        assertNotNull(s);
        assertEquals(1234L, s.size);
        assertEquals(ms(LocalDateTime.of(2026, 11, 6, 10, 15)), s.epochMillis);
    }

    @Test public void anOldFileShowsItsYearAndMidnight() {
        RemoteFileStat s = parse("-rw-r--r--   1 ops  ops  99 Jan  5  2024 old.txt").get("/in/old.txt");
        assertNotNull(s);
        assertEquals(ms(LocalDateTime.of(2024, 1, 5, 0, 0)), s.epochMillis);
    }

    @Test public void aTimeLaterThanNowBelongsToLastYear() {
        // "Dec 25 10:00" cannot be in the future relative to 10 Nov 2026, so it is from 2025.
        RemoteFileStat s = parse("-rw-r--r--   1 ops  ops  5 Dec 25 10:00 x.txt").get("/in/x.txt");
        assertEquals(ms(LocalDateTime.of(2025, 12, 25, 10, 0)), s.epochMillis);
    }

    @Test public void aListingWithSecondsCarriesItsYearBeforeTheName() {
        RemoteFileStat s = parse("-rw-r--r--   1 ops  ops  7 Mar  3 08:09:10 2025 precise.log").get("/in/precise.log");
        assertNotNull(s);
        assertEquals(ms(LocalDateTime.of(2025, 3, 3, 8, 9, 10)), s.epochMillis);
    }

    @Test public void namesWithSpacesAreKeptWhole() {
        assertNotNull(parse("-rw-r--r--   1 ops  ops  1234 Nov  6 10:15 my report final.csv").get("/in/my report final.csv"));
        assertNotNull(parse("-rw-r--r--   1 ops  ops  7 Mar  3 08:09:10 2025 my precise log.txt").get("/in/my precise log.txt"));
        assertNotNull(parse("-rw-r--r--   1 ops  ops  99 Jan  5  2024 old file.txt").get("/in/old file.txt"));
    }

    @Test public void foldersAndNonFileLinesAreIgnored() {
        Map<String, RemoteFileStat> m = parse(
                "drwxr-xr-x   2 ops  ops  4096 Nov  6 10:15 subdir",
                "total 12",
                "",
                "   ",
                "-rw-r--r--   1 ops  ops  10 Nov  6 10:15 keep.txt");
        assertEquals(Collections.singleton("/in/keep.txt"), m.keySet());
    }

    @Test public void malformedLinesAreSkippedNotFatal() {
        Map<String, RemoteFileStat> m = parse(
                "-rw-r--r--   1 ops  ops  10 Foo  6 10:15 badmonth.txt",
                "-rw-r--r--   1 ops  ops  10 Nov  xx 10:15 badday.txt",
                "-rw-r--r--   1 ops  ops  10 Nov  6 99:99 badtime.txt",
                "-rw-r--r-- short",
                "-rw-r--r--   1 ops  ops  10 Nov  6 10:15 good.txt");
        assertEquals(Collections.singleton("/in/good.txt"), m.keySet());
    }

    @Test public void anUnreadableSizeCountsAsZero() {
        RemoteFileStat s = parse("-rw-r--r--   1 ops  ops  ?? Nov  6 10:15 odd.txt").get("/in/odd.txt");
        assertNotNull(s);
        assertEquals(0L, s.size);
    }

    @Test public void keysAreTheDirectoryFollowedByTheName() {
        assertEquals("/data/x/a.txt",
                WinScpListing.parse(Collections.singletonList("-rw-r--r-- 1 u g 1 Nov 6 10:15 a.txt"),
                        "/data/x/", NOW).keySet().iterator().next());
    }

    @Test public void theOrderOfTheListingIsKept() {
        Map<String, RemoteFileStat> m = parse(
                "-rw-r--r-- 1 u g 1 Nov 6 10:15 b.txt",
                "-rw-r--r-- 1 u g 1 Nov 6 10:15 a.txt");
        assertEquals(Arrays.asList("/in/b.txt", "/in/a.txt"), Arrays.asList(m.keySet().toArray()));
    }

    @Test public void anEmptyListingGivesAnEmptyMap() {
        assertTrue(WinScpListing.parse(Collections.emptyList(), "/in/", NOW).isEmpty());
    }
}
