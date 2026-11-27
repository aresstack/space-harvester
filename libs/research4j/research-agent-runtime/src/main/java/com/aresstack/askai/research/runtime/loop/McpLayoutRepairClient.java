package com.aresstack.askai.research.runtime.loop;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.analysis.SearchLayoutRepairCoordination;
import com.aresstack.askai.browser.search.analysis.SearchLayoutRepairCoordinator;
import com.aresstack.askai.browser.search.analysis.SearchLayoutRepairJson;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.browser.search.repair.PreparedWebSearchResult;
import com.aresstack.askai.browser.search.repair.SearchChallengeState;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairRequest;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The research-runtime driver of the two-step SERP layout repair over MCP. It calls
 * {@code web_search_prepare}; on REPAIR_REQUIRED it runs the model-using
 * {@link SearchLayoutRepairCoordinator} (profile or {@code StructuredInferencePort}) for each ordered
 * repair ticket and sends a validated decision back through {@code web_search_apply_layout} until one
 * yields organic results. If every repair fails, the harvested external links the prepare step carried
 * along are the result; only without them the outcome is EXTRACTION_FAILED — never a fabricated empty
 * engine. The central research tool budget is consulted BEFORE every MCP call via
 * {@link ToolBudget}; the coordinator's inference budget covers only the model calls.
 */
public final class McpLayoutRepairClient {

    /** The research runtime's central tool budget, checked before EACH MCP call. */
    public interface ToolBudget {
        boolean beforeToolCall();
    }

    /** The typed status the loop routes on — never a free-text string. */
    public enum Outcome {
        ORGANIC_RESULTS,
        NO_ORGANIC_RESULTS,
        CHALLENGE_PENDING,
        EXTRACTION_FAILED,
        BUDGET_EXHAUSTED,
        CANCELLED
    }

    /** The typed outcome the loop routes on, carrying transit hosts and challenges typed. */
    public static final class Result {
        public final Outcome status;
        public final List<SearchResultCandidate> candidates;
        public final List<String> providerHosts;
        public final List<SearchChallengeState> challenges;
        public final List<String> diagnostics;

        Result(Outcome status, List<SearchResultCandidate> candidates, List<String> providerHosts,
               List<SearchChallengeState> challenges, List<String> diagnostics) {
            this.status = status;
            this.candidates = Collections.unmodifiableList(candidates);
            this.providerHosts = Collections.unmodifiableList(providerHosts);
            this.challenges = Collections.unmodifiableList(challenges);
            this.diagnostics = Collections.unmodifiableList(diagnostics);
        }
    }

    private final ToolInvoker browser;
    private final SearchLayoutRepairCoordinator coordinator;

    public McpLayoutRepairClient(ToolInvoker browser, SearchLayoutRepairCoordinator coordinator) {
        this.browser = browser;
        this.coordinator = coordinator;
    }

