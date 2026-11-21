package com.aresstack.spaceharvester.application.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.RelationScope;
import com.aresstack.spaceharvester.application.terms.RelationType;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.TermRelation;
import com.aresstack.spaceharvester.application.terms.TermText;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Suchmethode „Nur Fachbegriffe“ (K5b): Begriff selbst oder eines seiner Synonyme passt; erfundene Begriffe. */
class TechnicalTermsTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("test");
    private static final Instant T0 = Instant.parse("2026-11-21T18:00:00Z");

    private static GlossaryEntry entry(String term, ReviewStatus status) {
        return new GlossaryEntry("g-" + term, term, TermText.normalize(term), RelationScope.GLOBAL,
                RelationOrigin.AI, status, List.of(), false, null, null, "ai", T0, T0, null, null, 1);
    }

    private static TermRelation synonym(String from, String to, ReviewStatus status) {
        String id = TermRelation.idOf(TermRelation.key(from, to, RelationType.SYNONYM, RelationScope.GLOBAL));
        return new TermRelation(id, from, to, RelationType.SYNONYM, RelationScope.GLOBAL, RelationOrigin.AI, status,
                List.of(), null, null, false, "ai", T0, null, null, 1);
    }

    private final List<GlossaryEntry> entries = List.of(entry("Wolkenpegel", ReviewStatus.CONFIRMED),
            entry("Kiesel-Verfahren", ReviewStatus.PROPOSED), entry("Ankerschein", ReviewStatus.CONFIRMED));
    private final List<TermRelation> synonyms = List.of(synonym("Wolkenpegel", "Dunsthöhe", ReviewStatus.PROPOSED),
            synonym("Schotterweg", "Kiesel-Verfahren", ReviewStatus.CONFIRMED));
    private final TechnicalTerms terms = new TechnicalTerms((caller, scope) -> entries,
            (caller, scope) -> synonyms);

    @Test
    void withoutTextAllTermsSortedWithoutSynonymMark() {
        TechnicalTerms.Page page = terms.search(CALLER, " ", null, 50, null);

        assertThat(page.hits()).extracting(hit -> hit.entry().term())
                .containsExactly("Ankerschein", "Kiesel-Verfahren", "Wolkenpegel");
        assertThat(page.hits()).allMatch(hit -> hit.viaSynonym() == null);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void aTermMatchesByItselfOrByAWordOfIt() {
        assertThat(terms.search(CALLER, "verf", null, 50, null).hits()).extracting(hit -> hit.entry().term())
                .containsExactly("Kiesel-Verfahren");
    }

    @Test
    void aTermMatchesThroughEitherEndOfASynonym() {
        TechnicalTerms.Page dunst = terms.search(CALLER, "dunst", null, 50, null);
        TechnicalTerms.Page schotter = terms.search(CALLER, "Schotter", null, 50, null);

        assertThat(dunst.hits()).singleElement().satisfies(hit -> {
            assertThat(hit.entry().term()).isEqualTo("Wolkenpegel");
            assertThat(hit.viaSynonym()).isEqualTo("Dunsthöhe");
        });
        assertThat(schotter.hits()).singleElement().satisfies(hit -> {
            assertThat(hit.entry().term()).isEqualTo("Kiesel-Verfahren");
            assertThat(hit.viaSynonym()).isEqualTo("Schotterweg");
        });
    }

    @Test
    void theCursorIsTheKeySoARejectBetweenPagesNeitherSkipsNorRepeats() {
        // Auflage AT 2: nach Ablehnen der ersten Zeile liefert Seite 2 genau den Rest.
        java.util.List<GlossaryEntry> live = new java.util.concurrent.CopyOnWriteArrayList<>(List.of(
                entry("Ankerschein", ReviewStatus.CONFIRMED), entry("Kieselgrund", ReviewStatus.PROPOSED),
                entry("Schattenmarke", ReviewStatus.PROPOSED), entry("Wolkenpegel", ReviewStatus.CONFIRMED)));
        TechnicalTerms paged = new TechnicalTerms((caller, scope) -> live, (caller, scope) -> List.of());
        TechnicalTerms.Page first = paged.search(CALLER, null, null, 2, null);
        live.removeIf(e -> e.term().equals("Ankerschein"));

        TechnicalTerms.Page second = paged.search(CALLER, null, null, 2, first.nextCursor());

        assertThat(first.hits()).extracting(hit -> hit.entry().term()).containsExactly("Ankerschein", "Kieselgrund");
        assertThat(second.hits()).extracting(hit -> hit.entry().term())
                .containsExactly("Schattenmarke", "Wolkenpegel");
    }

    @Test
    void theOrderFollowsTheGlossaryTabImportantFirst() {
        // Auflage AT 3: wie GlossaryOverview.ROW_ORDER (Seiten, Handelnder, Erwähnungen), ohne Wichtigkeit hinten.
        TechnicalTerms ordered = new TechnicalTerms((caller, scope) -> entries, (caller, scope) -> synonyms,
                (caller, scope) -> java.util.Map.of(
                        "wolkenpegel", new GlossaryDerivation.Importance(9, 1, 0, 0, 0),
                        "kiesel-verfahren", new GlossaryDerivation.Importance(2, 5, 3, 0, 0)));

        assertThat(ordered.search(CALLER, null, null, 50, null).hits()).extracting(hit -> hit.entry().term())
                .containsExactly("Wolkenpegel", "Kiesel-Verfahren", "Ankerschein");
        TechnicalTerms.Page first = ordered.search(CALLER, null, null, 1, null);
        assertThat(ordered.search(CALLER, null, null, 2, first.nextCursor()).hits())
                .extracting(hit -> hit.entry().term()).containsExactly("Kiesel-Verfahren", "Ankerschein");
    }

    @Test
    void aSynonymCountsOnlyWhereItsScopeCoversTheTerm() {
        // Auflage AT 5: ein Synonym eines anderen Space macht den Begriff nicht zum Treffer.
        GlossaryEntry inDev = new GlossaryEntry("g-dev", "Wolkenpegel", "wolkenpegel",
                new RelationScope("wiki", "DEV"), RelationOrigin.AI, ReviewStatus.PROPOSED, List.of(), false, null,
                null, "ai", T0, T0, null, null, 1);
        String id = TermRelation.idOf(TermRelation.key("Wolkenpegel", "Dunsthöhe", RelationType.SYNONYM,
                new RelationScope("wiki", "OPS")));
        TermRelation inOps = new TermRelation(id, "Wolkenpegel", "Dunsthöhe", RelationType.SYNONYM,
                new RelationScope("wiki", "OPS"), RelationOrigin.AI, ReviewStatus.PROPOSED, List.of(), null, null,
                false, "ai", T0, null, null, 1);
        TechnicalTerms scoped = new TechnicalTerms((caller, scope) -> List.of(inDev),
                (caller, scope) -> List.of(inOps));

        assertThat(scoped.search(CALLER, "dunst", null, 50, null).hits()).isEmpty();
        assertThat(scoped.synonymsOf(CALLER, inDev)).isEmpty();
        assertThat(scoped.search(CALLER, null, new TechnicalTerms.Scope(java.util.Set.of("wiki"),
                java.util.Set.of("OPS")), 50, null).hits()).isEmpty();
    }

    @Test
    void pagesWithAnOffsetCursor() {
        TechnicalTerms.Page first = terms.search(CALLER, null, null, 2, null);
        TechnicalTerms.Page second = terms.search(CALLER, null, null, 2, first.nextCursor());

        assertThat(first.hits()).hasSize(2);
        assertThat(second.hits()).extracting(hit -> hit.entry().term()).containsExactly("Wolkenpegel");
        assertThat(second.nextCursor()).isNull();
        assertThatThrownBy(() -> terms.search(CALLER, null, null, 2, "kaputt")).isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> terms.search(CALLER, null, null, 0, null)).isInstanceOf(InvalidQueryException.class);
    }
}
