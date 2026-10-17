package com.aresstack.spaceharvester.persistence.lucene;

import static com.aresstack.spaceharvester.persistence.lucene.TestSnapshots.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.query.CacheGenerationChangedException;
import com.aresstack.spaceharvester.application.query.CacheState;
import com.aresstack.spaceharvester.application.query.CacheUnavailableException;
import com.aresstack.spaceharvester.application.query.ListedPage;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchHit;
import com.aresstack.spaceharvester.application.query.SearchIndexState;
import com.aresstack.spaceharvester.application.query.SearchSort;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sync.BaselineEntry;
import com.aresstack.spaceharvester.application.sync.SyncBaseline;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Die Lucene-Suche gegen die Referenz aus dem Suchvertrag (#28): {@link PageSearchQuery#matches}, {@link SearchSort}. */
class LucenePageSearchIndexTest {

    private static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");

    @TempDir
    Path directory;

    private LucenePageCache cache;
    private LucenePageSearchIndex index;
    private final List<ListedPage> reference = new ArrayList<>();

    @BeforeEach
    void open() {
        cache = LucenePageCache.open(directory);
        index = new LucenePageSearchIndex(cache, Duration.ofSeconds(5));
    }

    @AfterEach
    void close() {
        cache.close();
    }

    /** Titel mit Satzzeichen, Groß-/Kleinschreibung, Umlauten, Zeichen außerhalb der BMP und gleichen Titeln. */
    private void corpus() {
        page("wiki", "100", "DEV", null, "Startseite", T0, "3");
        page("wiki", "101", "DEV", "100", "API-Übersicht (v2)", T0.plusSeconds(60), "1");
        page("wiki", "102", "DEV", "100", "api übersicht", T0.plusSeconds(60), "2");
        page("wiki", "103", "DEV", "101", "Release 1.2: API", T0.plusNanos(1), "1");
        page("wiki", "104", "DEV", "101", "Release 1.2: API", T0.plusNanos(2), "1");
        page("wiki", "105", "OPS", null, "Ｚ Breite Zeichen", null, "1");
        page("wiki", "106", "OPS", "105", "😀 Emoji vorn", T0.minusSeconds(3600), "1");
        page("wiki", "107", "OPS", "105", "Ärger mit Zeichen", T0.plusSeconds(120), null);
        page("wiki", "99", "OPS", null, "zebra", null, "1");
        page("other", "101", "DEV", null, "API-Übersicht", T0, "1");
    }

    @Test
    void aSourceWithoutPagesHasNoSearchGeneration() {
        assertThat(index.state("wiki")).isEmpty();
    }

    @Test
    void everyCommitThatChangesASourceStartsANewGenerationOnlyForThatSource() {
        corpus();
        SearchIndexState wiki = index.state("wiki").orElseThrow();
        SearchIndexState other = index.state("other").orElseThrow();

        page("wiki", "108", "DEV", null, "Neu", T0, "1");

        assertThat(index.state("wiki").orElseThrow().generation()).isNotEqualTo(wiki.generation());
        assertThat(index.state("other")).contains(other);
        assertThatThrownBy(() -> index.search(query("wiki", List.of(), null, null, null, null, null, null,
                SearchSort.PAGE_ID), wiki.generation(), null, 10)).isInstanceOf(CacheGenerationChangedException.class);
    }

    @Test
    void everySortAndFilterMatchesTheReferenceAcrossAllPageSizes() {
        corpus();
        List<PageSearchQuery> queries = new ArrayList<>();
        for (SearchSort sort : SearchSort.values()) {
            queries.add(query("wiki", List.of(), null, null, null, null, null, null, sort));
            queries.add(query("wiki", List.of(), "api", null, null, null, null, null, sort));
            queries.add(query("wiki", List.of(), "Übersicht API", null, null, null, null, null, sort));
            queries.add(query("wiki", List.of(), "1.2", null, null, null, null, null, sort));
            queries.add(query("wiki", List.of(), "zeichen", null, null, null, null, null, sort));
            queries.add(query("wiki", List.of("OPS"), null, null, null, null, null, null, sort));
            queries.add(query("wiki", List.of("DEV", "OPS"), null, null, "101", null, null, null, sort));
            queries.add(query("wiki", List.of(), null, "103", null, null, null, null, sort));
            queries.add(query("wiki", List.of(), null, null, null, "1", null, null, sort));
            queries.add(query("wiki", List.of(), null, null, null, null, T0.plusNanos(1), T0.plusSeconds(60), sort));
            queries.add(query("wiki", List.of(), null, null, null, null, T0.plusSeconds(60), null, sort));
            queries.add(query("wiki", List.of(), null, null, null, null, null, T0.plusNanos(2), sort));
            queries.add(query("other", List.of(), "api", null, null, null, null, null, sort));
            queries.add(query("wiki", List.of(), "fehlt", null, null, null, null, null, sort));
        }

        for (PageSearchQuery query : queries) {
            List<String> expected = reference.stream().filter(query::matches).sorted(query.sort().order())
                    .map(ListedPage::pageId).toList();
            String generation = index.state(query.sourceId()).orElseThrow().generation();
            for (int limit : new int[] {1, 2, 3, 100}) {
                assertThat(pageThrough(query, generation, limit)).as("%s, limit %d", query, limit)
                        .containsExactlyElementsOf(expected);
            }
        }
    }

    @Test
    void theSmallestRepresentableUpperTimeBoundMatchesNothing() {
        corpus();
        Instant smallest = Instant.ofEpochSecond(Math.floorDiv(Long.MIN_VALUE, 1_000_000_000L),
                Math.floorMod(Long.MIN_VALUE, 1_000_000_000L));
        PageSearchQuery query = query("wiki", List.of(), null, null, null, null, null, smallest, SearchSort.PAGE_ID);

        assertThat(index.search(query, index.state("wiki").orElseThrow().generation(), null, 10)).isEmpty();
    }

    @Test
    void hitsCarryTheCurrentHeaderThePayloadHashAndTheGenerationOfTheirSpace() {
        corpus();
        PageSnapshot confirmed = page("wiki", "200", "DOC", null, "Bestätigt", T0, "1");
        try (LucenePageCache.Batch batch = cache.begin()) {
            batch.confirm(new SyncBaseline("wiki", "DOC", 1, T0, new TreeMap<>(Map.of("200",
                    new BaselineEntry("200", "DOC", null, "1", confirmed.metadataHash(),
                            confirmed.attributesHash()))), new TreeSet<>()));
            batch.commit();
        }
        String generation = index.state("wiki").orElseThrow().generation();

        List<SearchHit> hits = index.search(query("wiki", List.of(), "bestätigt", null, null, null, null, null,
                SearchSort.PAGE_ID), generation, null, 10);
        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.page().header()).isEqualTo(confirmed.header());
            assertThat(hit.page().rawSha256()).isEqualTo(confirmed.payload().rawSha256());
            assertThat(hit.generation().id()).isEqualTo("g1");
            assertThat(hit.generation().state()).isEqualTo(CacheState.CONFIRMED);
        });
        assertThat(index.search(query("wiki", List.of("OPS"), null, null, null, null, null, null,
                SearchSort.PAGE_ID), generation, null, 1)).singleElement()
                .satisfies(hit -> assertThat(hit.generation().state()).isEqualTo(CacheState.INCOMPLETE));
    }

    @Test
    void aHeaderOnlyPageIsFoundWithoutPayloadHashAndAMovedPageUnderItsNewTitle() {
        PageSnapshot moved = page("wiki", "300", "DEV", null, "Alter Titel", T0, "1");
        PageHeader renamed = PageHeader.of("wiki", metadata("300", "OPS", null, "Neuer Titel", T0, "1"), T0);
        cache.saveHeader(renamed);
        reference.set(reference.size() - 1, new ListedPage(renamed, moved.payload().rawSha256()));
        cache.saveHeader(PageHeader.of("wiki", metadata("301", "OPS", null, "Nur Kopf", T0, "1"), T0));
        String generation = index.state("wiki").orElseThrow().generation();

        assertThat(index.search(query("wiki", List.of(), "alter", null, null, null, null, null, SearchSort.TITLE),
                generation, null, 10)).isEmpty();
        assertThat(index.search(query("wiki", List.of("OPS"), "titel", null, null, null, null, null,
                SearchSort.TITLE), generation, null, 10)).extracting(hit -> hit.page().rawSha256())
                .containsExactly(moved.payload().rawSha256());
        assertThat(index.search(query("wiki", List.of(), "kopf", null, null, null, null, null, SearchSort.TITLE),
                generation, null, 10)).singleElement()
                .satisfies(hit -> assertThat(hit.page().rawSha256()).isNull());
    }

    @Test
    void aRemovedPageIsNoLongerFound() {
        corpus();
        cache.remove(new PageKey("wiki", "103"));
        String generation = index.state("wiki").orElseThrow().generation();

        assertThat(index.search(query("wiki", List.of(), "release", null, null, null, null, null,
                SearchSort.PAGE_ID), generation, null, 10)).extracting(hit -> hit.page().pageId())
                .containsExactly("104");
    }

    @Test
    void thePositionHoldsOnlyTheSortValuesOfTheHit() {
        corpus();
        String generation = index.state("wiki").orElseThrow().generation();
        PageSearchQuery byTitle = query("wiki", List.of(), "startseite", null, null, null, null, null,
                SearchSort.TITLE);

        String position = index.search(byTitle, generation, null, 1).get(0).position();

        assertThat(position).startsWith("TITLE.").doesNotContain("\n").doesNotContain("wiki");
        assertThatThrownBy(() -> index.search(query("wiki", List.of(), null, null, null, null, null, null,
                SearchSort.PAGE_ID), generation, position, 10)).isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> index.search(byTitle, generation, "TITLE.!!.!!", 10))
                .isInstanceOf(InvalidQueryException.class);
    }

    @Test
    void aSearchGenerationSurvivesARestartAndAClosedIndexIsUnavailable() {
        corpus();
        SearchIndexState before = index.state("wiki").orElseThrow();
        cache.close();

        assertThatThrownBy(() -> index.state("wiki")).isInstanceOf(CacheUnavailableException.class);
        cache = LucenePageCache.open(directory);
        index = new LucenePageSearchIndex(cache, Duration.ofSeconds(5));
        assertThat(index.state("wiki")).contains(before);
    }

    // Globale Suche über mehrere Quellen (#114)

    @Test
    void aGlobalSearchSortsAndPagesAcrossSourcesLikeTheReference() {
        corpus();
        page("third", "100", "DEV", null, "Startseite", T0, "1");
        page("third", "101", "DEV", null, "API-Übersicht (v2)", T0.plusSeconds(60), "1");
        Set<String> scope = Set.of("wiki", "other", "third");
        List<PageSearchQuery> queries = new ArrayList<>();
        for (SearchSort sort : SearchSort.values()) {
            queries.add(query(null, List.of(), null, null, null, null, null, null, sort));
            queries.add(query(null, List.of(), "api", null, null, null, null, null, sort));
            queries.add(query(null, List.of("DEV"), null, null, null, null, null, null, sort));
            queries.add(query(null, List.of(), null, "101", null, null, null, null, sort));
            queries.add(query(null, List.of(), "startseite", null, null, null, null, null, sort));
            // Quellenliste (#114 Nachtrag): nur diese Quellen des Umfangs.
            queries.add(PageSearchQuery.of(null, List.of("third", "wiki"), List.of(), null, null, null, null, null,
                    null, sort));
            queries.add(PageSearchQuery.of(null, List.of("other", "fehlt"), List.of(), "api", null, null, null, null,
                    null, sort));
        }
        String generation = index.state(scope).orElseThrow().generation();

        for (PageSearchQuery query : queries) {
            List<String> expected = reference.stream().filter(query::matches).sorted(query.sort().order())
                    .map(LucenePageSearchIndexTest::key).toList();
            for (int limit : new int[] {1, 2, 3, 100}) {
                assertThat(pageThrough(query, scope, generation, limit)).as("%s, limit %d", query, limit)
                        .containsExactlyElementsOf(expected).doesNotHaveDuplicates();
            }
        }
        // Gleiche Seiten-ID in drei Quellen: drei Treffer, nach Quelle geordnet.
        assertThat(pageThrough(query(null, List.of(), null, "101", null, null, null, null, SearchSort.TITLE), scope,
                generation, 10)).containsExactly("other/101", "third/101", "wiki/101");
    }

    @Test
    void aGlobalSearchPagesThroughMoreThanAHundredHitsFromManySources() {
        List<String> sources = List.of("a", "b", "c", "d", "e", "f");
        try (LucenePageCache.Batch batch = cache.begin()) {
            for (String sourceId : sources) {
                for (int i = 0; i < 40; i++) {
                    // Gleiche IDs, Spaces und teils gleiche Titel und Zeiten in jeder Quelle.
                    PageSnapshot snapshot = PageSnapshot.of(sourceId, metadata(String.valueOf(i), "DEV", null,
                            "Seite " + (i % 7), i % 3 == 0 ? null : T0.plusSeconds(i % 5), "1"),
                            new NativePayload(json("{}"), "application/json", "confluence-content/v1"), T0);
                    batch.save(snapshot);
                    reference.add(ListedPage.of(snapshot.header(), snapshot));
                }
            }
            batch.commit();
        }
        Set<String> scope = Set.copyOf(sources);
        String generation = index.state(scope).orElseThrow().generation();

        for (SearchSort sort : SearchSort.values()) {
            PageSearchQuery query = query(null, List.of(), "seite", null, null, null, null, null, sort);
            List<String> expected = reference.stream().filter(query::matches).sorted(query.sort().order())
                    .map(LucenePageSearchIndexTest::key).toList();
            assertThat(expected).hasSize(240);
            for (int limit : new int[] {7, 100}) {
                assertThat(pageThrough(query, scope, generation, limit)).containsExactlyElementsOf(expected);
            }
        }
    }

    @Test
    void theGenerationOfSeveralSourcesChangesWithAnyOfThemButNotWithOthers() {
        corpus();
        Set<String> scope = Set.of("wiki", "other", "later");
        SearchIndexState before = index.state(scope).orElseThrow();
        assertThat(before.generation()).startsWith("g").doesNotContain("wiki");

        page("unrelated", "1", "DEV", null, "Fremd", T0, "1");
        assertThat(index.state(scope)).contains(before);

        page("later", "1", "DEV", null, "Neu im Index", T0, "1");
        SearchIndexState added = index.state(scope).orElseThrow();
        assertThat(added.generation()).isNotEqualTo(before.generation());
        PageSearchQuery all = query(null, List.of(), null, null, null, null, null, null, SearchSort.PAGE_ID);
        assertThatThrownBy(() -> index.search(all, scope, before.generation(), null, 10))
                .isInstanceOf(CacheGenerationChangedException.class);

        page("other", "300", "DEV", null, "Geändert", T0, "1");
        assertThat(index.state(scope).orElseThrow().generation()).isNotEqualTo(added.generation());
        assertThat(index.state(Set.of("nirgends", "auch-nicht"))).isEmpty();
        // Auch eine Menge aus einer Quelle nennt die Quelle mit; die Einzelquellensuche behält s<n>.
        assertThat(index.state(Set.of("wiki")).orElseThrow().generation()).startsWith("g")
                .isNotEqualTo(index.state(Set.of("other")).orElseThrow().generation());
        assertThat(index.state("wiki").orElseThrow().generation()).startsWith("s");
        String single = index.state("wiki").orElseThrow().generation();
        String scoped = index.state(Set.of("wiki")).orElseThrow().generation();
        assertThat(index.search(all, Set.of("wiki"), single, null, 1)).hasSize(1);
        assertThat(index.search(all, Set.of("wiki"), scoped, null, 1)).hasSize(1);
        assertThatThrownBy(() -> index.search(all, Set.of("other"), single, null, 1))
                .isInstanceOf(CacheGenerationChangedException.class);
    }

    @Test
    void aGlobalQueryOnlyReadsTheSourcesInItsScope() {
        corpus();
        Set<String> scope = Set.of("wiki", "nicht-indexiert");
        String generation = index.state(scope).orElseThrow().generation();

        assertThat(pageThrough(query(null, List.of(), "api", null, null, null, null, null, SearchSort.TITLE), scope,
                generation, 10)).allMatch(key -> key.startsWith("wiki/")).isNotEmpty();
        assertThatThrownBy(() -> index.search(query("other", List.of(), null, null, null, null, null, null,
                SearchSort.TITLE), scope, generation, null, 10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPositionFromBeforeTheSourceTiebreakStillContinuesASingleSourceSearch() {
        corpus();
        String generation = index.state("wiki").orElseThrow().generation();
        for (SearchSort sort : SearchSort.values()) {
            PageSearchQuery query = query("wiki", List.of(), null, null, null, null, null, null, sort);
            List<SearchHit> first = index.search(query, generation, null, 3);
            String position = first.get(2).position();
            String legacy = position.substring(0, position.lastIndexOf('.'));

            assertThat(index.search(query, generation, legacy, 100)).map(hit -> hit.page().pageId())
                    .containsExactlyElementsOf(index.search(query, generation, position, 100).stream()
                            .map(hit -> hit.page().pageId()).toList());
            PageSearchQuery global = query(null, List.of(), null, null, null, null, null, null, sort);
            assertThatThrownBy(() -> index.search(global, Set.of("wiki"), generation, legacy, 10))
                    .isInstanceOf(InvalidQueryException.class);
        }
    }

    private List<String> pageThrough(PageSearchQuery query, Set<String> scope, String generation, int limit) {
        List<String> keys = new ArrayList<>();
        String after = null;
        while (true) {
            List<SearchHit> batch = index.search(query, scope, generation, after, limit);
            batch.forEach(hit -> keys.add(key(hit.page())));
            if (batch.size() < limit) {
                return keys;
            }
            after = batch.get(batch.size() - 1).position();
        }
    }

    private static String key(ListedPage page) {
        return page.header().key().sourceId() + "/" + page.pageId();
    }

    private List<String> pageThrough(PageSearchQuery query, String generation, int limit) {
        List<String> pageIds = new ArrayList<>();
        String after = null;
        while (true) {
            List<SearchHit> batch = index.search(query, generation, after, limit);
            batch.forEach(hit -> pageIds.add(hit.page().pageId()));
            if (batch.size() < limit) {
                return pageIds;
            }
            after = batch.get(batch.size() - 1).position();
        }
    }

    private static PageSearchQuery query(String sourceId, List<String> spaceKeys, String title, String pageId,
                                         String parentPageId, String version, Instant modifiedFrom,
                                         Instant modifiedBefore, SearchSort sort) {
        return PageSearchQuery.of(sourceId, spaceKeys, title, pageId, parentPageId, version, modifiedFrom,
                modifiedBefore, sort);
    }

    private PageSnapshot page(String sourceId, String pageId, String spaceKey, String parentId, String title,
                              Instant modifiedAt, String version) {
        PageSnapshot snapshot = PageSnapshot.of(sourceId, metadata(pageId, spaceKey, parentId, title, modifiedAt,
                version), new NativePayload(json("{\"id\":\"" + pageId + "\"}"), "application/json",
                "confluence-content/v1"), T0);
        cache.save(snapshot);
        reference.add(ListedPage.of(snapshot.header(), snapshot));
        return snapshot;
    }

    private static PageMetadata metadata(String pageId, String spaceKey, String parentId, String title,
                                         Instant modifiedAt, String version) {
        return new PageMetadata(pageId, "confluence:wiki.example.org/page/" + pageId, title, spaceKey, parentId,
                version, modifiedAt, URI.create("https://wiki.example.org/pages/" + pageId),
                PageMetadata.sorted(Map.of()));
    }
}
