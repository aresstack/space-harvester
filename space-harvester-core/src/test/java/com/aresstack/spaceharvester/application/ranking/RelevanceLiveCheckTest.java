package com.aresstack.spaceharvester.application.ranking;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemorySearchIndex.entry;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.application.query.InMemorySearchIndex;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.PageTextIndex;
import com.aresstack.spaceharvester.application.query.SearchHit;
import com.aresstack.spaceharvester.application.query.SearchIndexState;
import com.aresstack.spaceharvester.application.query.SearchSort;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SearchSpaces;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.source.KnowledgeTextSegmentation;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Live-Prüfung je Relevanztreffer (ADR 0011, Punkt 7): Recht und Version aus einem Metadatenabruf, gesperrte Seiten
 * fallen weg und Kandidaten rücken nach, neuere Versionen werden nachgeladen, nicht prüfbare nie ausgeliefert.
 */
class RelevanceLiveCheckTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("etl");
    private static final SearchIndexState STATE = new SearchIndexState("i1", Instant.parse("2026-10-17T08:00:00Z"));
    private static final List<String> AXES = List.of("betrieb", "architektur", "urlaub");

    /** Kopfdaten, die die Quelle live meldet; fehlt eine Seite, gilt sie als nicht vorhanden. */
    private final Map<String, Object> live = new ConcurrentHashMap<>();
    private final List<String> metadataCalls = new CopyOnWriteArrayList<>();
    private final List<PageKey> renewed = new CopyOnWriteArrayList<>();
    private final InMemorySearchIndex index = new InMemorySearchIndex();
    private final InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();

    private final HarvestSource source = (HarvestSource) Proxy.newProxyInstance(
            HarvestSource.class.getClassLoader(), new Class<?>[] {HarvestSource.class}, (proxy, method, args) -> {
                if (!method.getName().equals("pageMetadata")) {
                    return null;
                }
                String pageId = (String) args[0];
                metadataCalls.add(pageId);
                Object answer = live.get(pageId);
                if (answer instanceof SourceAccessException e) {
                    throw e;
                }
                if (answer == null) {
                    throw new SourceAccessException("wiki", SourceAccessException.Reason.NOT_FOUND, "nicht da", null);
                }
                return answer;
            });

    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of(source);
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return "wiki".equals(sourceId) ? Optional.of(source) : Optional.empty();
        }
    };

    private final EmbeddingProcessor processor = new EmbeddingProcessor(new EmbeddingProvider() {
        @Override
        public String modelId() {
            return "axes";
        }

        @Override
        public int dimensions() {
            return AXES.size();
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            throw new AssertionError("Seiten werden in diesem Test nicht eingebettet");
        }

        @Override
        public List<float[]> embedQueries(List<String> texts) {
            return texts.stream().map(RelevanceLiveCheckTest::axes).toList();
        }
    }, 1500, KnowledgeTextSegmentation.defaults());

    @BeforeEach
    void publish() {
        index.publish("wiki", STATE, List.of(
                entry(page("DEV", "1", "Betrieb eins", "1"), confirmed("g1")),
                entry(page("DEV", "2", "Betrieb zwei", "1"), confirmed("g1")),
                entry(page("DEV", "3", "Betrieb drei", "1"), confirmed("g1")),
                entry(page("DEV", "4", "Betrieb vier", "1"), confirmed("g1"))));
        // Reihenfolge nach Ähnlichkeit: 1, 2, 3, 4.
        embed("1", "[1,0,0]");
        embed("2", "[0.9,0.1,0]");
        embed("3", "[0.8,0.3,0]");
        embed("4", "[0.6,0.6,0]");
        for (String id : List.of("1", "2", "3", "4")) {
            live.put(id, metadata("DEV", id, "1"));
        }
    }

    @Test
    void everyDeliveredHitIsCheckedLiveAndCurrentPagesAreNotReloaded() {
        RelevanceSearch.RelevanceResults results = search((context, scope, kind) -> AccessDecision.ALLOW)
                .search(CALLER, query(), "Betrieb", 2);

        assertThat(ids(results)).containsExactly("1", "2");
        assertThat(results.complete()).isTrue();
        assertThat(metadataCalls).containsExactlyInAnyOrder("1", "2");
        assertThat(results.hits()).allSatisfy(hit -> assertThat(hit.relevance().freshness())
                .isEqualTo(RelevanceSearch.Freshness.CURRENT));
        assertThat(renewed).isEmpty();
    }

    @Test
    void aPageMovedToALockedSpaceVanishesAndTheNextCandidateFollows() {
        // Im Index noch DEV, bei der Quelle inzwischen im gesperrten Space.
        live.put("1", metadata("SECRET", "1", "1"));
        AccessPolicy noSecret = (context, scope, kind) -> "SECRET".equals(scope.spaceKey())
                ? AccessDecision.DENY : AccessDecision.ALLOW;

        RelevanceSearch.RelevanceResults results = search(noSecret).search(CALLER, query(), "Betrieb", 2);

        assertThat(ids(results)).containsExactly("2", "3");
        assertThat(results.complete()).isTrue();
    }

    @Test
    void aPageTheSourceNoLongerShowsVanishes() {
        live.remove("2");

        assertThat(ids(search((context, scope, kind) -> AccessDecision.ALLOW).search(CALLER, query(), "Betrieb", 3)))
                .containsExactly("1", "3", "4");
    }

    @Test
    void aNewerVersionIsReloadedAndTheHitIsMarkedStale() {
        live.put("1", metadata("DEV", "1", "2"));

        RelevanceSearch.RelevanceResults results = search((context, scope, kind) -> AccessDecision.ALLOW)
                .search(CALLER, query(), "Betrieb", 2);

        assertThat(ids(results)).containsExactly("1", "2");
        assertThat(results.hits().get(0).relevance().freshness()).isEqualTo(RelevanceSearch.Freshness.STALE);
        assertThat(results.hits().get(1).relevance().freshness()).isEqualTo(RelevanceSearch.Freshness.CURRENT);
        assertThat(renewed).containsExactly(new PageKey("wiki", "1"));
    }

    @Test
    void anUncheckableHitIsNeverDeliveredAndTheResultIsIncomplete() {
        live.put("1", new SourceAccessException("wiki", SourceAccessException.Reason.UNAVAILABLE, "weg", null));

        RelevanceSearch.RelevanceResults results = search((context, scope, kind) -> AccessDecision.ALLOW)
                .search(CALLER, query(), "Betrieb", 2);

        assertThat(ids(results)).containsExactly("2", "3");
        assertThat(results.complete()).isFalse();
    }

    @Test
    void spaceFiltersApplyToTheLiveSpace() {
        live.put("2", metadata("OPS", "2", "1"));
        PageSearchQuery dev = PageSearchQuery.of("wiki", List.of("DEV"), null, null, null, null, null, null,
                SearchSort.TITLE);

        assertThat(ids(search((context, scope, kind) -> AccessDecision.ALLOW).search(CALLER, dev, "Betrieb", 3)))
                .containsExactly("1", "3", "4");
    }

    @Test
    void aFullTextHitMovedToASpaceWithoutContentAccessVanishes() {
        index.publish("wiki", STATE, List.of(
                entry(page("DEV", "1", "Betrieb eins", "1"), confirmed("g1")),
                entry(page("DEV", "5", "Wartung", "1"), confirmed("g1"))));
        // Kein Embedding für 5: ihr Platz stammt nur aus dem Volltext. Bei der Quelle liegt sie inzwischen in SECRET,
        // wo nur Kopfdaten freigegeben sind.
        live.put("5", metadata("SECRET", "5", "1"));
        AccessPolicy secretWithoutContent = (context, scope, kind) -> kind == AccessKind.CONTENT
                && "SECRET".equals(scope.spaceKey()) ? AccessDecision.DENY : AccessDecision.ALLOW;
        PageTextIndex texts = new PageTextIndex() {
            @Override
            public void put(Entry entry) {
            }

            @Override
            public Optional<String> inputSha256(PageKey key) {
                return Optional.empty();
            }

            @Override
            public void remove(PageKey key) {
            }

            @Override
            public void removeSource(String sourceId) {
            }

            @Override
            public List<Match> search(String text, Set<String> sourceIds, SearchSpaces spaces, int limit) {
                return List.of(new Match("wiki", "5", "DEV", "Wartung", "Wartung"));
            }

            @Override
            public void flush() {
            }
        };
        RelevanceSearch search = new RelevanceSearch(catalog, index, new AccessGuard(secretWithoutContent), store,
                store, processor, (caller, key) -> renewed.add(key), SearchReranker.NONE, texts);

        RelevanceSearch.RelevanceResults results = search.search(CALLER, query(), "Betrieb Wartung", 5);

        assertThat(ids(results)).containsExactly("1");
        assertThat(results.complete()).isTrue();
    }

    @Test
    void theRerankerGetsCachedTextOnlyForTheSpaceThePageIsInNow() {
        index.publish("wiki", STATE, List.of(
                entry(page("DEV", "1", "Betrieb eins", "1"), confirmed("g1")),
                entry(page("DEV", "5", "Betrieb fünf", "1"), confirmed("g1"))));
        // 5 ist ein Titeltreffer ohne Embedding; bei der Quelle liegt sie inzwischen in SECRET, wo nur Kopfdaten
        // freigegeben sind. Ihr Text aus dem Cache darf nicht an den Reranker gehen.
        live.put("5", metadata("SECRET", "5", "1"));
        AccessPolicy secretWithoutContent = (context, scope, kind) -> kind == AccessKind.CONTENT
                && "SECRET".equals(scope.spaceKey()) ? AccessDecision.DENY : AccessDecision.ALLOW;
        PageTextIndex texts = new PageTextIndex() {
            @Override
            public void put(Entry entry) {
            }

            @Override
            public Optional<String> inputSha256(PageKey key) {
                return Optional.empty();
            }

            @Override
            public Optional<String> text(PageKey key) {
                return Optional.of("Geheimtext " + key.pageId());
            }

            @Override
            public void remove(PageKey key) {
            }

            @Override
            public void removeSource(String sourceId) {
            }

            @Override
            public List<Match> search(String text, Set<String> sourceIds, SearchSpaces spaces, int limit) {
                return List.of();
            }

            @Override
            public void flush() {
            }
        };
        Map<String, String> documents = new ConcurrentHashMap<>();
        SearchReranker recording = new SearchReranker() {
            @Override
            public String id() {
                return "aufzeichnung@1";
            }

            @Override
            public List<SearchHit> rerank(PageSearchQuery query, List<SearchHit> candidates) {
                return candidates;
            }

            @Override
            public List<SearchHit> rerank(Request request) {
                request.candidates().forEach(hit -> documents.put(hit.page().pageId(), request.documents().apply(hit)));
                return request.candidates();
            }
        };
        PageSearchQuery byTitle = PageSearchQuery.of("wiki", List.of(), "betrieb", null, null, null, null, null,
                SearchSort.TITLE);

        RelevanceSearch.RelevanceResults results = new RelevanceSearch(catalog, index,
                new AccessGuard(secretWithoutContent), store, store, processor, (caller, key) -> renewed.add(key),
                recording, texts).search(CALLER, byTitle, "Betrieb", 5);

        assertThat(ids(results)).containsExactly("1", "5");
        assertThat(documents).containsEntry("1", "Betrieb eins\nGeheimtext 1").containsEntry("5", "Betrieb fünf");
        assertThat(metadataCalls).contains("5");
    }

    private RelevanceSearch search(AccessPolicy policy) {
        return new RelevanceSearch(catalog, index, new AccessGuard(policy), store, store, processor,
                (caller, key) -> renewed.add(key));
    }

    private static PageSearchQuery query() {
        return PageSearchQuery.of("wiki", List.of(), null, null, null, null, null, null, SearchSort.TITLE);
    }

    private static List<String> ids(RelevanceSearch.RelevanceResults results) {
        return results.hits().stream().map(hit -> hit.hit().page().pageId()).toList();
    }

    private static float[] axes(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        float[] vector = new float[AXES.size()];
        for (int i = 0; i < AXES.size(); i++) {
            vector[i] = lower.contains(AXES.get(i)) ? 1 : 0;
        }
        return vector;
    }

    private void embed(String pageId, String vector) {
        String json = "{\"model\":\"axes\",\"dimensions\":3,\"chunks\":[{\"charStart\":0,\"charEnd\":10,"
                + "\"section\":null,\"vector\":" + vector + "}]}";
        store.save(new Enrichment(new PageKey("wiki", pageId), "embedding", processor.version(),
                "sha256:" + "1".repeat(64), "1", Enrichment.Status.OK, json, List.of(), null,
                Instant.parse("2026-10-17T07:00:00Z")));
    }

    private static PageMetadata metadata(String space, String pageId, String version) {
        return new PageMetadata(pageId, "confluence:wiki/page/" + pageId, "Betrieb " + pageId, space, null, version,
                Instant.parse("2026-10-01T00:00:00Z"), null, PageMetadata.sorted(Map.of()));
    }

    private static PageSnapshot page(String space, String pageId, String title, String version) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:wiki/page/" + pageId, title, space, null,
                version, Instant.parse("2026-10-01T00:00:00Z"), null, PageMetadata.sorted(Map.of()));
        return PageSnapshot.of("wiki", metadata, new NativePayload("{}".getBytes(StandardCharsets.UTF_8),
                "application/json", "test"), Instant.parse("2026-10-16T20:00:00Z"));
    }
}
