package com.aresstack.spaceharvester.application.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.query.SearchExpansion;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.terms.InMemoryTermRelationStore;
import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.RelationScope;
import com.aresstack.spaceharvester.application.terms.RelationType;
import com.aresstack.spaceharvester.application.terms.TermRelation;
import com.aresstack.spaceharvester.application.terms.TermRelations;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Suchmethode „Synonyme der Fachbegriffe“ (K5a) über das echte Glossar und die echten Beziehungen: nur vorgeschlagene
 * Synonyme bestätigter Fachbegriffe; abgelehnte, bestätigte (die weitet die gewöhnliche Erweiterung aus) und Begriffe
 * der Einordnung TECHNISCH nie; gesperrte Quellen nicht. Erfundene Begriffe.
 */
class GlossarySynonymsTest {

    private static final AccessContext EDITOR = AccessContext.technicalClient("editor");
    private static final Instant T0 = Instant.parse("2026-11-21T18:00:00Z");
    private static final RelationScope OPEN = new RelationScope("wiki-a", null);
    private static final RelationScope LOCKED = new RelationScope("wiki-b", null);

    private final Set<String> metadataDenied = new HashSet<>();
    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of(source("wiki-a"), source("wiki-b"));
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return List.of("wiki-a", "wiki-b").contains(sourceId) ? Optional.of(source(sourceId)) : Optional.empty();
        }
    };
    private final AccessPolicy policy = (context, scope, kind) -> kind == AccessKind.METADATA
            && metadataDenied.contains(scope.sourceId()) ? AccessDecision.DENY : AccessDecision.ALLOW;
    private final AccessGuard access = new AccessGuard(policy);
    private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    private final Glossary glossary = new Glossary(catalog, new InMemoryGlossaryStore(), access,
            key -> Optional.empty(), clock);
    private final TermRelations relations = new TermRelations(catalog, new InMemoryTermRelationStore(), access,
            key -> Optional.empty(), clock);
    private final GlossarySynonyms synonyms = new GlossarySynonyms(glossary, relations);

    private static HarvestSource source(String id) {
        HarvestSource source = mock(HarvestSource.class);
        when(source.id()).thenReturn(id);
        return source;
    }

    private GlossaryEntry term(String term, RelationScope scope) {
        return glossary.add(EDITOR, new Glossary.NewEntry(term, scope, null, List.of()));
    }

    private void propose(RelationScope scope, String from, String... to) {
        relations.propose("lauf", RelationOrigin.AI, scope, Set.of(RelationType.SYNONYM), "g1",
                java.util.Arrays.stream(to).map(other -> new TermRelations.Proposal(from, other,
                        RelationType.SYNONYM, List.of())).toList());
    }

    private List<String> found(String query) {
        return synonyms.expansion().expand(EDITOR, query, "wiki-a", null);
    }

    @Test
    void aProposedSynonymOfATechnicalTermBecomesAQueryMarkedAsProposal() {
        term("Wolkenpegel", OPEN);
        propose(OPEN, "Wolkenpegel", "Dunsthöhe");

        assertThat(synonyms.counterparts(EDITOR, "wolkenpegel", "wiki-a", null))
                .containsExactly(new SearchExpansion.Counterpart("Dunsthöhe", GlossarySynonyms.PROPOSED));
        assertThat(found("Wolkenpegel")).containsExactly("Dunsthöhe");
    }

    @Test
    void withoutATechnicalTermNothingIsAdded() {
        propose(OPEN, "Wolkenpegel", "Dunsthöhe");

        assertThat(found("Wolkenpegel")).isEmpty();
    }

    @Test
    void rejectedAndConfirmedSynonymsAreNeverAddedHere() {
        term("Wolkenpegel", OPEN);
        propose(OPEN, "Wolkenpegel", "Dunsthöhe", "Nebelmaß");
        TermRelation rejected = relations.relations(EDITOR, new TermRelations.RelationSearch("Nebelmaß", null, null,
                null, null, 50, null)).relations().getFirst();
        relations.reject(EDITOR, rejected.id(), rejected.revision());
        relations.add(EDITOR, new TermRelations.NewRelation("Wolkenpegel", "Schleierlinie", RelationType.SYNONYM,
                RelationScope.GLOBAL, null, List.of()));

        assertThat(found("Wolkenpegel")).containsExactly("Dunsthöhe");
    }

    @Test
    void aTermClassifiedAsTechnicalNeverExpands() {
        GlossaryEntry entry = term("Wolkenpegel", OPEN);
        glossary.categorize(EDITOR, entry.id(), GlossaryDerivation.Category.TECHNISCH, entry.revision());
        propose(OPEN, "Wolkenpegel", "Dunsthöhe");

        assertThat(found("Wolkenpegel")).isEmpty();
    }

    @Test
    void aLockedSourceContributesNothing() {
        term("Wolkenpegel", LOCKED);
        propose(LOCKED, "Wolkenpegel", "Dunsthöhe");
        metadataDenied.add("wiki-b");

        assertThat(synonyms.expansion().expand(EDITOR, "Wolkenpegel", "wiki-b", null)).isEmpty();
    }

    @Test
    void allProposalsComeBeforeTheLimitWhichTheSearchAppliesAfterDeduplication() {
        term("Wolkenpegel", OPEN);
        propose(OPEN, "Wolkenpegel", "Dunsthöhe", "Nebelmaß", "Schleierlinie", "Taugrenze", "Hauchmarke");

        synonyms.requireReadable(EDITOR);
        assertThat(synonyms.expansion().allExpansions(EDITOR, "Wolkenpegel", "wiki-a", null)).hasSize(5);
        assertThat(found("Wolkenpegel")).hasSize(SearchExpansion.MAX_EXPANSIONS);
    }

    @Test
    void visibleInScopeAndConfirmedMustBeTheSameEntry() {
        // Auflage 3: im Bereich wiki-b nur ein Eintrag der gesperrten Quelle; sichtbar ist nur der in wiki-a, außerhalb.
        term("Wolkenpegel", LOCKED);
        term("Wolkenpegel", OPEN);
        metadataDenied.add("wiki-b");

        assertThat(glossary.confirmedTerms(LOCKED)).as("ohne Rechteprüfung").contains("wolkenpegel");
        assertThat(glossary.confirmedTerms(EDITOR, LOCKED)).isEmpty();
        assertThat(glossary.confirmedTerms(EDITOR, OPEN)).containsExactly("wolkenpegel");
    }
}
