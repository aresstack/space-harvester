package com.aresstack.spaceharvester.application.structure;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemorySearchIndex.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.enrichment.language.TermProcessor;
import com.aresstack.spaceharvester.application.query.CacheMissException;
import com.aresstack.spaceharvester.application.query.InMemorySearchIndex;
import com.aresstack.spaceharvester.application.query.SearchIndexState;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.terms.InMemoryTermRelationStore;
import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.RelationScope;
import com.aresstack.spaceharvester.application.terms.RelationType;
import com.aresstack.spaceharvester.application.terms.TermQueries;
import com.aresstack.spaceharvester.application.terms.TermRelations;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link PageConnections}: Kantenarten mit Grundlage und Herkunft, Vollständigkeit, Grenzen und Rechte (auch: eine
 * gesperrte Seite hinterlässt keine Spur, auch nicht in Zählern).
 */
class PageConnectionsTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("etl");
    private static final Instant T0 = Instant.parse("2026-11-21T03:00:00Z");
    private static final SearchIndexState STATE = new SearchIndexState("i1", T0);
    private static final PageTextProjector PLAIN = snapshot -> Optional.of(new PageText("test/v1",
            new String(snapshot.payload().bytes(), StandardCharsets.UTF_8), List.of()));

    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of(source("wiki"));
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return "wiki".equals(sourceId) ? Optional.of(source("wiki")) : Optional.empty();
        }
    };
    private final InMemorySearchIndex index = new InMemorySearchIndex();
    private final InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();
    private final TermProcessor processor = new TermProcessor();
    private final Enrichments enrichments = new Enrichments(PLAIN, List.of(processor), store,
            Clock.fixed(T0, ZoneOffset.UTC));
    private final Map<PageKey, PageSnapshot> cache = new HashMap<>();
    private final List<PageSnapshot> pages = new ArrayList<>();
    private final Set<String> hidden = new HashSet<>();
    private final AccessPolicy policy = (context, scope, kind) -> scope.pageId() != null
            && hidden.contains(scope.pageId()) ? AccessDecision.DENY : AccessDecision.ALLOW;
    private final AccessGuard guard = new AccessGuard(policy);
    private final TermRelations relations = new TermRelations(catalog, new InMemoryTermRelationStore(), guard,
            key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header), Clock.fixed(T0, ZoneOffset.UTC),
            store, processor.version());
    private final TermQueries terms = new TermQueries(catalog, store, guard,
            key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header),
            key -> Optional.ofNullable(cache.get(key)), PLAIN, processor.version());

    private static HarvestSource source(String id) {
        HarvestSource source = mock(HarvestSource.class);
        when(source.id()).thenReturn(id);
        return source;
    }

    /** Handbuch mit Unterseiten; Betrieb nennt den RSL, Buchung und Geheim schreiben ihn aus. */
    private void standard(String... skipped) {
        Set<String> skip = Set.of(skipped);
        String[][] all = {
                {"0", "Handbuch", null, "Willkommen."},
                {"1", "Betrieb", "0", "Der RSL steht im Beleg."},
                {"2", "Buchung", "0", "Der Rückmeldeschlüssel kommt mit der Buchung."},
                {"3", "Geheim", "0", "Der Rückmeldeschlüssel ist geheim."},
                {"4", "Nachtlauf", "1", "Nachts."},
                {"5", "Abschluss", "1", "Abends."}};
        for (String[] row : all) {
            if (!skip.contains(row[0])) {
                page(row[0], row[1], row[2], row[3]);
            }
        }
    }

    private void page(String pageId, String title, String parent, String text) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:wiki/page/" + pageId, title, "DEV", parent,
                "1", T0, null, PageMetadata.sorted(Map.of()));
        PageSnapshot snapshot = PageSnapshot.of("wiki", metadata, new NativePayload(
                text.getBytes(StandardCharsets.UTF_8), "text/plain", "test"), T0);
        cache.put(snapshot.key(), snapshot);
        pages.add(snapshot);
        enrichments.refresh(snapshot);
    }

    private PageConnections connections(LinkDirectory links) {
        index.publish("wiki", STATE, pages.stream().map(page -> entry(page, confirmed("g1"))).toList());
        return new PageConnections(catalog, index, guard, links, terms, relations);
    }

    private static PageKey key(String pageId) {
        return new PageKey("wiki", pageId);
    }

    @Test
    void hierarchyIsExplicitWithParentAndChildrenFromTheLocalCatalog() {
        standard();

        PageConnections.Connections result = connections(LinkDirectory.NONE).connections(CALLER, "wiki", "1",
                PageConnections.Query.all().withKinds(List.of(ConnectionKind.PARENT, ConnectionKind.CHILD)));

        assertThat(result.seed().title()).isEqualTo("Betrieb");
        assertThat(result.edges()).extracting(Connection::kind, edge -> edge.target().key().pageId(),
                        Connection::basis, Connection::from)
                .containsExactly(
                        tuple(ConnectionKind.PARENT, "0", Basis.EXPLICIT, key("1")),
                        tuple(ConnectionKind.CHILD, "5", Basis.EXPLICIT, key("1")),
                        tuple(ConnectionKind.CHILD, "4", Basis.EXPLICIT, key("1")));
        assertThat(result.edges()).allSatisfy(edge ->
                assertThat(edge.provenance()).isInstanceOf(Provenance.Hierarchy.class));
        assertThat(result.coverage()).containsEntry(ConnectionKind.PARENT, Coverage.COMPLETE)
                .containsEntry(ConnectionKind.CHILD, Coverage.COMPLETE).hasSize(2);
        assertThat(result.complete()).isTrue();
        assertThat(result.truncated()).isEmpty();
        assertThat(result.pagesConsidered()).isEqualTo(4);
    }

    @Test
    void aParentThatIsNotLocalMakesTheParentPartialWithoutAnEdge() {
        page("7", "Waise", "77", "Allein.");

        PageConnections.Connections result = connections(LinkDirectory.NONE).connections(CALLER, "wiki", "7",
                PageConnections.Query.all().withKinds(List.of(ConnectionKind.PARENT)));

        assertThat(result.edges()).isEmpty();
        assertThat(result.coverage()).containsEntry(ConnectionKind.PARENT, Coverage.PARTIAL);
        assertThat(result.complete()).isFalse();
    }

    @Test
    void confirmedTermRelationsLeadToThePagesOfTheCounterpartWithTypeScopeAndOrigin() {
        standard();
        relations.add(CALLER, new TermRelations.NewRelation("RSL", "Rückmeldeschlüssel",
                RelationType.ABBREVIATION, RelationScope.GLOBAL, null, List.of()));

        PageConnections.Connections result = connections(LinkDirectory.NONE).connections(CALLER, "wiki", "1",
                PageConnections.Query.all().withKinds(List.of(ConnectionKind.TERM_RELATION)));

        assertThat(result.edges()).extracting(edge -> edge.target().key().pageId()).containsExactly("2", "3");
        Connection first = result.edges().getFirst();
        assertThat(first.basis()).isEqualTo(Basis.CONFIRMED);
        assertThat(first.provenance()).isInstanceOfSatisfying(Provenance.TermLink.class, link -> {
            assertThat(link.type()).isEqualTo(RelationType.ABBREVIATION);
            assertThat(link.origin()).isEqualTo(RelationOrigin.MANUAL);
            assertThat(link.fromTerm()).isEqualTo("RSL");
            assertThat(link.toTerm()).isEqualTo("Rückmeldeschlüssel");
            assertThat(link.scopeSourceId()).isNull();
            assertThat(link.seedTerm()).isEqualTo("rsl");
        });
        assertThat(result.coverage()).containsEntry(ConnectionKind.TERM_RELATION, Coverage.COMPLETE);
    }

    @Test
    void relatedIsShownAsItsOwnRelationTypeAndRejectedRelationsAreNot() {
        standard();
        relations.add(CALLER, new TermRelations.NewRelation("Beleg", "Buchung", RelationType.RELATED,
                RelationScope.GLOBAL, null, List.of()));
        var rejected = relations.add(CALLER, new TermRelations.NewRelation("RSL", "Rückmeldeschlüssel",
                RelationType.ABBREVIATION, RelationScope.GLOBAL, null, List.of()));
        relations.reject(CALLER, rejected.id(), null);

        PageConnections.Connections result = connections(LinkDirectory.NONE).connections(CALLER, "wiki", "1",
                PageConnections.Query.all().withKinds(List.of(ConnectionKind.TERM_RELATION)));

        assertThat(result.edges()).extracting(edge -> edge.target().key().pageId(),
                        edge -> ((Provenance.TermLink) edge.provenance()).type())
                .containsExactly(tuple("2", RelationType.RELATED));
    }

    @Test
    void withoutTermsOfThePageTermRelationsAreUnavailableNotEmpty() {
        standard();
        connections(LinkDirectory.NONE);

        PageConnections.Connections none = new PageConnections(catalog, index, guard, LinkDirectory.NONE, null,
                null).connections(CALLER, "wiki", "1", PageConnections.Query.all());

        assertThat(none.coverage()).containsEntry(ConnectionKind.TERM_RELATION, Coverage.UNAVAILABLE)
                .containsEntry(ConnectionKind.LINKS_TO, Coverage.UNAVAILABLE)
                .containsEntry(ConnectionKind.LINKED_FROM, Coverage.UNAVAILABLE);
        assertThat(none.of(ConnectionKind.TERM_RELATION)).isEmpty();
    }

    @Test
    void linksAreExplicitAndATargetThatIsNotLocalMakesThemPartial() {
        standard();
        LinkDirectory links = (caller, page) -> Optional.of(new LinkDirectory.Links(
                List.of(key("2"), key("99"), key("1")), List.of(key("0")), true));

        PageConnections.Connections result = connections(links).connections(CALLER, "wiki", "1",
                PageConnections.Query.all().withKinds(List.of(ConnectionKind.LINKS_TO, ConnectionKind.LINKED_FROM)));

        assertThat(result.edges()).extracting(Connection::kind, edge -> edge.target().key().pageId())
                .containsExactly(tuple(ConnectionKind.LINKS_TO, "2"), tuple(ConnectionKind.LINKED_FROM, "0"));
        assertThat(result.edges()).allSatisfy(edge -> {
            assertThat(edge.basis()).isEqualTo(Basis.EXPLICIT);
            assertThat(edge.provenance()).isInstanceOf(Provenance.Link.class);
        });
        assertThat(result.coverage()).containsEntry(ConnectionKind.LINKS_TO, Coverage.PARTIAL)
                .containsEntry(ConnectionKind.LINKED_FROM, Coverage.COMPLETE);
    }

    @Test
    void aHiddenPageLeavesNoTraceInEdgesCountersOrFlags() {
        standard();
        relations.add(CALLER, new TermRelations.NewRelation("RSL", "Rückmeldeschlüssel",
                RelationType.ABBREVIATION, RelationScope.GLOBAL, null, List.of()));
        LinkDirectory links = (caller, page) -> Optional.of(new LinkDirectory.Links(List.of(key("2")),
                List.of(), true));
        hidden.add("3");
        PageConnections.Query query = PageConnections.Query.all();

        PageConnections.Connections withHidden = connections(links).connections(CALLER, "wiki", "1", query);

        PageConnectionsTest without = new PageConnectionsTest();
        without.standard("3");
        without.relations.add(CALLER, new TermRelations.NewRelation("RSL", "Rückmeldeschlüssel",
                RelationType.ABBREVIATION, RelationScope.GLOBAL, null, List.of()));
        PageConnections.Connections absent = without.connections(links).connections(CALLER, "wiki", "1", query);

        assertThat(withHidden.edges()).isEqualTo(absent.edges());
        assertThat(withHidden.pagesConsidered()).isEqualTo(absent.pagesConsidered());
        assertThat(withHidden.truncated()).isEqualTo(absent.truncated());
        assertThat(withHidden.coverage()).isEqualTo(absent.coverage());
        assertThat(withHidden.edges()).noneMatch(edge -> edge.target().key().pageId().equals("3"));
    }

    @Test
    void limitsCutPerKindAndOverallAndSayWhichKindsWereCut() {
        standard();

        PageConnections connections = connections(LinkDirectory.NONE);
        PageConnections.Connections perKind = connections.connections(CALLER, "wiki", "1",
                new PageConnections.Query(EnumSet.of(ConnectionKind.PARENT, ConnectionKind.CHILD), 1, 10));
        PageConnections.Connections overall = connections.connections(CALLER, "wiki", "1",
                new PageConnections.Query(EnumSet.of(ConnectionKind.PARENT, ConnectionKind.CHILD), 10, 2));

        assertThat(perKind.edges()).extracting(edge -> edge.target().key().pageId()).containsExactly("0", "5");
        assertThat(perKind.truncated()).containsExactly(ConnectionKind.CHILD);
        assertThat(overall.edges()).hasSize(2);
        assertThat(overall.truncated()).containsExactly(ConnectionKind.CHILD);
        assertThatThrownBy(() -> new PageConnections.Query(Set.of(), 0, 10))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> new PageConnections.Query(Set.of(), 1, 501))
                .isInstanceOf(InvalidQueryException.class);
    }

    @Test
    void aMissingOrHiddenSeedIsNotInTheCache() {
        standard();
        hidden.add("3");
        PageConnections connections = connections(LinkDirectory.NONE);

        assertThatThrownBy(() -> connections.connections(CALLER, "wiki", "42", PageConnections.Query.all()))
                .isInstanceOf(CacheMissException.class);
        assertThatThrownBy(() -> connections.connections(CALLER, "wiki", "3", PageConnections.Query.all()))
                .isInstanceOf(CacheMissException.class);
    }

    @Test
    void anEdgeNeverCarriesAProvenanceOfAnotherBasis() {
        PageRef target = new PageRef(key("2"), "Buchung", "DEV");

        assertThatThrownBy(() -> new Connection(key("1"), ConnectionKind.LINKS_TO, target,
                new Provenance.TermLink("r1", RelationType.SYNONYM, "a", "b", null, null, RelationOrigin.AI, "a",
                        "b")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Connection(key("1"), ConnectionKind.TERM_RELATION, target,
                new Provenance.Link()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
