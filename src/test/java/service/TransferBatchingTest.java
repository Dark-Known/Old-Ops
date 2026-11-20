package service;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class TransferBatchingTest {

    private static List<List<Integer>> chunk(long max, Integer... sizes) {
        return TransferBatching.chunkBySize(Arrays.asList(sizes), i -> i, max);
    }

    @Test public void emptyInputGivesNoBatches() { assertEquals(0, chunk(10).size()); }

    @Test public void filesArePackedUpToTheCapInOrder() {
        List<List<Integer>> b = chunk(10, 4, 4, 4, 4, 4);
        assertEquals(Arrays.asList(Arrays.asList(4, 4), Arrays.asList(4, 4), Arrays.asList(4)), b);
    }

    @Test public void aFileBiggerThanTheCapGoesAloneAndIsNeverSplit() {
        List<List<Integer>> b = chunk(10, 3, 50, 3);
        assertEquals(Arrays.asList(Arrays.asList(3), Arrays.asList(50), Arrays.asList(3)), b);
    }

    @Test public void noCapMeansOneBatch() {
        assertEquals(1, chunk(0, 5, 500, 5000).size());
        assertEquals(1, chunk(-1, 5, 500, 5000).size());
    }

    @Test public void unknownSizesCountAsZero() {
        assertEquals(1, chunk(10, -1, -5, 10).size());
    }

    @Test public void aBatchMayFillTheCapExactly() {
        assertEquals(Arrays.asList(Arrays.asList(5, 5), Arrays.asList(5)), chunk(10, 5, 5, 5));
    }

    // ── spreading work over several sessions ─────────────────────────────

    private static List<Long> sizes(int n, long each) {
        List<Long> l = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) l.add(each);
        return l;
    }

    @Test
    public void planForSessionsSplitsOneSmallBatchOverTheSessions() {
        // 40 files of 1 MB with a 100 MB cap: the cap alone gives one batch, 4 sessions should share it
        List<List<Long>> plan = TransferBatching.planForSessions(sizes(40, 1_000_000), x -> x, 100_000_000, 4);
        assertTrue("at most 4 batches: " + plan.size(), plan.size() <= 4);
        assertTrue("uses several sessions: " + plan.size(), plan.size() >= 3);
        int files = 0;
        for (List<Long> b : plan) files += b.size();
        assertEquals(40, files);
    }

    @Test
    public void planForSessionsNeverUsesMoreSessionsThanFiles() {
        assertEquals("3 files, 3 sessions allowed: one file each", 3, TransferBatching.planForSessions(sizes(3, 1_000_000), x -> x, 100_000_000, 3).size());
        assertEquals("only 2 files: 2 sessions", 2, TransferBatching.planForSessions(sizes(2, 1_000_000), x -> x, 100_000_000, 4).size());
        assertEquals("a single file: 1 session", 1, TransferBatching.planForSessions(sizes(1, 1_000_000), x -> x, 100_000_000, 4).size());
    }

    @Test
    public void planForSessionsKeepsTheSizeCapAndOneSessionMeansNoSplit() {
        assertEquals(1, TransferBatching.planForSessions(sizes(40, 1_000_000), x -> x, 100_000_000, 1).size());
        // the cap already gives more batches than sessions: unchanged
        assertEquals(10, TransferBatching.planForSessions(sizes(40, 1_000_000), x -> x, 4_000_000, 2).size());
    }

    @Test
    public void planForSessionsSharesByCountWhenSizesAreUnknown() {
        List<List<Long>> plan = TransferBatching.planForSessions(sizes(20, 0), x -> x, 100_000_000, 4);
        assertEquals(4, plan.size());
    }
}
