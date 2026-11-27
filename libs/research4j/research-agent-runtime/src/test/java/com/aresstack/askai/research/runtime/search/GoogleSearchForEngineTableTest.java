package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection.Entry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Google follows the engine table of every search: off means no Google search, a changed row a new one. */
public class GoogleSearchForEngineTableTest {

    /** Records every build and hands out a fresh strategy each time. */
    private static final class RecordingFactory implements GoogleSearchForEngineTable.Factory {
        final List<String> builds = new ArrayList<String>();

        public SearchStrategy create(int resultPages, long delayMillis) {
            builds.add(resultPages + "/" + delayMillis);
            return new SearchStrategy() { // a new instance per build (a non-capturing lambda may be shared)
                public InitialSearchResult search(InitialSearchRequest request,
                                                  com.aresstack.askai.browser.search.inference.CancellationSignal
                                                          cancellation, SearchBudgetGate budget) {
                    return null;
                }
            };
        }
    }

    private static BrowserSearchEngineSelection table(boolean googleOn, int googlePages, int googleDelayMillis) {
        return new BrowserSearchEngineSelection(Arrays.asList(
                new Entry("google", googleOn, googlePages, googleDelayMillis),
                new Entry("duckduckgo", true)), null);
    }

    @Test
    public void aTableWithoutAnActiveGoogleRowHasNoGoogleSearch() {
        RecordingFactory factory = new RecordingFactory();
        GoogleSearchForEngineTable google = new GoogleSearchForEngineTable(factory);

        assertNull(google.forSelection(table(false, 3, 15_000)));
        assertNull(google.forSelection(new BrowserSearchEngineSelection(
                Arrays.asList(new Entry("duckduckgo", true)), null)));
        assertNull(google.forSelection(null));
        assertTrue("nothing is built for a table without Google", factory.builds.isEmpty());
    }

    @Test
    public void theGoogleSearchIsBuiltOnFirstUseWithTheRowsPagesAndDelayAndThenReused() {
        RecordingFactory factory = new RecordingFactory();
        GoogleSearchForEngineTable google = new GoogleSearchForEngineTable(factory);

        SearchStrategy first = google.forSelection(table(true, 3, 15_000));
        SearchStrategy second = google.forSelection(table(true, 3, 15_000));

        assertNotNull(first);
        assertSame(first, second);
        assertEquals(Arrays.asList("3/15000"), factory.builds);
    }

    @Test
    public void switchingGoogleOffAndOnFollowsTheTableOfEachSearch() {
        RecordingFactory factory = new RecordingFactory();
        GoogleSearchForEngineTable google = new GoogleSearchForEngineTable(factory);

        SearchStrategy on = google.forSelection(table(true, 3, 15_000));
        assertNull("Google switched off: the next search asks no Google", google.forSelection(table(false, 3, 15_000)));
        assertSame("switched on again with the same row: the built search is used again", on,
                google.forSelection(table(true, 3, 15_000)));
        assertEquals(1, factory.builds.size());
    }

    @Test
    public void aChangedPageCountOrDelayBuildsTheGoogleSearchAgain() {
        RecordingFactory factory = new RecordingFactory();
        GoogleSearchForEngineTable google = new GoogleSearchForEngineTable(factory);

        SearchStrategy threePages = google.forSelection(table(true, 3, 15_000));
        SearchStrategy twoPages = google.forSelection(table(true, 2, 15_000));
        SearchStrategy shorterDelay = google.forSelection(table(true, 2, 5_000));

        assertNotSame(threePages, twoPages);
        assertNotSame(twoPages, shorterDelay);
        assertEquals(Arrays.asList("3/15000", "2/15000", "2/5000"), factory.builds);
    }

    @Test
    public void onlyAnActiveGoogleRowCountsAsEnabled() {
        assertTrue(GoogleSearchForEngineTable.enabled(table(true, 3, 15_000)));
        assertFalse(GoogleSearchForEngineTable.enabled(table(false, 3, 15_000)));
        assertFalse(GoogleSearchForEngineTable.enabled(null));
    }
}
