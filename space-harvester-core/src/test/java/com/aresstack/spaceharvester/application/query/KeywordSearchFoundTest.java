package com.aresstack.spaceharvester.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SearchSpaces;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.SourceSearchMatch;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Der Beobachter einer Schlagwortsuche ({@link KeywordSearch.Found}) erfährt die Funde jeder Quelle, sobald sie
 * geantwortet hat: Eine schnelle Quelle steht da, während eine langsame noch sucht, und der fertige Stapel nennt
 * danach alle.
 */
class KeywordSearchFoundTest {

    private static final AccessContext APP = AccessContext.technicalClient("app");

    private final FakeSource fast = new FakeSource("fast");
    private final FakeSource slow = new FakeSource("slow");
    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of(slow, fast);
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return all().stream().filter(source -> source.id().equals(sourceId)).findFirst();
        }
    };
    private final PageTextIndex texts = mock(PageTextIndex.class);
    private final KeywordSearch.Prefetch prefetch = (caller, hits) -> KeywordSearch.Prefetch.Fill.NONE;

    KeywordSearchFoundTest() {
        when(texts.search(anyString(), any(), any(), anyInt())).thenReturn(List.of());
    }

    @Test
    void aFastSourceIsReportedWhileASlowOneIsStillSearching() throws Exception {
        fast.results("betrieb", "1", "2");
        slow.results("betrieb", "9");
        // Die langsame Quelle antwortet erst, wenn die schnelle gemeldet wurde.
        CountDownLatch reported = new CountDownLatch(1);
        slow.waitFor = reported;
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        List<List<String>> hits = Collections.synchronizedList(new ArrayList<>());
        KeywordSearch.Found found = (sourceId, found1) -> {
            order.add(sourceId);
            hits.add(found1.stream().map(KeywordSearch.Hit::pageId).toList());
            reported.countDown();
        };

        KeywordSearch.Results results = search(allowAll()).search(APP, KeywordSearch.Query.of("betrieb"), null, 25,
                null, found);

        assertThat(order).as("die schnelle Quelle zuerst, obwohl der Katalog die langsame zuerst nennt")
                .containsExactly("fast", "slow");
        assertThat(hits).containsExactly(List.of("1", "2"), List.of("9"));
        assertThat(hits.get(0).get(0)).isEqualTo("1");
        assertThat(results.ranked()).extracting(KeywordSearch.Hit::pageId).containsExactlyInAnyOrder("1", "2", "9");
        assertThat(results.sources()).extracting(KeywordSearch.SourceState::sourceId).containsExactly("slow", "fast");
    }

    @Test
    void aReportedHitCarriesTitleAndExcerptLikeTheProvisionalListAndHonoursTheContentPolicy() throws Exception {
        fast.results("betrieb", "1");
        slow.results("betrieb", "9");
        // Für „slow“ gilt die Freigabe nur für Kopfdaten, nicht für den Inhalt: kein Auszug.
        AccessGuard guard = new AccessGuard(new com.aresstack.spaceharvester.application.access.AccessPolicy() {
            @Override
            public AccessDecision decide(AccessContext context,
                                         com.aresstack.spaceharvester.application.access.ResourceScope scope,
                                         AccessKind kind) {
                return decideSourceWide(context, scope.sourceId(), kind);
            }

            @Override
            public AccessDecision decideSourceWide(AccessContext context, String sourceId, AccessKind kind) {
                return "slow".equals(sourceId) && kind == AccessKind.CONTENT ? AccessDecision.DENY
                        : AccessDecision.ALLOW;
            }
        });
        Map<String, List<KeywordSearch.Hit>> reported = new java.util.concurrent.ConcurrentHashMap<>();

        search(guard).search(APP, KeywordSearch.Query.of("betrieb"), null, 25, null, reported::put);

        assertThat(reported.get("fast")).singleElement().satisfies(hit -> {
            assertThat(hit.title()).isEqualTo("Seite 1");
            assertThat(hit.snippet()).isEqualTo("Auszug 1");
        });
        assertThat(reported.get("slow")).singleElement().satisfies(hit -> assertThat(hit.snippet()).isEmpty());
    }

    @Test
    void anEndUserGetsNoProvisionalReportAndAFailingObserverDoesNotBreakTheSearch() throws Exception {
        fast.results("betrieb", "1");
        AccessContext user = AccessContext.endUser("u", "app",
                new com.aresstack.spaceharvester.application.access.UserCredential("fast", "t"));
        List<String> reported = Collections.synchronizedList(new ArrayList<>());

        KeywordSearch search = search(allowAll());
        search.search(user, KeywordSearch.Query.of("betrieb"), null, 25, null, (id, hits) -> reported.add(id));
        assertThat(reported).as("ADR 0014: Endnutzer sehen nur die endgültige Liste").isEmpty();

        KeywordSearch.Results results = search.search(APP, KeywordSearch.Query.of("betrieb"), null, 25, null,
                (id, hits) -> {
                    throw new IllegalStateException("Beobachter kaputt");
                });
        assertThat(results.ranked()).extracting(KeywordSearch.Hit::pageId).contains("1");
    }

    /** Ohne Beobachter sucht die Suche wie bisher. */
    @Test
    void withoutAnObserverNothingChanges() {
        fast.results("betrieb", "1");
        KeywordSearch.Results results = search(allowAll()).search(APP, KeywordSearch.Query.of("betrieb"), null, 25);
        assertThat(results.ranked()).extracting(KeywordSearch.Hit::pageId).contains("1");
    }

    /** Wie die App für ihren Betreiber ({@code LocalOperatorPolicy}): alles erlaubt, auch für die ganze Quelle. */
    private static AccessGuard allowAll() {
        return new AccessGuard(new com.aresstack.spaceharvester.application.access.AccessPolicy() {
            @Override
            public AccessDecision decide(AccessContext context,
                                         com.aresstack.spaceharvester.application.access.ResourceScope scope,
                                         AccessKind kind) {
                return AccessDecision.ALLOW;
            }

            @Override
            public AccessDecision decideSourceWide(AccessContext context, String sourceId, AccessKind kind) {
                return AccessDecision.ALLOW;
            }
        });
    }

    private KeywordSearch search(AccessGuard access) {
        return new KeywordSearch(catalog, access, texts, prefetch, 1, ExcerptPriority.off());
    }

    /** Eine Quelle mit eigener Suche; wartet auf Wunsch, bis ein Riegel fällt. */
    private static final class FakeSource implements HarvestSource {

        private final String id;
        private final Map<String, List<String>> results = new java.util.concurrent.ConcurrentHashMap<>();
        volatile CountDownLatch waitFor;

        FakeSource(String id) {
            this.id = id;
        }

        FakeSource results(String query, String... pageIds) {
            results.put(query, List.of(pageIds));
            return this;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String type() {
            return "fake";
        }

        @Override
        public String title() {
            return id;
        }

        @Override
        public Set<SourceCapability> capabilities() {
            return Set.of(SourceCapability.PARTITIONS, SourceCapability.LIVE_SEARCH);
        }

        @Override
        public List<Partition> partitions() {
            return List.of();
        }

        @Override
        public PageMetadataBatch pageMetadata(String partitionKey, String cursor, int limit) {
            return new PageMetadataBatch(List.of(), null);
        }

        @Override
        public PageMetadata pageMetadata(String pageId) {
            return new PageMetadata(pageId, pageId, "Seite " + pageId, "DEV", null, "1", null, null,
                    new TreeMap<>());
        }

        @Override
        public List<SourceSearchMatch> searchPages(String text, int limit, SearchSpaces spaces) {
            CountDownLatch latch = waitFor;
            if (latch != null) {
                try {
                    if (!latch.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Die schnelle Quelle wurde nie gemeldet");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return results.getOrDefault(text, List.of()).stream().limit(limit)
                    .map(id -> new SourceSearchMatch(id, "Seite " + id, "Auszug " + id, null, "1", null)).toList();
        }
    }
}
