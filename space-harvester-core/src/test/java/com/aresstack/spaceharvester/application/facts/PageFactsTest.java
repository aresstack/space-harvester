package com.aresstack.spaceharvester.application.facts;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.query.CacheMissException;
import com.aresstack.spaceharvester.application.query.CachedPage;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.application.query.PageQueries;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import com.aresstack.spaceharvester.application.sync.PageFreshness;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link PageFacts}: nur lesbare Seiten, auch nicht über Zähler oder Cursor; Faktenblatt wie das Lesen der Seite. */
class PageFactsTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("etl");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-18T22:00:00Z"), ZoneOffset.UTC);

    private final HarvestSource wiki = mock(HarvestSource.class);
    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of(wiki);
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return "wiki".equals(sourceId) ? Optional.of(wiki) : Optional.empty();
        }
    };
    private final InMemoryPageCache cache = new InMemoryPageCache();
    private final InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();

    /** SECRET ist nicht lesbar, von p3 in DEV nur die Metadaten. */
    private final AccessPolicy policy = (context, scope, kind) -> "SECRET".equals(scope.spaceKey())
            || "p3".equals(scope.pageId()) && kind == AccessKind.CONTENT ? AccessDecision.DENY : AccessDecision.ALLOW;

    @BeforeEach
    void pages() {
        when(wiki.id()).thenReturn("wiki");
        List<com.aresstack.spaceharvester.application.snapshot.PageSnapshot> dev = new ArrayList<>();
        for (String id : List.of("p1", "p2", "p3")) {
            dev.add(page("wiki", "DEV", id, null));
        }
        cache.publish("wiki", "DEV", confirmed("g1"), dev);
        cache.publish("wiki", "SECRET", confirmed("g2"), List.of(page("wiki", "SECRET", "s1", null)));
        for (String id : List.of("p1", "p2", "p3", "s1", "gone")) {
            store.save(FactTestResults.acronyms(new PageKey("wiki", id), FactTestResults.TEXT));
        }
    }

    private PageFacts facts() {
        AccessGuard guard = new AccessGuard(policy);
        PageQueries queries = new PageQueries(catalog, cache, guard,
                new PageFreshness(new SourceQueries(catalog, CLOCK), null, CLOCK), 1000);
        return new PageFacts(catalog, guard, queries, null, store, key -> cache.page(key).map(CachedPage::header));
    }

    @Test
    void searchReturnsOnlyFactsOfReadablePages() {
        PageFacts.Results results = facts().search(CALLER, PageFacts.Search.ofType(FactTypes.ACRONYM_DEFINITION),
                null, 100);

        assertThat(results.facts()).extracting(fact -> fact.page().pageId()).containsExactlyInAnyOrder("p1", "p2");
        assertThat(results.nextCursor()).isNull();
    }

    @Test
    void theCursorWalksAllReadableFactsOnce() {
        PageFacts facts = facts();
        PageFacts.Search search = PageFacts.Search.ofType(FactTypes.ACRONYM_CANDIDATE);
        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            PageFacts.Results results = facts.search(CALLER, search, cursor, 1);
            results.facts().forEach(fact -> seen.add(fact.page().pageId()));
            cursor = results.nextCursor();
        } while (cursor != null);

        assertThat(seen).containsExactlyInAnyOrder("p1", "p2");
    }

    @Test
    void conditionsAndSpacesNarrowTheSearch() {
        PageFacts.Search api = new PageFacts.Search(null, List.of(ArgumentCondition.parse("SHORT_FORM:API")), null,
                Set.of(), Set.of("SECRET"));

        assertThat(facts().search(CALLER, api, null, 100).facts()).isEmpty();
    }

    @Test
    void invalidSearchesAreRefused() {
        assertThatThrownBy(() -> new PageFacts.Search(null, List.of(), null, Set.of(), Set.of()))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> facts().search(CALLER, PageFacts.Search.ofType(FactTypes.MENTION), "../x", 10))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> facts().search(CALLER, PageFacts.Search.ofType(FactTypes.MENTION), null, 201))
                .isInstanceOf(InvalidQueryException.class);
    }

    @Test
    void theSheetOfAPageFollowsTheRightsOfReadingIt() {
        PageFacts.Sheet sheet = facts().factsOf(CALLER, "wiki", "p1", FactTypes.ACRONYM_DEFINITION);

        assertThat(sheet.spaceKey()).isEqualTo("DEV");
        assertThat(sheet.facts()).singleElement().satisfies(entry -> {
            assertThat(entry.fact().values(FactTypes.SHORT_FORM)).containsExactly("api");
            assertThat(entry.freshness()).isEqualTo(Enrichment.Freshness.STALE);
        });
        // Nicht lesbar heißt wie beim Lesen der Seite: nicht im Cache, ohne Hinweis auf die Rechte.
        assertThatThrownBy(() -> facts().factsOf(CALLER, "wiki", "s1", null))
                .isInstanceOf(CacheMissException.class);
        assertThatThrownBy(() -> facts().factsOf(CALLER, "wiki", "p3", null))
                .isInstanceOf(CacheMissException.class);
    }
}
