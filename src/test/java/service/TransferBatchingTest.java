package service;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class TransferBatchingTest {

    private static List<List<Integer>> chunk(long max, Integer... sizes) {
        return TransferBatching.chunkBySize(Arrays.asList(sizes), i -> i, max);
    }

    @Test
    public void emptyInputGivesNoBatches() {
        assertEquals(0, chunk(10).size());
    }

    @Test
    public void filesArePackedUpToTheCapInOrder() {
        List<List<Integer>> b = chunk(10, 4, 4, 4, 4, 4);
        assertEquals(Arrays.asList(Arrays.asList(4, 4), Arrays.asList(4, 4), Arrays.asList(4)), b);
    }

    @Test
    public void aFileBiggerThanTheCapGoesAloneAndIsNeverSplit() {
        List<List<Integer>> b = chunk(10, 3, 50, 3);
        assertEquals(Arrays.asList(Arrays.asList(3), Arrays.asList(50), Arrays.asList(3)), b);
    }

    @Test
    public void noCapMeansOneBatch() {
        assertEquals(1, chunk(0, 5, 500, 5000).size());
        assertEquals(1, chunk(-1, 5, 500, 5000).size());
    }

    @Test
    public void unknownSizesCountAsZero() {
        assertEquals(1, chunk(10, -1, -5, 10).size());
    }

    @Test
    public void aBatchMayFillTheCapExactly() {
        assertEquals(Arrays.asList(Arrays.asList(5, 5), Arrays.asList(5)), chunk(10, 5, 5, 5));
    }
}