    public Result searchWithRepair(String query, CancellationSignal cancellationSignal,
                                   long nowEpochMillis, ToolBudget budget)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        return searchWithRepair(query, null, cancellationSignal, nowEpochMillis, budget);
    }

    /**
     * @param engineIds one priority group of Search → Engines (all of them are searched and merged), or null for the
     *                  engines enabled in the browser's settings
     */
    public Result searchWithRepair(String query, List<String> engineIds, CancellationSignal cancellationSignal,
                                   long nowEpochMillis, ToolBudget budget)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        List<String> diagnostics = new ArrayList<String>();
        List<String> providerHosts = new ArrayList<String>();
        List<SearchChallengeState> challenges = new ArrayList<SearchChallengeState>();
        if (!budget.beforeToolCall()) {
            return result(Outcome.BUDGET_EXHAUSTED, noCandidates(), providerHosts, challenges,
                    diagnostics);
        }
        long prepareStart = System.currentTimeMillis();
        PreparedWebSearchResult prepared = SearchLayoutRepairJson.decodePrepared(
                browser.call("web_search_prepare", prepareArgs(query, engineIds)));
        diagnostics.addAll(prepared.diagnostics);
        providerHosts.addAll(prepared.providerHosts);
        challenges.addAll(prepared.challenges);
        // D1 per-step diagnostics: what web_search_prepare actually returned (the cold-start suspect).
        System.err.println("[initial-search] prepare status=" + prepared.status
                + " candidates=" + prepared.candidates.size()
                + " repairTickets=" + prepared.repairRequests.size()
                + " challenges=" + prepared.challenges.size()
                + " providerHosts=" + prepared.providerHosts
                + " elapsedMs=" + (System.currentTimeMillis() - prepareStart)
                + (prepared.diagnostics.isEmpty() ? "" : " diagnostics=" + prepared.diagnostics));

        switch (prepared.status) {
            case ORGANIC_RESULTS:
                return result(Outcome.ORGANIC_RESULTS, prepared.candidates, providerHosts, challenges,
                        diagnostics);
            case NO_ORGANIC_RESULTS:
                return result(Outcome.NO_ORGANIC_RESULTS, noCandidates(), providerHosts, challenges,
                        diagnostics);
            case CHALLENGE_PENDING:
                return result(Outcome.CHALLENGE_PENDING, noCandidates(), providerHosts, challenges,
                        diagnostics);
            case FAILED:
                return result(Outcome.EXTRACTION_FAILED, noCandidates(), providerHosts, challenges,
                        diagnostics);
            case REPAIR_REQUIRED:
            default:
                break;
        }

        java.util.Set<String> enginesRejectedByValidation = new java.util.HashSet<String>();
        for (SearchLayoutRepairRequest ticket : prepared.repairRequests) {
            if (cancellationSignal.isCancelled()) {
                return result(Outcome.CANCELLED, noCandidates(), providerHosts, challenges,
                        diagnostics);
            }
            if (enginesRejectedByValidation.contains(ticket.engineHost)) {
                // The engine's further result pages share the layout the model just failed on with
                // validation feedback in every attempt; repeating it per page was 3 x 3 useless calls.
                String skipped = "repair ticket " + ticket.snapshotId + " skipped: the AI layout "
                        + "for " + ticket.engineHost + " already failed validation on an earlier page";
                diagnostics.add(skipped);
                System.err.println("[initial-search] " + skipped);
                continue;
            }
            // D1 per-ticket diagnostics: the mechanical situation the coordinator starts from.
            String ticketSummary = ticketSummary(ticket);
            diagnostics.add(ticketSummary);
            System.err.println("[initial-search] " + ticketSummary);
            SearchLayoutRepairCoordination coordination =
                    coordinator.coordinate(ticket, cancellationSignal, nowEpochMillis);
            diagnostics.addAll(coordination.diagnostics);
            System.err.println("[initial-search] repair coordinate submit=" + coordination.shouldSubmit()
                    + selectedOrganicInfo(coordination)
                    + (coordination.diagnostics.isEmpty() ? "" : " diagnostics=" + coordination.diagnostics));
            if (!coordination.shouldSubmit()) {
                if (coordination.resolverResult != null && coordination.resolverResult.outcome
                        == com.aresstack.askai.browser.search.layout.SearchPageLayoutResolverOutcome
                                .VALIDATION_FAILED) {
                    enginesRejectedByValidation.add(ticket.engineHost);
                }
                continue; // AI disabled/unavailable/validation-failed for this engine — try the next
            }
            if (!budget.beforeToolCall()) {
                return result(Outcome.BUDGET_EXHAUSTED, noCandidates(), providerHosts, challenges,
                        diagnostics);
            }
            SearchLayoutRepairResult applied = SearchLayoutRepairJson.decodeRepairResult(
                    browser.call("web_search_apply_layout",
                            singletonArg("submission",
                                    SearchLayoutRepairJson.encodeSubmission(coordination.submission))));
            diagnostics.addAll(applied.diagnostics);
            System.err.println("[initial-search] apply organic=" + applied.isOrganic()
                    + " candidates=" + applied.candidates.size()
                    + (applied.diagnostics.isEmpty() ? "" : " diagnostics=" + applied.diagnostics));
            if (applied.isOrganic()) {
                return result(Outcome.ORGANIC_RESULTS, applied.candidates, providerHosts, challenges,
                        diagnostics);
            }
            // this ticket produced no valid block — try the next repair ticket / engine
        }
        if (!prepared.candidates.isEmpty()) {
            // Every repair failed, but the pages' external links were harvested: hand them to the
            // reranker instead of ending a search on a page that visibly listed results.
            String fallback = "layout repair failed for every ticket — falling back to "
                    + prepared.candidates.size() + " harvested external link(s)";
            diagnostics.add(fallback);
            System.err.println("[initial-search] " + fallback);
            return result(Outcome.ORGANIC_RESULTS, prepared.candidates, providerHosts, challenges,
                    diagnostics);
        }
        return result(Outcome.EXTRACTION_FAILED, noCandidates(), providerHosts, challenges,
                diagnostics);
    }

    /** One line answering: which engine, which snapshot, how sure was mechanics, was capture stable. */
    private static String ticketSummary(SearchLayoutRepairRequest ticket) {
        boolean unstableCapture = false;
        for (String warning : ticket.artifact.captureWarnings) {
            if (warning.startsWith("DOM_CHANGED_DURING_CAPTURE")) {
                unstableCapture = true;
            }
        }
        return "repair ticket engine=" + ticket.engineHost
                + " snapshot=" + ticket.snapshotId
                + " mechanicalConfidence=" + ticket.artifact.mechanicalConfidence
                + " capture=" + (unstableCapture ? "DOM_CHANGED" : "stable")
                + " candidateContainers=" + ticket.artifact.containerCandidates.size();
    }

    private static String selectedOrganicInfo(SearchLayoutRepairCoordination coordination) {
        if (!coordination.shouldSubmit()) {
            return "";
        }
        return " selectedOrganicContainers="
                + coordination.submission.decision.organicResultContainerIds.size();
    }

    private static Result result(Outcome status, List<SearchResultCandidate> candidates,
                                 List<String> providerHosts, List<SearchChallengeState> challenges,
                                 List<String> diagnostics) {
        return new Result(status, candidates, providerHosts, challenges, diagnostics);
    }

    private static List<SearchResultCandidate> noCandidates() {
        return new ArrayList<SearchResultCandidate>();
    }

    private static Map<String, Object> prepareArgs(String query, List<String> engineIds) {
        if (engineIds == null) {
            return singletonArg("query", query);
        }
        Map<String, Object> args = new java.util.LinkedHashMap<String, Object>();
        args.put("query", query);
        args.put("engines", String.join(",", engineIds));
        return args;
    }

    private static Map<String, Object> singletonArg(String key, String value) {
        return Collections.<String, Object>singletonMap(key, value);
    }
}
