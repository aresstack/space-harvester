package com.aresstack.spaceharvester.application.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.terms.InMemoryTermRelationStore;
import com.aresstack.spaceharvester.application.terms.RelationScope;
import com.aresstack.spaceharvester.application.terms.RelationType;
import com.aresstack.spaceharvester.application.terms.TermRelations;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * „Nur Fachbegriffe“ (K5b) über das echte Glossar und die echten Beziehungen: Ablehnen lässt den nächsten nachrücken,
 * gesperrte Quellen liefern nichts, Änderungen an Beziehungen melden sich beim Beobachter. Erfundene Begriffe.
 */
class TechnicalTermsCoreTest {

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
    private final TechnicalTerms terms = new TechnicalTerms(glossary, relations);

    private static HarvestSource source(String id) {
        HarvestSource source = mock(HarvestSource.class);
        when(source.id()).thenReturn(id);
        return source;
    }

    private GlossaryEntry add(String term, RelationScope scope) {
        return glossary.add(EDITOR, new Glossary.NewEntry(term, scope, null, List.of()));
    }

    private List<String> page(String q, int limit) {
        return terms.search(EDITOR, q, null, limit, null).hits().stream().map(hit -> hit.entry().term()).toList();
    }

    @Test
    void aRejectedTermDropsOutAndTheNextMovesUp() {
        GlossaryEntry anker = add("Ankerschein", OPEN);
        add("Kieselgrund", OPEN);
        add("Wolkenpegel", OPEN);
        assertThat(page(null, 2)).containsExactly("Ankerschein", "Kieselgrund");

        glossary.reject(EDITOR, anker.id(), anker.revision());

        assertThat(page(null, 2)).containsExactly("Kieselgrund", "Wolkenpegel");
    }

    @Test
    void aHitThroughASynonymNamesIt() {
        add("Wolkenpegel", OPEN);
        relations.add(EDITOR, new TermRelations.NewRelation("Wolkenpegel", "Dunsthöhe", RelationType.SYNONYM,
                RelationScope.GLOBAL, null, List.of()));

        assertThat(terms.search(EDITOR, "Dunst", null, 50, null).hits()).singleElement().satisfies(hit -> {
            assertThat(hit.entry().term()).isEqualTo("Wolkenpegel");
            assertThat(hit.viaSynonym()).isEqualTo("Dunsthöhe");
        });
    }

    @Test
    void aLockedSourceYieldsNothing() {
        add("Ankerschein", OPEN);
        add("Schattenmarke", LOCKED);
        relations.add(EDITOR, new TermRelations.NewRelation("Ankerschein", "Hafenzettel", RelationType.SYNONYM,
                LOCKED, null, List.of()));
        metadataDenied.add("wiki-b");

        assertThat(page(null, 50)).containsExactly("Ankerschein");
        assertThat(page("Hafen", 50)).isEmpty();
        assertThat(terms.search(EDITOR, null, new TechnicalTerms.Scope("wiki-b", null), 50, null).hits()).isEmpty();
    }

    @Test
    void relationChangesReachTheObserver() {
        AtomicInteger seen = new AtomicInteger();
        Runnable stop = relations.subscribe(seen::incrementAndGet);

        relations.add(EDITOR, new TermRelations.NewRelation("Wolkenpegel", "Dunsthöhe", RelationType.SYNONYM,
                RelationScope.GLOBAL, null, List.of()));
        stop.run();
        relations.add(EDITOR, new TermRelations.NewRelation("Kieselgrund", "Schotterbett", RelationType.SYNONYM,
                RelationScope.GLOBAL, null, List.of()));

        assertThat(seen).hasValue(1);
    }
}
