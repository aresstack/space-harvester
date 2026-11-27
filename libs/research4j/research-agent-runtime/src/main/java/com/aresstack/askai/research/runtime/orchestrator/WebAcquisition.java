package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.runtime.acquire.WebSearchApplicationService;
import com.aresstack.askai.research.runtime.loop.ResearchStopReason;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import java.util.function.Consumer;

/**
 * The ONE research funnel as a web-research worker reaches it (ADR 0001, driver 2): one search run of the
 * session's autonomous acquisition ({@code ResearchLoop} over {@link WebSearchApplicationService}, with the
 * scope fence, the SC1 gates and the source acceptance), for one query. The session implements it; there is
 * no second acquisition path.
 */
public interface WebAcquisition {

    /**
     * Runs one search run for {@code query}.
     *
     * @param research the research-control lane, already restricted to the worker role's tools
     * @param accepted notified for every newly accepted source
     * @param maxDurationMillis the task's remaining time; the run ends at the earlier of this and the search-run
     *                          time limit ({@code <= 0}: the search-run limit alone)
     * @param tokens            the task's token account; model calls of the search run are metered onto it
     * @param exhausted         true once the task's budget is spent; the search run's model calls then fail fast
     * @return why the run stopped and a one-line summary
     */
    Result acquire(String query, ToolInvoker research, Consumer<WebSearchApplicationService.AcceptedSource> accepted,
                   long maxDurationMillis, java.util.concurrent.atomic.AtomicLong tokens,
                   java.util.function.BooleanSupplier exhausted);

    /** How one search run ended. */
    final class Result {
        private final ResearchStopReason stopReason;
        private final String summary;

        public Result(ResearchStopReason stopReason, String summary) {
            this.stopReason = stopReason;
            this.summary = summary == null ? "" : summary;
        }

        public ResearchStopReason getStopReason() {
            return stopReason;
        }

        public String getSummary() {
            return summary;
        }
    }
}
