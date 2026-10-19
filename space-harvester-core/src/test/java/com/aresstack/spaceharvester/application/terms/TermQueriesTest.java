package com.aresstack.spaceharvester.application.terms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.enrichment.language.TermProcessor;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link TermQueries} über zwei Quellen mit gleichen Seiten-IDs und Spaces, gemischten Rechten und echten Ergebnissen
 * des Prozessors {@code terms}.
 */
class TermQueriesTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("etl");
    private static final Instant T0 = Instant.parse("2026-10-19T00:00:00Z");
    /** Text = Payload; so prüft der Test Positionen und Snippets gegen genau den indexierten Text. */
    private static final PageTextProjector PLAIN = snapshot -> Optional.of(new PageText("test/v1",
            new String(snapshot.payload().bytes(), StandardCharsets.UTF_8), List.of()));

    private final List<String> configured = List.of("wiki-a", "wiki-b");
    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return configured.stream().map(TermQueriesTest::source).toList();
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return configured.contains(sourceId) ? Optional.of(source(sourceId)) : Optional.empty();
        }
    };
    private final InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();
    private final Enrichments enrichments = new Enrichments(PLAIN, List.of(new TermProcessor()), store,
            Clock.fixed(T0, ZoneOffset.UTC));
    private final Map<PageKey, PageSnapshot> cache = new HashMap<>();
    private final Set<PageKey> hidden = new HashSet<>();
    private final AccessPolicy policy = (context, scope, kind) -> kind == AccessKind.CONTENT
            && scope.pageId() != null && hidden.contains(new PageKey(scope.sourceId(), scope.pageId()))
            ? AccessDecision.DENY : AccessDecision.ALLOW;
    private TermQueries terms;

    private static HarvestSource source(String id) {
        HarvestSource source = mock(HarvestSource.class);
        when(source.id()).thenReturn(id);
        return source;
    }

    private static PageSnapshot page(String sourceId, String space, String pageId, String version, String text) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:" + sourceId + "/page/" + pageId, "Seite " + pageId,
                space, null, version, T0, null, PageMetadata.sorted(Map.of()));
        return PageSnapshot.of(sourceId, metadata, new NativePayload(text.getBytes(StandardCharsets.UTF_8),
                "text/plain", "test"), T0);
    }

    private void cached(PageSnapshot snapshot) {
        cache.put(snapshot.key(), snapshot);
        enrichments.refresh(snapshot);
    }

    @BeforeEach
    void setUp() {
        terms = new TermQueries(catalog, store, new AccessGuard(policy),
                key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header),
                key -> Optional.ofNullable(cache.get(key)), PLAIN, new TermProcessor().version());
        cached(page("wiki-a", "DEV", "1", "1", "Der RSL (Rückmelde-Schlüssel-Liste) steuert den Beleg. Der RSL ist Pflicht."));
        cached(page("wiki-b", "DEV", "1", "1", "Der RSL wird im Beleg gesetzt."));
        cached(page("wiki-b", "OPS", "2", "1", "Ein Beleg ohne RSL ist ungültig."));
    }

    private TermQueries.TermList list(String sourceId, String spaceKey, TermKind kind, String q, int limit,
                                      String cursor) {
        return terms.terms(CALLER, new TermQueries.TermSearch(sourceId, spaceKey, kind, q, false, limit, cursor));
    }

    private TermQueries.TermSummary only(TermQueries.TermList list, String normalized, TermKind kind) {
        return list.terms().stream().filter(term -> term.normalized().equals(normalized) && term.kind() == kind)
                .findFirst().orElseThrow(() -> new AssertionError(normalized + " fehlt in " + list.terms()));
    }

    @Test
    void aggregatesAcronymsOverAllSourcesWithEveryExpansion() {
        TermQueries.TermSummary rsl = only(list(null, null, TermKind.ACRONYM, null, 50, null), "rsl",
                TermKind.ACRONYM);

        assertThat(rsl.occurrences()).isEqualTo(4);
        assertThat(rsl.pages()).isEqualTo(3);
        assertThat(rsl.sources()).isEqualTo(2);
        assertThat(rsl.expansions()).containsExactly("Rückmelde-Schlüssel-Liste");
    }

    @Test
    void nounsAreListedSeparatelyFromAcronyms() {
        TermQueries.TermList nouns = list(null, null, TermKind.NOUN, null, 50, null);

        assertThat(nouns.terms()).extracting(TermQueries.TermSummary::kind).containsOnly(TermKind.NOUN);
        assertThat(only(nouns, "beleg", TermKind.NOUN).pages()).isEqualTo(3);
    }

    @Test
    void everyOccurrenceIsReachableWithItsRealPosition() {
        TermQueries.OccurrenceList found = terms.occurrences(CALLER, new TermQueries.OccurrenceSearch("RSL",
                TermKind.ACRONYM, null, null, 50, null, 0));

        assertThat(found.occurrences()).extracting(o -> o.sourceId() + "/" + o.pageId() + "@" + o.start())
                .containsExactly("wiki-a/1@4", "wiki-a/1@59", "wiki-b/1@4", "wiki-b/2@15");
        for (TermQueries.Occurrence occurrence : found.occurrences()) {
            String text = new String(cache.get(new PageKey(occurrence.sourceId(), occurrence.pageId())).payload()
                    .bytes(), StandardCharsets.UTF_8);
            assertThat(text.substring(occurrence.start(), occurrence.end())).isEqualTo("RSL");
        }
    }

    @Test
    void hiddenPagesContributeNothingNotEvenToCounters() {
        hidden.add(new PageKey("wiki-a", "1"));

        TermQueries.TermList acronyms = list(null, null, TermKind.ACRONYM, null, 50, null);
        TermQueries.TermSummary rsl = only(acronyms, "rsl", TermKind.ACRONYM);

        assertThat(rsl.occurrences()).isEqualTo(2);
        assertThat(rsl.expansions()).isEmpty();
        assertThat(terms.occurrences(CALLER, new TermQueries.OccurrenceSearch("rsl", null, null, null, 50, null, 40))
                .occurrences()).extracting(TermQueries.Occurrence::sourceId).containsOnly("wiki-b");
        assertThat(list(null, null, null, "rückmelde", 50, null).terms()).isEmpty();
        assertThat(terms.snippet(CALLER, "wiki-a", "1", "x", 4, 7, 20)).isEmpty();
    }

    @Test
    void filtersBySourceAndSpace() {
        assertThat(only(list("wiki-b", null, TermKind.ACRONYM, null, 50, null), "rsl", TermKind.ACRONYM).pages())
                .isEqualTo(2);
        assertThat(only(list(null, "OPS", TermKind.ACRONYM, null, 50, null), "rsl", TermKind.ACRONYM).pages())
                .isEqualTo(1);
    }

    @Test
    void pagingThroughTheListYieldsEveryTermOnce() {
        List<String> all = list(null, null, null, null, 200, null).terms().stream()
                .map(term -> term.normalized() + "/" + term.kind()).toList();
        List<String> paged = new ArrayList<>();
        String cursor = null;
        do {
            TermQueries.TermList batch = list(null, null, null, null, 2, cursor);
            assertThat(batch.terms()).hasSizeLessThanOrEqualTo(2);
            batch.terms().forEach(term -> paged.add(term.normalized() + "/" + term.kind()));
            cursor = batch.nextCursor();
        } while (cursor != null);

        assertThat(all).hasSizeGreaterThan(3);
        assertThat(paged).isEqualTo(all);
    }

    @Test
    void pagingThroughOccurrencesYieldsEveryOccurrenceOnce() {
        List<String> paged = new ArrayList<>();
        String cursor = null;
        do {
            TermQueries.OccurrenceList batch = terms.occurrences(CALLER, new TermQueries.OccurrenceSearch("rsl",
                    TermKind.ACRONYM, null, null, 1, cursor, 0));
            batch.occurrences().forEach(o -> paged.add(o.sourceId() + "/" + o.pageId() + "@" + o.start()));
            cursor = batch.nextCursor();
        } while (cursor != null);

        assertThat(paged).containsExactly("wiki-a/1@4", "wiki-a/1@59", "wiki-b/1@4", "wiki-b/2@15");
    }

    @Test
    void snippetsComeFromTheCachedTextOnlyWhileItIsTheSame() {
        TermQueries.Occurrence first = terms.occurrences(CALLER, new TermQueries.OccurrenceSearch("rsl", null,
                "wiki-b", "OPS", 10, null, 8)).occurrences().get(0);

        assertThat(first.snippet().match()).isEqualTo("RSL");
        assertThat(first.snippet().before()).isEqualTo("ohne ");
        assertThat(first.snippet().after()).isEqualTo(" ist ung");
        assertThat(terms.snippet(CALLER, "wiki-b", "2", first.inputSha256(), first.start(), first.end(), 5))
                .hasValueSatisfying(snippet -> assertThat(snippet.match()).isEqualTo("RSL"));
        assertThat(terms.snippet(CALLER, "wiki-b", "2", "sha256:alt", first.start(), first.end(), 5)).isEmpty();
    }

    @Test
    void termsOfAnOlderRevisionDoNotCountUntilTheyAreRecomputed() {
        // Neue Revision im Cache, Anreicherung noch nicht nachgezogen.
        cache.put(new PageKey("wiki-b", "2"), page("wiki-b", "OPS", "2", "2", "Ein Antrag ohne Kennzeichen."));

        assertThat(only(list(null, null, TermKind.ACRONYM, null, 50, null), "rsl", TermKind.ACRONYM).pages())
                .isEqualTo(2);

        enrichments.refresh(cache.get(new PageKey("wiki-b", "2")));

        assertThat(only(list(null, null, TermKind.NOUN, null, 50, null), "antrag", TermKind.NOUN).pages())
                .isEqualTo(1);
    }

    @Test
    void pagesThatLeftTheCacheVanish() {
        cache.remove(new PageKey("wiki-a", "1"));

        assertThat(only(list(null, null, TermKind.ACRONYM, null, 50, null), "rsl", TermKind.ACRONYM).sources())
                .isEqualTo(1);
    }

    @Test
    void rejectsUnknownSourcesForeignCursorsAndBadLimits() {
        assertThatThrownBy(() -> list("wiki-x", null, null, null, 10, null))
                .isInstanceOf(UnknownSourceException.class);
        assertThatThrownBy(() -> list(null, null, null, null, 0, null)).isInstanceOf(InvalidQueryException.class);
        String cursor = list(null, null, null, null, 1, null).nextCursor();
        assertThatThrownBy(() -> list(null, null, TermKind.NOUN, null, 1, cursor))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> terms.occurrences(CALLER, new TermQueries.OccurrenceSearch(" ", null, null, null,
                10, null, 0))).isInstanceOf(InvalidQueryException.class);
    }

    @Test
    void anExplicitSourceWithoutMetadataIsDeniedAndGloballyDropped() {
        AccessPolicy noB = (context, scope, kind) -> "wiki-b".equals(scope.sourceId()) ? AccessDecision.DENY
                : AccessDecision.ALLOW;
        TermQueries limited = new TermQueries(catalog, store, new AccessGuard(noB),
                key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header),
                key -> Optional.ofNullable(cache.get(key)), PLAIN, new TermProcessor().version());

        assertThatThrownBy(() -> limited.terms(CALLER, new TermQueries.TermSearch("wiki-b", null, null, null, false,
                10, null))).isInstanceOf(AccessDeniedException.class);
        TermQueries.TermList global = limited.terms(CALLER, new TermQueries.TermSearch(null, null, TermKind.ACRONYM,
                null, false, 10, null));
        assertThat(only(global, "rsl", TermKind.ACRONYM).sources()).isEqualTo(1);
    }

    @Test
    void withoutRevisionOnlyTermsOfTheCurrentTextCount() {
        cached(page("wiki-a", "DEV", "9", null, "Die ZKV regelt alles."));
        assertThat(list("wiki-a", null, TermKind.ACRONYM, "zkv", 50, null).terms()).hasSize(1);

        // Neuer Inhalt im Cache, Anreicherung noch nicht gelaufen: die alten Begriffe zählen nicht mehr.
        PageSnapshot changed = page("wiki-a", "DEV", "9", null, "Ohne Kürzel.");
        cache.put(changed.key(), changed);

        assertThat(list("wiki-a", null, TermKind.ACRONYM, "zkv", 50, null).terms()).isEmpty();
    }

    @Test
    void cursorsDoNotRevealTermKeys() {
        TermQueries.TermList first = list(null, null, TermKind.NOUN, null, 1, null);
        String cursor = first.nextCursor();

        assertThat(cursor).isNotNull();
        assertThat(new String(java.util.Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8))
                .doesNotContain(first.terms().get(0).normalized(), "noun");
    }
}
