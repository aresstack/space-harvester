package com.aresstack.research4j.basket;

import com.aresstack.knowledge4j.access.FetchedContent;
import com.aresstack.knowledge4j.access.SourceAccessException;
import com.aresstack.knowledge4j.access.SourceHit;
import com.aresstack.knowledge4j.access.SourceQuery;
import com.aresstack.knowledge4j.access.SourceSearchPort;
import com.aresstack.knowledge4j.basket.SourceBasket;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.policy.GuardedSourceSearchPort;
import com.aresstack.knowledge4j.policy.RunGuard;
import com.aresstack.knowledge4j.run.CancellationSignal;
import com.aresstack.knowledge4j.run.CancelledException;
import com.aresstack.knowledge4j.run.ProgressListener;
import com.aresstack.knowledge4j.scope.Fence;
import com.aresstack.knowledge4j.source.SourceRef;
import com.aresstack.knowledge4j.source.domain.DomainIdentity;
import com.aresstack.knowledge4j.source.domain.DomainKeyResolver;
import com.aresstack.knowledge4j.source.domain.PublicSuffixDomainKeyResolver;
import com.aresstack.research4j.acquire.FetchChannel;
import com.aresstack.research4j.acquire.SearchChannel;
import com.aresstack.research4j.acquire.SourceRouter;
import com.aresstack.research4j.concept.SearchConcept;
import com.aresstack.research4j.judge.SourceJudge;
import com.aresstack.research4j.judge.SourceProfile;
import com.aresstack.research4j.plan.ConceptQueryPlanner;
import com.aresstack.research4j.plan.PlannedQuery;
import com.aresstack.research4j.run.ModelAsk;
import com.aresstack.research4j.run.Parallel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 3: the research fills the basket (Korb). It knows neither a browser nor a search engine nor the writing; it
 * works only over the neutral ports of Knowledge4j:
 * <ol>
 *     <li>The planner writes queries per node of the concept (the mind map), inside the fence.</li>
 *     <li>Every query goes to every search the host offers ({@link SearchChannel}); hits are merged per source.</li>
 *     <li>The router reads each new source over the first reader that supports it ({@link FetchChannel}).</li>
 *     <li>The judge checks each source against the fence; every source but a clearly outside one goes through the
 *     host's {@link SourceKeeper} into the basket. Linked or child sources of an accepted source are read in the next
 *     round, as deep as the host sets, so an internal knowledge base is followed like links between web pages.</li>
 *     <li>A node is covered when it has enough sources from enough sites (settings of the host). The nodes not covered
 *     yet are planned again from another angle.</li>
 * </ol>
 * The research ends at a standstill, when rounds in a row add nothing to the basket, when every node is covered, or
 * when there is nothing new to search or read. There is no fixed number of rounds.
 *
 * <p>The concept only steers the search: the basket carries no trace of it, and nothing outside Research4j sees it.
 * Every search and every read goes through the run's {@link RunGuard}, so the host's transfer policy is checked on
 * every exit; what the policy refuses is a visible skipped step with its reason. The model calls go through the
 * {@link MainModelChat} the host binds to its model port.</p>
 *
 * <p><b>Two operating modes, one code.</b> {@link Settings#capacity()} is the number of concurrent calls the host's
 * model takes (1 for a large model alone on one graphics card, more for a cloud service), and
 * {@link Settings#accessParallel()} the number of concurrent searches and reads. Planning and judging run with that
 * many calls at the same time; the logical order and the outcome are the same at every capacity.</p>
 */
public final class BasketResearch {

    /**
     * How the research works; every value is a setting of the host.
     *
     * @param language        the language of queries and prompts as an IETF tag
     * @param capacity        concurrent model calls, from the host's model port
     * @param accessParallel  concurrent searches and reads
     * @param sourcesPerNode  sources a node needs to count as covered
     * @param sitesPerNode    distinct sites among them (registrable domain of a web address, the document otherwise)
     * @param quietRounds     rounds in a row without a new source in the basket that end the research
     * @param linkDepth       how deep linked or child sources of an accepted source are followed; 0 = not at all
     * @param judgeRetries    how often a source whose judgement failed is judged again in a later round
     * @param planner         the planner's settings
     * @param judge           the judge's settings
     */
    public record Settings(String language, int capacity, int accessParallel, int sourcesPerNode, int sitesPerNode,
                           int quietRounds, int linkDepth, int judgeRetries, ConceptQueryPlanner.Settings planner,
                           SourceJudge.Settings judge) {
        public Settings {
            language = language == null ? "" : language;
            if (capacity < 1 || accessParallel < 1 || sourcesPerNode < 1 || sitesPerNode < 1 || quietRounds < 1
                    || linkDepth < 0 || judgeRetries < 0) {
                throw new IllegalArgumentException("invalid research settings");
            }
            planner = planner == null ? ConceptQueryPlanner.Settings.defaults() : planner;
            judge = judge == null ? SourceJudge.Settings.defaults() : judge;
        }

        /**
         * The values of Ask AI: 3 sources from 2 sites per node (its plan coverage), an end after 2 quiet rounds,
         * children one level deep, one more try for a failed judgement.
         */
        public static Settings defaults(String language, int capacity, int accessParallel) {
            return new Settings(language, capacity, accessParallel, 3, 2, 2, 1, 1, null, null);
        }

        public Settings withCapacity(int newCapacity) {
            return new Settings(language, newCapacity, accessParallel, sourcesPerNode, sitesPerNode, quietRounds,
                    linkDepth, judgeRetries, planner, judge);
        }
    }

    /** Why the research ended. */
    public enum Stop {
        /** Rounds in a row added nothing to the basket. */
        STILLSTAND,
        /** Every node of the concept is covered. */
        ABGEDECKT,
        /** There was nothing new left to search or read. */
        NICHTS_NEUES,
        /** The host cancelled the run; the basket holds what was found until then. */
        ABGEBROCHEN
    }

    /** One query sent to one search. */
    public record SearchRecord(int round, String nodeId, String query, String channel, int hits, String problem) {
    }

    /** A search or read that did not happen or failed, with its reason; never with content. */
    public record SkippedAccess(int round, String step, String what, SourceAccessException.Reason reason,
                                String message) {
    }

    /** How far a node of the concept got. */
    public record NodeCoverage(String nodeId, String label, int sources, List<String> sites, boolean covered) {
    }

    /** The outcome of one research. */
    public record Result(SourceBasket basket, List<SourceProfile> profiles, List<SearchRecord> searches,
                         List<SkippedAccess> skipped, List<NodeCoverage> coverage, int rounds, Stop stop,
                         long modelCalls, long modelFailures) {
    }

    /** The steps a host can show progress for. */
    public static final String STEP_PLAN = "plan";
    public static final String STEP_SEARCH = "search";
    public static final String STEP_FETCH = "fetch";
    public static final String STEP_JUDGE = "judge";

    private final MainModelChat chat;
    private final List<SearchChannel> searches;
    private final List<FetchChannel> readers;
    private final SourceKeeper keeper;
    private final RunGuard guard;
    private final DomainKeyResolver domains;
    private final Settings settings;

    public BasketResearch(MainModelChat chat, List<SearchChannel> searches, List<FetchChannel> readers,
                          SourceKeeper keeper, RunGuard guard, Settings settings) {
        this(chat, searches, readers, keeper, guard, new PublicSuffixDomainKeyResolver(), settings);
    }

    public BasketResearch(MainModelChat chat, List<SearchChannel> searches, List<FetchChannel> readers,
                          SourceKeeper keeper, RunGuard guard, DomainKeyResolver domains, Settings settings) {
        this.chat = chat;
        this.searches = List.copyOf(searches == null ? List.of() : searches);
        this.readers = List.copyOf(readers == null ? List.of() : readers);
        this.keeper = Objects.requireNonNull(keeper, "keeper");
        this.guard = Objects.requireNonNull(guard, "the research does not start without the host's transfer guard");
        this.domains = Objects.requireNonNull(domains, "domains");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Runs the research.
     *
     * @param fence   mission and bounds
     * @param concept the mind map; {@code null} searches by the mission alone
     * @param start   the basket so far (a further search adds to it); {@code null} for an empty one. The result's
     *                basket carries {@code fence}, whatever fence the start basket had
     */
    public Result run(Fence fence, SearchConcept concept, SourceBasket start, CancellationSignal cancel,
                      ProgressListener progress) {
        Objects.requireNonNull(fence, "fence");
        return new Run(fence, concept == null ? SearchConcept.ofMission(fence.getMission()) : concept,
                start == null ? SourceBasket.empty() : start,
                cancel == null ? CancellationSignal.NEVER : cancel,
                progress == null ? ProgressListener.NONE : progress).execute();
    }

    /** A source to read, with the nodes it serves and how deep it lies below a search hit. */
    private record Candidate(SourceRef ref, Set<String> nodes, int depth) {
    }

    /** A read source waiting for its judgement. */
    private record Read(Candidate candidate, FetchedContent content, int tries) {
    }

    private final class Run {
        private final Fence fence;
        private final SearchConcept concept;
        private final CancellationSignal cancel;
        private final ProgressListener progress;
        private final ModelAsk model = new ModelAsk(chat);
        private final ConceptQueryPlanner planner;
        private final SourceJudge judge;
        private final SourceRouter router;
        private final List<SourceSearchPort> guardedSearches = new ArrayList<>();

        private SourceBasket basket;
        private final Map<String, SourceRef> accepted = new LinkedHashMap<>();
        private final Set<String> seen = new LinkedHashSet<>();
        private final Map<String, Set<String>> nodeSources = new LinkedHashMap<>();
        private final Map<String, Set<String>> nodeSites = new LinkedHashMap<>();
        private final List<String> searched = new ArrayList<>();
        private final List<SourceProfile> profiles = new ArrayList<>();
        private final List<SearchRecord> searchLog = new ArrayList<>();
        private final List<SkippedAccess> skipped = new ArrayList<>();
        private List<Candidate> links = new ArrayList<>();
        private List<Read> retries = new ArrayList<>();
        private int round;

        Run(Fence fence, SearchConcept concept, SourceBasket start, CancellationSignal cancel,
            ProgressListener progress) {
            this.fence = fence;
            this.concept = concept;
            this.cancel = cancel;
            this.progress = progress;
            this.planner = new ConceptQueryPlanner(model, settings.planner(), settings.language(), fence);
            this.judge = new SourceJudge(model, settings.judge(), settings.language(), fence);
            this.router = new SourceRouter(readers, guard);
            for (SearchChannel channel : searches) {
                guardedSearches.add(new GuardedSourceSearchPort(channel.port(), channel.target(), guard));
            }
            // The basket carries the fence it is collected for into Phase 4 (knowledge4j SourceBasket.fence()).
            this.basket = start.withFence(fence);
            for (SourceRef ref : start.sources()) {
                seen.add(key(ref));
                accepted.put(key(ref), ref);
            }
            for (SearchConcept.Node node : concept.nodes()) {
                nodeSources.put(node.id(), new LinkedHashSet<>());
                nodeSites.put(node.id(), new LinkedHashSet<>());
            }
        }

        Result execute() {
            Stop stop;
            try {
                List<PlannedQuery> queries = plan(concept.nodes());
                int quiet = 0;
                while (true) {
                    cancel.throwIfCancelled();
                    round++;
                    List<Candidate> candidates = search(queries);
                    candidates.addAll(links);
                    links = new ArrayList<>();
                    List<Read> reads = read(candidates);
                    reads.addAll(retries);
                    retries = new ArrayList<>();
                    int added = judgeAndKeep(reads);
                    quiet = added == 0 ? quiet + 1 : 0;
                    if (quiet >= settings.quietRounds()) {
                        stop = Stop.STILLSTAND;
                        break;
                    }
                    List<SearchConcept.Node> open = uncovered();
                    if (open.isEmpty() && links.isEmpty() && retries.isEmpty()) {
                        stop = Stop.ABGEDECKT;
                        break;
                    }
                    queries = plan(open);
                    if (queries.isEmpty() && links.isEmpty() && retries.isEmpty()) {
                        stop = Stop.NICHTS_NEUES;
                        break;
                    }
                }
            } catch (CancelledException cancelled) {
                stop = Stop.ABGEBROCHEN;
            }
            return new Result(basket, List.copyOf(profiles), List.copyOf(searchLog), List.copyOf(skipped),
                    coverage(), round, stop, model.calls(), model.failures());
        }

        private List<PlannedQuery> plan(List<SearchConcept.Node> nodes) {
            List<ConceptQueryPlanner.NodeRequest> requests = new ArrayList<>();
            for (SearchConcept.Node node : nodes) {
                requests.add(new ConceptQueryPlanner.NodeRequest(node, List.copyOf(nodeSites.get(node.id()))));
            }
            progress.onProgress(STEP_PLAN, 0, requests.size());
            List<ConceptQueryPlanner.NodePlan> plans = planner.plan(concept, requests, round + 1, searched,
                    settings.capacity(), cancel);
            progress.onProgress(STEP_PLAN, requests.size(), requests.size());
            List<PlannedQuery> queries = new ArrayList<>();
            for (ConceptQueryPlanner.NodePlan plan : plans) {
                queries.addAll(plan.queries());
            }
            return queries;
        }

        private record SearchTask(PlannedQuery query, int channel) {
        }

        private record SearchOutcome(List<SourceHit> hits, SourceAccessException problem) {
        }

        private List<Candidate> search(List<PlannedQuery> queries) {
            List<SearchTask> tasks = new ArrayList<>();
            for (PlannedQuery query : queries) {
                searched.add(query.text());
                for (int c = 0; c < searches.size(); c++) {
                    tasks.add(new SearchTask(query, c));
                }
            }
            AtomicLong done = new AtomicLong();
            progress.onProgress(STEP_SEARCH, 0, tasks.size());
            List<SearchOutcome> outcomes = Parallel.map(tasks, settings.accessParallel(), cancel, task -> {
                SearchChannel channel = searches.get(task.channel());
                try {
                    SourceQuery query = new SourceQuery(task.query().text(), settings.language(),
                            channel.hitsPerQuery(), guard.mark());
                    return new SearchOutcome(guardedSearches.get(task.channel()).search(query, cancel), null);
                } catch (SourceAccessException failed) {
                    return new SearchOutcome(List.of(), failed);
                } finally {
                    progress.onProgress(STEP_SEARCH, done.incrementAndGet(), tasks.size());
                }
            });
            Map<String, Candidate> found = new LinkedHashMap<>();
            for (int i = 0; i < tasks.size(); i++) {
                SearchTask task = tasks.get(i);
                SearchOutcome outcome = outcomes.get(i);
                String channel = searches.get(task.channel()).target().id();
                searchLog.add(new SearchRecord(round, task.query().nodeId(), task.query().text(), channel,
                        outcome.hits().size(), outcome.problem() == null ? "" : outcome.problem().getMessage()));
                if (outcome.problem() != null) {
                    skipped.add(new SkippedAccess(round, STEP_SEARCH, channel, outcome.problem().reason(),
                            outcome.problem().getMessage()));
                }
                for (SourceHit hit : outcome.hits()) {
                    String key = key(hit.ref());
                    String nodeId = task.query().nodeId();
                    if (accepted.containsKey(key)) {
                        credit(nodeId, accepted.get(key));
                    } else if (!seen.contains(key)) {
                        found.computeIfAbsent(key, k -> new Candidate(withTitle(hit), new LinkedHashSet<>(), 0))
                                .nodes().add(nodeId);
                    }
                }
            }
            return new ArrayList<>(found.values());
        }

        private record ReadOutcome(FetchedContent content, SourceAccessException problem) {
        }

        private List<Read> read(List<Candidate> candidates) {
            List<Candidate> todo = new ArrayList<>();
            for (Candidate candidate : candidates) {
                if (seen.add(key(candidate.ref()))) {
                    todo.add(candidate);
                }
            }
            AtomicLong done = new AtomicLong();
            progress.onProgress(STEP_FETCH, 0, todo.size());
            List<ReadOutcome> outcomes = Parallel.map(todo, settings.accessParallel(), cancel, candidate -> {
                try {
                    return new ReadOutcome(router.fetch(candidate.ref(), cancel), null);
                } catch (SourceAccessException failed) {
                    return new ReadOutcome(null, failed);
                } finally {
                    progress.onProgress(STEP_FETCH, done.incrementAndGet(), todo.size());
                }
            });
            List<Read> reads = new ArrayList<>();
            for (int i = 0; i < todo.size(); i++) {
                Candidate candidate = todo.get(i);
                ReadOutcome outcome = outcomes.get(i);
                if (outcome.problem() != null) {
                    skipped.add(new SkippedAccess(round, STEP_FETCH, candidate.ref().id(), outcome.problem().reason(),
                            outcome.problem().getMessage()));
                } else if (outcome.content().text().isBlank()) {
                    skipped.add(new SkippedAccess(round, STEP_FETCH, candidate.ref().id(),
                            SourceAccessException.Reason.NOT_FOUND, "the source has no readable text"));
                } else {
                    reads.add(new Read(candidate, outcome.content(), 0));
                }
            }
            return reads;
        }

        private int judgeAndKeep(List<Read> reads) {
            AtomicLong done = new AtomicLong();
            progress.onProgress(STEP_JUDGE, 0, reads.size());
            List<SourceProfile> verdicts = Parallel.map(reads, settings.capacity(), cancel, read -> {
                try {
                    return judge.judge(read.content());
                } finally {
                    progress.onProgress(STEP_JUDGE, done.incrementAndGet(), reads.size());
                }
            });
            int added = 0;
            for (int i = 0; i < reads.size(); i++) {
                Read read = reads.get(i);
                SourceProfile profile = verdicts.get(i);
                if (!profile.judged()) {
                    if (read.tries() < settings.judgeRetries()) {
                        retries.add(new Read(read.candidate(), read.content(), read.tries() + 1));
                    } else {
                        profiles.add(profile);
                    }
                    continue;
                }
                profiles.add(profile);
                if (!profile.fit().belongsInBasket()) {
                    continue;
                }
                SourceRef kept;
                try {
                    kept = keeper.keep(read.content());
                } catch (SourceAccessException failed) {
                    skipped.add(new SkippedAccess(round, "keep", read.content().ref().id(), failed.reason(),
                            failed.getMessage()));
                    continue;
                }
                SourceRef ref = kept == null ? read.content().ref() : kept;
                accepted.put(key(read.candidate().ref()), ref);
                accepted.put(key(ref), ref);
                seen.add(key(ref));
                int before = basket.sources().size();
                basket = basket.with(List.of(ref));
                if (basket.sources().size() > before) {
                    added++;
                }
                for (String nodeId : read.candidate().nodes()) {
                    credit(nodeId, ref);
                }
                if (read.candidate().depth() < settings.linkDepth()) {
                    for (SourceRef link : read.content().links()) {
                        if (!seen.contains(key(link))) {
                            links.add(new Candidate(link, new LinkedHashSet<>(read.candidate().nodes()),
                                    read.candidate().depth() + 1));
                        }
                    }
                }
            }
            return added;
        }

        private void credit(String nodeId, SourceRef ref) {
            Set<String> sources = nodeSources.get(nodeId);
            if (sources != null && sources.add(key(ref))) {
                nodeSites.get(nodeId).add(site(ref));
            }
        }

        private List<SearchConcept.Node> uncovered() {
            List<SearchConcept.Node> open = new ArrayList<>();
            for (SearchConcept.Node node : concept.nodes()) {
                if (!covered(node.id())) {
                    open.add(node);
                }
            }
            return open;
        }

        private boolean covered(String nodeId) {
            return nodeSources.get(nodeId).size() >= settings.sourcesPerNode()
                    && nodeSites.get(nodeId).size() >= settings.sitesPerNode();
        }

        private List<NodeCoverage> coverage() {
            List<NodeCoverage> out = new ArrayList<>();
            for (SearchConcept.Node node : concept.nodes()) {
                out.add(new NodeCoverage(node.id(), node.label(), nodeSources.get(node.id()).size(),
                        List.copyOf(nodeSites.get(node.id())), covered(node.id())));
            }
            return List.copyOf(out);
        }

        private String site(SourceRef ref) {
            String id = ref.id().toLowerCase(Locale.ROOT);
            if (id.startsWith("http://") || id.startsWith("https://")) {
                DomainIdentity identity = domains.resolve(ref.id());
                if (!identity.isEmpty()) {
                    return identity.getRegistrableDomain().isEmpty() ? identity.getHost()
                            : identity.getRegistrableDomain();
                }
            }
            return key(ref);
        }
    }

    private static SourceRef withTitle(SourceHit hit) {
        return hit.ref().title().isEmpty() && !hit.title().isEmpty() ? hit.ref().withTitle(hit.title()) : hit.ref();
    }

    private static String key(SourceRef ref) {
        return ref.kind().id() + '\u0000' + ref.id();
    }
}
