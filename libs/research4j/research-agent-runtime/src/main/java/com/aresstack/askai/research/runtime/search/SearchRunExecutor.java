package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.research.domain.search.DiscoveryBatch;
import com.aresstack.askai.research.domain.search.SearchRun;
import com.aresstack.askai.research.domain.search.SearchStrategyProfile;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the DISCOVERY half of a search under a {@link SearchStrategyProfile} and produces a
 * {@link SearchRun}. It opens no page and accepts no source — inspection is a separate concern that works
 * on the candidates this produced.
 * <p>
 * The two acquisition orders are two genuinely different traversals, not one loop with a flag:
 * <ul>
 * <li>{@code COLLECT_THEN_SELECT} fetches up to the batch limit FIRST and only then hands over a pool. The
 *     point is that the first result page must not decide the map of the topic on its own.</li>
 * <li>{@code PROGRESSIVE} evaluates after every batch and stops as soon as it has enough, so a targeted
 *     lookup does not pay for batches it will never look at.</li>
 * </ul>
 * A batch that fails does NOT discard what earlier batches produced: the run keeps its candidates and
 * records {@code TECHNICAL_PROBLEM} as the reason traversal ended. "It produced results" and "it ran to
 * completion" are two different statements.
 */
public final class SearchRunExecutor {

    /** How many hits to request per batch — providers treat this as an upper bound. */
    private static final int RESULTS_PER_BATCH = 15;

    /** Decides whether a PROGRESSIVE traversal has seen enough; never consulted for COLLECT_THEN_SELECT. */
    public interface Sufficiency {
        boolean isSufficient(CandidatePool pool, int batchesCollected);
    }

    /** The default: enough distinct hits to choose from, and more than one domain to choose between. */
    public static Sufficiency defaultSufficiency(final int minCandidates, final int minDomains) {
        return new Sufficiency() {
            public boolean isSufficient(CandidatePool pool, int batchesCollected) {
                return pool.size() >= minCandidates && pool.distinctDomains() >= minDomains;
            }
        };
    }

    private final SearchDiscovery discovery;
    private final Sufficiency sufficiency;

    public SearchRunExecutor(SearchDiscovery discovery) {
        this(discovery, defaultSufficiency(10, 3));
    }

    public SearchRunExecutor(SearchDiscovery discovery, Sufficiency sufficiency) {
        if (discovery == null) {
            throw new IllegalArgumentException("discovery must not be null");
        }
        this.discovery = discovery;
        this.sufficiency = sufficiency == null ? defaultSufficiency(10, 3) : sufficiency;
    }

    public SearchRun execute(String runId, String query, String language, String country,
                             SearchStrategyProfile profile, CancellationSignal cancellation,
                             SearchBudgetGate budget)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        return profile.getAcquisitionOrder() == SearchStrategyProfile.AcquisitionOrder.PROGRESSIVE
                ? progressive(runId, query, language, country, profile, cancellation, budget)
                : collectThenSelect(runId, query, language, country, profile, cancellation, budget);
    }

    /**
     * Collect first, judge later: every batch the profile allows is fetched before anything looks at the
     * pool. Nothing about the pool influences whether the next batch is fetched — that is the whole point.
     */
    private SearchRun collectThenSelect(String runId, String query, String language, String country,
                                        SearchStrategyProfile profile, CancellationSignal cancellation,
                                        SearchBudgetGate budget)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        CandidatePool pool = new CandidatePool();
        List<DiscoveryBatch> batches = new ArrayList<DiscoveryBatch>();
        DiscoveryRequest request = DiscoveryRequest.first(query, RESULTS_PER_BATCH, language, country);

        for (int ordinal = 1; ordinal <= profile.getMaxDiscoveryBatches(); ordinal++) {
            if (cancelled(cancellation) || !budget.beforeToolCall()) {
                return run(runId, query, profile, batches, pool, SearchRun.StopReason.CANCELLED);
            }
            DiscoveryBatchResult batch = discovery.discover(request, cancellation, budget);
            if (batch.status == InitialSearchStatus.TECHNICAL_PROBLEM) {
                // Whatever earlier batches produced stays valid; only traversal ends here.
                return run(runId, query, profile, batches, pool, SearchRun.StopReason.TECHNICAL_PROBLEM);
            }
            pool.add(batch.candidates, batch.provider, ordinal);
            batches.add(new DiscoveryBatch(ordinal, batch.provider, batch.candidates.size(),
                    batch.continuation));
            if (!batch.hasContinuation()) {
                return run(runId, query, profile, batches, pool, SearchRun.StopReason.NO_CONTINUATION);
            }
            request = request.next(batch.continuation);
        }
        return run(runId, query, profile, batches, pool, SearchRun.StopReason.BATCH_LIMIT_REACHED);
    }

    /**
     * Evaluate as you go: after each batch the pool is checked, and the next batch is only fetched when the
     * run still needs it. A targeted lookup therefore stops at batch 1 when batch 1 already answers it.
     */
    private SearchRun progressive(String runId, String query, String language, String country,
                                  SearchStrategyProfile profile, CancellationSignal cancellation,
                                  SearchBudgetGate budget)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        CandidatePool pool = new CandidatePool();
        List<DiscoveryBatch> batches = new ArrayList<DiscoveryBatch>();
        DiscoveryRequest request = DiscoveryRequest.first(query, RESULTS_PER_BATCH, language, country);

        for (int ordinal = 1; ordinal <= profile.getMaxDiscoveryBatches(); ordinal++) {
            if (cancelled(cancellation) || !budget.beforeToolCall()) {
                return run(runId, query, profile, batches, pool, SearchRun.StopReason.CANCELLED);
            }
            DiscoveryBatchResult batch = discovery.discover(request, cancellation, budget);
            if (batch.status == InitialSearchStatus.TECHNICAL_PROBLEM) {
                return run(runId, query, profile, batches, pool, SearchRun.StopReason.TECHNICAL_PROBLEM);
            }
            pool.add(batch.candidates, batch.provider, ordinal);
            batches.add(new DiscoveryBatch(ordinal, batch.provider, batch.candidates.size(),
                    batch.continuation));
            // THE difference to collect-then-select: the pool decides whether to keep going.
            if (sufficiency.isSufficient(pool, ordinal)) {
                return run(runId, query, profile, batches, pool, SearchRun.StopReason.SUFFICIENT);
            }
            if (!batch.hasContinuation()) {
                return run(runId, query, profile, batches, pool, SearchRun.StopReason.NO_CONTINUATION);
            }
            request = request.next(batch.continuation);
        }
        return run(runId, query, profile, batches, pool, SearchRun.StopReason.BATCH_LIMIT_REACHED);
    }

    private static boolean cancelled(CancellationSignal cancellation) {
        return cancellation != null && cancellation.isCancelled();
    }

    private static SearchRun run(String runId, String query, SearchStrategyProfile profile,
                                 List<DiscoveryBatch> batches, CandidatePool pool,
                                 SearchRun.StopReason stopReason) {
        // The run's own status follows from what it holds; the stop reason says why traversal ended. A
        // failed batch 3 after 28 usable hits is RESULTS + TECHNICAL_PROBLEM, never "nothing found".
        SearchRun.Status status = pool.isEmpty() && stopReason == SearchRun.StopReason.TECHNICAL_PROBLEM
                ? SearchRun.Status.TECHNICAL_PROBLEM : SearchRun.Status.RESULTS;
        return SearchRun.discovered(runId, query, profile.getName(), status, stopReason, batches,
                pool.candidates());
    }
}
