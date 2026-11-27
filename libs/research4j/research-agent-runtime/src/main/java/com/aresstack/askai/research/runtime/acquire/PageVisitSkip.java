package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.browser.search.inference.CancellationSignal;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * "Leave this page alone" — the user's escape from ONE page visit, separate from cancelling the run and
 * separate from pausing it.
 * <p>
 * The HUD's Skip button used to end up as {@code skipNextInterPageDelay = true}: the page kept being
 * read, assessed, accepted and harvested for links, and the only thing that was skipped was the pause
 * before the NEXT page. A control that is pressed on one page and takes effect on another is not a
 * control.
 * <p>
 * A visit is identified by a GENERATION. Skip marks the generation that is running now, so work that
 * finishes afterwards can tell that it belongs to a visit the user has abandoned and must not act on its
 * own result — the same reason a cancelled turn may not report a terminal for the turn after it.
 */
public final class PageVisitSkip {

    private final AtomicLong currentGeneration = new AtomicLong();
    private final AtomicLong skippedGeneration = new AtomicLong(-1L);
    private final AtomicBoolean runCancelled;

    /** @param runCancelled the RUN's cancellation; a cancelled run makes every visit skipped too */
    public PageVisitSkip(AtomicBoolean runCancelled) {
        this.runCancelled = runCancelled;
    }

    /** A new page is about to be visited: its own generation, unskipped. */
    public long beginVisit() {
        return currentGeneration.incrementAndGet();
    }

    /** The user pressed Skip while the current page was being worked on. */
    public void requestSkip() {
        skippedGeneration.set(currentGeneration.get());
    }

    /** Whether the visit that is running right now has been abandoned. */
    public boolean isCurrentVisitSkipped() {
        return skippedGeneration.get() == currentGeneration.get();
    }

    /** Whether {@code generation} was abandoned — for work that finishes after the fact. */
    public boolean isSkipped(long generation) {
        return skippedGeneration.get() == generation;
    }

    /**
     * The cancellation the page's own work runs under: the run's, plus this visit's skip. Handing this to
     * the reranker is what makes Skip effective DURING an inference instead of after it — there is no
     * second abort mechanism, only the one the inference already understands.
     */
    public CancellationSignal cancellationFor(final long generation) {
        return new CancellationSignal() {
            public boolean isCancelled() {
                return runCancelled.get() || isSkipped(generation);
            }
        };
    }
}
