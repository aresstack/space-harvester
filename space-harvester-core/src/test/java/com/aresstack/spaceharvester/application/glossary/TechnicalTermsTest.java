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
