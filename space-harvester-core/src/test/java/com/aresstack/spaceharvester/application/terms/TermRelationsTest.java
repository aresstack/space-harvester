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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link TermRelations}: Pflege, Sperrliste, Vorschlagsläufe und Rechte über zwei Quellen. */
class TermRelationsTest {

    private static final AccessContext EDITOR = AccessContext.technicalClient("editor");
    private static final AccessContext READER = AccessContext.technicalClient("reader");
    private static final Instant T0 = Instant.parse("2026-10-19T00:00:00Z");

    private final List<String> configured = List.of("wiki-a", "wiki-b");
    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return configured.stream().map(TermRelationsTest::source).toList();
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return configured.contains(sourceId) ? Optional.of(source(sourceId)) : Optional.empty();
        }
    };
    private final Map<PageKey, PageSnapshot> cache = new HashMap<>();
    private final Set<PageKey> hidden = new HashSet<>();
    private final Set<String> metadataDenied = new HashSet<>();
    private final AccessPolicy policy = (context, scope, kind) -> {
        if (kind == AccessKind.SYNC && context.equals(READER)) {
            return AccessDecision.DENY;
        }
        if (kind == AccessKind.METADATA && metadataDenied.contains(scope.sourceId())) {
            return AccessDecision.DENY;
        }
        if (kind == AccessKind.CONTENT && scope.pageId() != null
                && hidden.contains(new PageKey(scope.sourceId(), scope.pageId()))) {
            return AccessDecision.DENY;
        }
        return AccessDecision.ALLOW;
    };
    private final InMemoryTermRelationStore store = new InMemoryTermRelationStore();
    private TermRelations relations;

    private static HarvestSource source(String id) {
        HarvestSource source = mock(HarvestSource.class);
        when(source.id()).thenReturn(id);
        return source;
    }

    private void cached(String sourceId, String pageId) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:" + sourceId + "/page/" + pageId,
                "Seite " + pageId, "DEV", null, "1", T0, null, PageMetadata.sorted(Map.of()));
        PageSnapshot snapshot = PageSnapshot.of(sourceId, metadata, new NativePayload(
                "text".getBytes(StandardCharsets.UTF_8), "text/plain", "test"), T0);
        cache.put(snapshot.key(), snapshot);
    }

    private static RelationEvidence evidence(String sourceId, String pageId) {
        return new RelationEvidence(sourceId, pageId, 4, 7, "sha256:x");
    }

    private static TermRelations.Proposal proposal(String from, String to, RelationEvidence... evidence) {
        return new TermRelations.Proposal(from, to, RelationType.ABBREVIATION, List.of(evidence));
    }

    private List<TermRelation> all(AccessContext caller, ReviewStatus status) {
        return relations.relations(caller, new TermRelations.RelationSearch(null, null, status, null, null, 200,
                null)).relations();
    }

    @BeforeEach
    void setUp() {
        relations = new TermRelations(catalog, store, new AccessGuard(policy),
                key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header), Clock.fixed(T0, ZoneOffset.UTC));
        cached("wiki-a", "1");
        cached("wiki-b", "1");
    }

    @Test
    void manualRelationIsConfirmedAndFoundFromBothEnds() {
        TermRelation created = relations.add(EDITOR, new TermRelations.NewRelation("RSL", "Rückmeldeschlüssel",
                RelationType.ABBREVIATION, RelationScope.GLOBAL, " Fachbereich ", List.of()));

        assertThat(created.origin()).isEqualTo(RelationOrigin.MANUAL);
        assertThat(created.status()).isEqualTo(ReviewStatus.CONFIRMED);
        assertThat(created.note()).isEqualTo("Fachbereich");
        assertThat(created.createdBy()).isEqualTo(EDITOR.jobOwner());
        for (String term : List.of("rsl", "Rückmeldeschlüssel")) {
            assertThat(relations.relations(READER, new TermRelations.RelationSearch(term, null, null, null, null,
                    50, null)).relations()).extracting(TermRelation::id).containsExactly(created.id());
        }
        assertThat(relations.expansions(READER, "RSL", "wiki-a")).containsExactly("Rückmeldeschlüssel");
        assertThat(relations.expansions(READER, "rückmeldeschlüssel", "wiki-a")).containsExactly("RSL");
        assertThat(relations.history(READER, created.id())).extracting(TermRelationStore.AuditEntry::action)
                .containsExactly("create");
        // Gleicher Schlüssel noch einmal: idempotent, keine zweite Beziehung.
        assertThat(relations.add(EDITOR, new TermRelations.NewRelation("rsl", "rückmeldeschlüssel",
                RelationType.ABBREVIATION, null, null, null))).isEqualTo(created);
        assertThat(store.all()).hasSize(1);
    }

    @Test
    void undirectedRelationsHaveOneKeyForBothDirections() {
        TermRelation one = relations.add(EDITOR, new TermRelations.NewRelation("Beleg", "Buchungsbeleg",
                RelationType.SYNONYM, RelationScope.GLOBAL, null, List.of()));
        TermRelation other = relations.add(EDITOR, new TermRelations.NewRelation("Buchungsbeleg", "Beleg",
                RelationType.SYNONYM, RelationScope.GLOBAL, null, List.of()));
        TermRelation abbreviation = relations.add(EDITOR, new TermRelations.NewRelation("Buchungsbeleg", "Beleg",
                RelationType.ABBREVIATION, RelationScope.GLOBAL, null, List.of()));

        assertThat(other.id()).isEqualTo(one.id());
        assertThat(abbreviation.id()).isNotEqualTo(one.id());
        assertThat(relations.expansions(EDITOR, "Beleg", null)).containsExactly("Buchungsbeleg");
    }

    @Test
    void relatedTermsNeverExpandSearch() {
        relations.add(EDITOR, new TermRelations.NewRelation("Beleg", "Kasse", RelationType.RELATED,
                RelationScope.GLOBAL, null, List.of()));

        assertThat(relations.expansions(EDITOR, "Beleg", null)).isEmpty();
        assertThat(relations.forTerms(EDITOR, List.of("beleg"), "wiki-a", "DEV")).hasSize(1);
    }

    @Test
    void rejectedProposalStaysBlockedAcrossRuns() {
        RelationScope scope = RelationScope.GLOBAL;
        TermRelations.ProposalRun first = relations.propose("rule:acronyms", RelationOrigin.RULE, scope, "g1",
                List.of(proposal("RSL", "Rückmeldeschlüssel", evidence("wiki-a", "1"))));
        TermRelation proposed = all(EDITOR, ReviewStatus.PROPOSED).get(0);
        relations.reject(EDITOR, proposed.id(), proposed.revision());

        TermRelations.ProposalRun second = relations.propose("rule:acronyms", RelationOrigin.RULE, scope, "g2",
                List.of(proposal("RSL", "Rückmeldeschlüssel", evidence("wiki-a", "1"))));

        assertThat(first.added()).isEqualTo(1);
        assertThat(second.suppressed()).isEqualTo(1);
        assertThat(all(EDITOR, null)).isEmpty();
        assertThat(all(EDITOR, ReviewStatus.REJECTED)).extracting(TermRelation::status)
                .containsExactly(ReviewStatus.REJECTED);
        // Wiederherstellen macht aus der Sperre wieder einen Vorschlag, die Herkunft bleibt.
        TermRelation restored = relations.restore(EDITOR, proposed.id(), null);
        assertThat(restored.status()).isEqualTo(ReviewStatus.PROPOSED);
        assertThat(restored.origin()).isEqualTo(RelationOrigin.RULE);
        assertThat(relations.history(EDITOR, proposed.id())).extracting(TermRelationStore.AuditEntry::action)
                .containsExactly("propose", "reject", "restore");
    }

    @Test
    void runReplacesOnlyItsOwnUnconfirmedProposals() {
        relations.propose("rule", RelationOrigin.RULE, RelationScope.GLOBAL, "g1", List.of(
                proposal("RSL", "Rückmeldeschlüssel", evidence("wiki-a", "1")),
                proposal("KPI", "Kennzahl", evidence("wiki-a", "1"))));
        TermRelation kpi = all(EDITOR, null).stream().filter(r -> r.fromTerm().equals("KPI")).findFirst()
                .orElseThrow();
        relations.confirm(EDITOR, kpi.id(), null);
        relations.propose("ai", RelationOrigin.AI, RelationScope.GLOBAL, "a1",
                List.of(proposal("ABC", "Alpha Beta Gamma", evidence("wiki-a", "1"))));
        TermRelation manual = relations.add(EDITOR, new TermRelations.NewRelation("ZV", "Zahlungsverkehr",
                RelationType.ABBREVIATION, RelationScope.GLOBAL, null, List.of()));

        TermRelations.ProposalRun run = relations.propose("rule", RelationOrigin.RULE, RelationScope.GLOBAL, "g2",
                List.of(proposal("ZV", "Zahlungsverkehr", evidence("wiki-a", "1")),
                        proposal("NEU", "Neuer Eintrag Uhr", evidence("wiki-b", "1"))));

        assertThat(run).isEqualTo(new TermRelations.ProposalRun(1, 0, 1, 1, 1));
        assertThat(all(EDITOR, null)).extracting(TermRelation::fromTerm)
                .containsExactlyInAnyOrder("KPI", "ABC", "ZV", "NEU");
        TermRelation confirmed = relations.relation(EDITOR, kpi.id());
        assertThat(confirmed.status()).isEqualTo(ReviewStatus.CONFIRMED);
        assertThat(confirmed.evidenceStale()).isTrue();
        assertThat(relations.relation(EDITOR, manual.id()).origin()).isEqualTo(RelationOrigin.MANUAL);
        assertThatThrownBy(() -> relations.propose("x", RelationOrigin.MANUAL, RelationScope.GLOBAL, "m",
                List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void removingAutomaticRelationRejectsItAndManualOneDisappears() {
        relations.propose("rule", RelationOrigin.RULE, RelationScope.GLOBAL, "g1",
                List.of(proposal("RSL", "Rückmeldeschlüssel", evidence("wiki-a", "1"))));
        TermRelation proposed = all(EDITOR, null).get(0);
        TermRelation manual = relations.add(EDITOR, new TermRelations.NewRelation("ZV", "Zahlungsverkehr",
                RelationType.ABBREVIATION, RelationScope.GLOBAL, null, List.of()));

        assertThat(relations.remove(EDITOR, proposed.id(), null)).get().extracting(TermRelation::status)
                .isEqualTo(ReviewStatus.REJECTED);
        assertThat(relations.remove(EDITOR, manual.id(), manual.revision())).isEmpty();
        assertThatThrownBy(() -> relations.relation(EDITOR, manual.id()))
                .isInstanceOf(UnknownRelationException.class);
        assertThat(store.history(manual.id())).extracting(TermRelationStore.AuditEntry::action)
                .containsExactly("create", "remove");
    }

    @Test
    void staleRevisionConflicts() {
        TermRelation created = relations.add(EDITOR, new TermRelations.NewRelation("ZV", "Zahlungsverkehr",
                RelationType.ABBREVIATION, RelationScope.GLOBAL, null, List.of()));
        relations.reject(EDITOR, created.id(), created.revision());

        assertThatThrownBy(() -> relations.restore(EDITOR, created.id(), created.revision()))
                .isInstanceOf(RelationConflictException.class)
                .extracting(e -> ((RelationConflictException) e).revision()).isEqualTo(created.revision() + 1);
    }

    @Test
    void automaticRelationsAreHiddenWhenNoEvidenceIsReadable() {
        relations.propose("rule", RelationOrigin.RULE, RelationScope.GLOBAL, "g1", List.of(
                proposal("RSL", "Rückmeldeschlüssel", evidence("wiki-a", "1"), evidence("wiki-b", "1")),
                proposal("KPI", "Kennzahl", evidence("wiki-b", "1")),
                proposal("XYZ", "Xaver Yps Zett", evidence("wiki-a", "404"))));
        hidden.add(new PageKey("wiki-b", "1"));

        List<TermRelation> visible = all(READER, null);

        assertThat(visible).extracting(TermRelation::fromTerm).containsExactly("RSL");
        assertThat(visible.get(0).evidence()).extracting(RelationEvidence::sourceId).containsExactly("wiki-a");
        String kpi = TermRelation.idOf(TermRelation.key("KPI", "Kennzahl", RelationType.ABBREVIATION,
                RelationScope.GLOBAL));
        assertThatThrownBy(() -> relations.relation(READER, kpi)).isInstanceOf(UnknownRelationException.class);
    }

    @Test
    void scopedRelationsNeedMetadataAndApplyOnlyToTheirSource() {
        TermRelation scoped = relations.add(EDITOR, new TermRelations.NewRelation("ZV", "Zahlungsverkehr",
                RelationType.ABBREVIATION, new RelationScope("wiki-a", null), null, List.of()));

        assertThat(relations.expansions(EDITOR, "ZV", "wiki-a")).containsExactly("Zahlungsverkehr");
        assertThat(relations.expansions(EDITOR, "ZV", "wiki-b")).isEmpty();
        assertThat(relations.forTerms(EDITOR, List.of("zv"), "wiki-b", "DEV")).isEmpty();
        assertThat(relations.relations(EDITOR, new TermRelations.RelationSearch(null, null, null, null, "wiki-b",
                50, null)).relations()).isEmpty();
        metadataDenied.add("wiki-a");
        assertThatThrownBy(() -> relations.relation(READER, scoped.id())).isInstanceOf(UnknownRelationException.class);
    }

    @Test
    void writesNeedSyncOnTheScope() {
        assertThatThrownBy(() -> relations.add(READER, new TermRelations.NewRelation("ZV", "Zahlungsverkehr",
                RelationType.ABBREVIATION, RelationScope.GLOBAL, null, List.of())))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> relations.add(READER, new TermRelations.NewRelation(null, null, null,
                new RelationScope("wiki-a", "DEV"), null, List.of()))).isInstanceOf(AccessDeniedException.class);
        TermRelation created = relations.add(EDITOR, new TermRelations.NewRelation("ZV", "Zahlungsverkehr",
                RelationType.ABBREVIATION, RelationScope.GLOBAL, null, List.of()));
        assertThatThrownBy(() -> relations.reject(READER, created.id(), null))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(relations.relation(EDITOR, created.id()).status()).isEqualTo(ReviewStatus.CONFIRMED);
    }

    @Test
    void invalidInputIsRejected() {
        assertThatThrownBy(() -> relations.add(EDITOR, new TermRelations.NewRelation("Beleg", " beleg ",
                RelationType.SYNONYM, RelationScope.GLOBAL, null, List.of())))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> relations.add(EDITOR, new TermRelations.NewRelation("ZV", "Zahlungsverkehr",
                RelationType.ABBREVIATION, new RelationScope("unbekannt", null), null, List.of())))
                .isInstanceOf(UnknownSourceException.class);
        assertThatThrownBy(() -> relations.relations(EDITOR, new TermRelations.RelationSearch(null, null, null,
                null, null, 0, null))).isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> relations.relations(EDITOR, new TermRelations.RelationSearch(null, null, null,
                null, null, 10, "kaputt"))).isInstanceOf(InvalidQueryException.class);
    }

    @Test
    void pagesThroughSortedRelations() {
        for (String term : List.of("AA", "BB", "CC", "DD", "EE")) {
            relations.add(EDITOR, new TermRelations.NewRelation(term, term + " Lang", RelationType.ABBREVIATION,
                    RelationScope.GLOBAL, null, List.of()));
        }
        List<String> seen = new java.util.ArrayList<>();
        String cursor = null;
        do {
            TermRelations.RelationList page = relations.relations(EDITOR, new TermRelations.RelationSearch(null,
                    null, null, null, null, 2, cursor));
            page.relations().forEach(relation -> seen.add(relation.fromTerm()));
            cursor = page.nextCursor();
        } while (cursor != null);

        assertThat(seen).containsExactly("AA", "BB", "CC", "DD", "EE");
    }

    @Test
    void competingMeaningsAreSeparateAndRejectingOneKeepsTheOther() {
        relations.propose("rule", RelationOrigin.RULE, RelationScope.GLOBAL, "g1", List.of(
                proposal("RSL", "Rückmeldeschlüssel", evidence("wiki-a", "1")),
                proposal("RSL", "Ressourcenstatusliste", evidence("wiki-b", "1"))));
        List<TermRelation> both = all(EDITOR, null);
        assertThat(both).extracting(TermRelation::toTerm)
                .containsExactlyInAnyOrder("Rückmeldeschlüssel", "Ressourcenstatusliste");

        relations.reject(EDITOR, both.stream().filter(r -> r.toTerm().equals("Ressourcenstatusliste")).findFirst()
                .orElseThrow().id(), null);
        relations.propose("rule", RelationOrigin.RULE, RelationScope.GLOBAL, "g2", List.of(
                proposal("RSL", "Rückmeldeschlüssel", evidence("wiki-a", "1")),
                proposal("RSL", "Ressourcenstatusliste", evidence("wiki-b", "1"))));

        assertThat(all(EDITOR, null)).extracting(TermRelation::toTerm).containsExactly("Rückmeldeschlüssel");
        // Eine Sperre im Geltungsbereich einer Quelle sperrt nicht dieselbe Beziehung global oder anderswo.
        relations.propose("rule", RelationOrigin.RULE, new RelationScope("wiki-b", null), "g3",
                List.of(proposal("RSL", "Ressourcenstatusliste", evidence("wiki-b", "1"))));
        assertThat(all(EDITOR, ReviewStatus.PROPOSED)).hasSize(2);
    }
}
