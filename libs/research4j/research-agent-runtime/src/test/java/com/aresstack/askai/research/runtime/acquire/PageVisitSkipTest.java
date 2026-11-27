package com.aresstack.askai.research.runtime.acquire;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Skip is about ONE page. The generation is what keeps that promise in both directions: the page being
 * worked on is the one that gets abandoned, and work that finishes after the fact can tell that it
 * belongs to a page nobody is waiting for any more.
 */
public class PageVisitSkipTest {

    private final AtomicBoolean runCancelled = new AtomicBoolean(false);
    private final PageVisitSkip skip = new PageVisitSkip(runCancelled);

    @Test
    public void skipAppliesToThePageThatIsBeingVisited() {
        skip.beginVisit();
        assertFalse(skip.isCurrentVisitSkipped());

        skip.requestSkip();

        assertTrue(skip.isCurrentVisitSkipped());
    }

    @Test
    public void theNextPageStartsUnskipped() {
        skip.beginVisit();
        skip.requestSkip();

        skip.beginVisit();

        assertFalse("skipping one page must not skip the one after it",
                skip.isCurrentVisitSkipped());
    }

    /** A relevance call that returns after the user moved on must not speak for the new page. */
    @Test
    public void workOfAnAbandonedVisitStaysAbandonedAfterTheNextOneStarts() {
        long abandoned = skip.beginVisit();
        skip.requestSkip();
        skip.beginVisit();

        assertTrue("the finished work still belongs to the page that was left",
                skip.isSkipped(abandoned));
        assertFalse(skip.isCurrentVisitSkipped());
    }

    @Test
    public void aCancelledRunCancelsThePagesWorkToo() {
        long generation = skip.beginVisit();
        assertFalse(skip.cancellationFor(generation).isCancelled());

        runCancelled.set(true);

        assertTrue(skip.cancellationFor(generation).isCancelled());
    }

    @Test
    public void theInferenceOfASkippedPageIsCancelled() {
        long generation = skip.beginVisit();
        skip.requestSkip();

        assertTrue("the reranker learns it through the signal it already honours",
                skip.cancellationFor(generation).isCancelled());
    }
}
