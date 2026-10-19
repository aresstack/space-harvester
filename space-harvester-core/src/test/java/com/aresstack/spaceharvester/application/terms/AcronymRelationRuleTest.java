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
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link AcronymRelationRule}: Ausschreibungen aus dem Begriffsindex werden Vorschläge, Ablehnungen halten. */
class AcronymRelationRuleTest {

    private static final AccessContext EDITOR = AccessContext.technicalClient("editor");
    private static final AccessContext READER = AccessContext.technicalClient("reader");
    private static final Instant T0 = Instant.parse("2026-10-19T00:00:00Z");
    private static final PageTextProjector PLAIN = snapshot -> Optional.of(new PageText("test/v1",
            new String(snapshot.payload().bytes(), StandardCharsets.UTF_8), List.of()));

    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of(source("wiki"));
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return "wiki".equals(sourceId) ? Optional.of(source(sourceId)) : Optional.empty();
        }
    };
    private final InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();
    private final Enrichments enrichments = new Enrichments(PLAIN, List.of(new TermProcessor()), store,
            Clock.fixed(T0, ZoneOffset.UTC));
    private final Map<PageKey, PageSnapshot> cache = new HashMap<>();
    private final AccessPolicy policy = (context, scope, kind) -> kind == AccessKind.SYNC && context.equals(READER)
            ? AccessDecision.DENY : AccessDecision.ALLOW;
    private TermRelations relations;
    private AcronymRelationRule rule;

    private static HarvestSource source(String id) {
        HarvestSource source = mock(HarvestSource.class);
        when(source.id()).thenReturn(id);
        return source;
    }

    private void cached(String pageId, String version, String text) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:wiki/page/" + pageId, "Seite " + pageId, "DEV",
                null, version, T0, null, PageMetadata.sorted(Map.of()));
        PageSnapshot snapshot = PageSnapshot.of("wiki", metadata, new NativePayload(
                text.getBytes(StandardCharsets.UTF_8), "text/plain", "test"), T0);
        cache.put(snapshot.key(), snapshot);
        enrichments.refresh(snapshot);
    }

    @BeforeEach
    void setUp() {
        AccessGuard guard = new AccessGuard(policy);
        relations = new TermRelations(catalog, new InMemoryTermRelationStore(), guard,
                key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header), Clock.fixed(T0, ZoneOffset.UTC));
        rule = new AcronymRelationRule(catalog, store, guard,
                key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header), relations,
                new TermProcessor().version(), Clock.fixed(T0, ZoneOffset.UTC));
        cached("1", "1", "Die Application Programming Interface (API) liefert Daten.");
        cached("2", "1", "Jede API (Application Programming Interface) hat Grenzen. Der RSL ist Pflicht.");
    }

    @Test
    void expansionsBecomeProposalsWithEvidencePerPage() {
        TermRelations.ProposalRun run = rule.run(EDITOR, "wiki");

        assertThat(run.added()).isEqualTo(1);
        TermRelation api = relations.relations(EDITOR, new TermRelations.RelationSearch("api", null, null, null,
                null, 50, null)).relations().get(0);
        assertThat(api.fromTerm()).isEqualTo("API");
        assertThat(api.toTerm()).isEqualTo("Application Programming Interface");
        assertThat(api.type()).isEqualTo(RelationType.ABBREVIATION);
        assertThat(api.origin()).isEqualTo(RelationOrigin.RULE);
        assertThat(api.status()).isEqualTo(ReviewStatus.PROPOSED);
        assertThat(api.scope()).isEqualTo(new RelationScope("wiki", null));
        assertThat(api.createdBy()).isEqualTo(AcronymRelationRule.ACTOR);
        assertThat(api.evidence()).extracting(RelationEvidence::pageId).containsExactlyInAnyOrder("1", "2");
        for (RelationEvidence evidence : api.evidence()) {
            String text = new String(cache.get(new PageKey("wiki", evidence.pageId())).payload().bytes(),
                    StandardCharsets.UTF_8);
            assertThat(text.substring(evidence.start(), evidence.end())).isEqualTo("API");
        }
    }

    @Test
    void rejectionSurvivesTheNextRunAndConfirmedOnesStay() {
        rule.run(EDITOR, "wiki");
        TermRelation api = relations.relations(EDITOR, new TermRelations.RelationSearch("api", null, null, null,
                null, 50, null)).relations().get(0);
        relations.reject(EDITOR, api.id(), null);

        TermRelations.ProposalRun again = rule.run(EDITOR, "wiki");

        assertThat(again.suppressed()).isEqualTo(1);
        assertThat(relations.relation(EDITOR, api.id()).status()).isEqualTo(ReviewStatus.REJECTED);
    }

    @Test
    void outdatedPagesDoNotCount() {
        PageMetadata metadata = new PageMetadata("1", "confluence:wiki/page/1", "Seite 1", "DEV", null, "2", T0, null,
                PageMetadata.sorted(Map.of()));
        cache.put(new PageKey("wiki", "1"), PageSnapshot.of("wiki", metadata, new NativePayload(
                "Neu".getBytes(StandardCharsets.UTF_8), "text/plain", "test"), T0));

        rule.run(EDITOR, "wiki");

        assertThat(relations.relations(EDITOR, new TermRelations.RelationSearch("api", null, null, null, null, 50,
                null)).relations().get(0).evidence()).extracting(RelationEvidence::pageId).containsExactly("2");
    }

    @Test
    void needsSyncAndAKnownSource() {
        assertThatThrownBy(() -> rule.run(READER, "wiki")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> rule.run(EDITOR, "unbekannt")).isInstanceOf(UnknownSourceException.class);
    }
}
