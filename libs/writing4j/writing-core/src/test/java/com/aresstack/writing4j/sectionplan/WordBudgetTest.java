package com.aresstack.writing4j.sectionplan;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WordBudgetTest {

    private static final double NONE = Double.NaN;

    private static int sum(int[] words) {
        int sum = 0;
        for (int word : words) {
            sum += word;
        }
        return sum;
    }

    @Test
    public void weightsShareTheRestAfterEveryPartGotItsFloor() {
        int[] words = WordBudget.spread(2000, new long[]{10, 30, 60}, null, 350);

        assertEquals(2000, sum(words));
        assertEquals(350 + 95, words[0]);
        assertEquals(350 + 285, words[1]);
        assertEquals(350 + 570, words[2]);
    }

    @Test
    public void aBudgetTooSmallForTheFloorsFollowsTheWeightsAlone() {
        int[] words = WordBudget.spread(600, new long[]{1, 1, 1, 1}, null, 350);

        assertArrayEquals(new int[]{150, 150, 150, 150}, words);
    }

    @Test
    public void anExplicitShareIsThatShareOfTheWholeAndTheOthersShareTheRestByWeight() {
        int[] words = WordBudget.spread(7500, new long[]{5, 20, 10}, new double[]{0.4, NONE, NONE}, 350);

        assertEquals(7500, sum(words));
        assertEquals(3000, words[0]);
        // 4,500 words left: 350 each first, then 3,800 by weight 20 : 10.
        assertEquals(350 + 2533, words[1]);
        assertEquals(350 + 1267, words[2]);
    }

    @Test
    public void sharesShrinkSoThatEveryPartWithoutOneKeepsItsFloor() {
        int[] words = WordBudget.spread(2000, new long[]{1, 1, 1, 1}, new double[]{0.6, 0.4, NONE, NONE}, 350);

        assertEquals(2000, sum(words));
        assertEquals(350, words[2]);
        assertEquals(350, words[3]);
        assertEquals(780, words[0]);
        assertEquals(520, words[1]);
    }

    @Test
    public void onlySharesFillTheWholeInTheirProportion() {
        int[] words = WordBudget.spread(8000, new long[]{1, 1}, new double[]{0.5, 0.3}, 350);

        assertArrayEquals(new int[]{5000, 3000}, words);
    }

    @Test
    public void roundingAlwaysAddsUpToTheTotal() {
        for (int total = 1000; total < 1100; total++) {
            int[] words = WordBudget.spread(total, new long[]{3, 7, 11, 13}, new double[]{0.17, NONE, NONE, NONE},
                    200);
            assertEquals("total " + total, total, sum(words));
            for (int i = 1; i < words.length; i++) {
                assertTrue("floor at " + total, words[i] >= 200);
            }
        }
    }

    /** "Nach Stoff begrenzen": a part keeps at most its bound; the others take what it gives up, by weight. */
    @Test
    public void aBoundKeepsAPartWithinItsMaterialAndTheOthersTakeTheRest() {
        assertArrayEquals(new int[]{100, 800, 600}, WordBudget.capped(new int[]{500, 500, 500},
                new int[]{100, 1000, 600}, new long[]{1, 1, 1}));
        assertArrayEquals("by weight", new int[]{200, 500, 300}, WordBudget.capped(new int[]{600, 200, 200},
                new int[]{200, 1000, 1000}, new long[]{1, 3, 1}));
        assertArrayEquals("what no part holds is dropped", new int[]{100, 140}, WordBudget.capped(
                new int[]{500, 500}, new int[]{100, 140}, new long[]{1, 1}));
        assertArrayEquals("within the bounds nothing changes", new int[]{300, 400}, WordBudget.capped(
                new int[]{300, 400}, new int[]{1000, 400}, new long[]{5, 1}));
        assertArrayEquals(new int[0], WordBudget.capped(new int[0], new int[0], new long[0]));
    }

    @Test
    public void aBoundNeverRaisesAPartAboveItAndKeepsWhatTheBoundsHold() {
        for (int seed = 1; seed < 200; seed++) {
            int n = 1 + seed % 5;
            int[] words = new int[n];
            int[] bounds = new int[n];
            long[] weights = new long[n];
            int all = 0;
            int room = 0;
            for (int i = 0; i < n; i++) {
                words[i] = 100 + (seed * 37 + i * 101) % 900;
                bounds[i] = 70 * (1 + (seed * 13 + i * 7) % 15);
                weights[i] = (seed + i * 3) % 9;
                all += words[i];
                room += bounds[i];
            }
            int[] out = WordBudget.capped(words, bounds, weights);
            for (int i = 0; i < n; i++) {
                assertTrue("seed " + seed, out[i] <= bounds[i]);
                assertTrue("seed " + seed, out[i] >= Math.min(words[i], bounds[i]));
            }
            assertEquals("seed " + seed, Math.min(all, room), sum(out));
        }
    }

    @Test
    public void nothingToSpreadGivesNothing() {
        assertArrayEquals(new int[0], WordBudget.spread(5000, new long[0], null, 350));
        assertArrayEquals(new int[]{0, 0}, WordBudget.spread(0, new long[]{1, 2}, null, 350));
    }
}
