package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.controls.MarkedTextRenderer;
import com.aresstack.spaceharvester.application.query.HitReason;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Treffergrund (R1): Die Marke steht nur, wenn nicht die eingegebene Anfrage den Treffer lieferte, und nennt die Art in
 * den Worten der App; das Overlay sagt ehrlich, wo es keine Fundstelle gibt.
 */
class HitReasonsTest {

    private static final HitReason.Relation ABBREVIATION =
            new HitReason.Relation("RSL", "Rückmeldeschlüssel", "abbreviation");

    @Test
    void theOriginalQueryInTheFullTextNeedsNoMark() {
        assertThat(HitReasons.mark(List.of())).isNull();
        assertThat(HitReasons.mark(List.of(HitReason.text("RSL", null, List.of("rsl"), List.of()),
                HitReason.text("Rückmeldeschlüssel", ABBREVIATION, List.of(), List.of())))).isNull();
    }

    @Test
    void aRelationOrAVariantIsMarkedWithTheWordsOfTheAppAndAShortForm() {
        assertThat(HitReasons.mark(List.of(HitReason.text("Rückmeldeschlüssel", ABBREVIATION, List.of(),
                List.of("rückmeldeschlüssel"))))).isEqualTo(new MarkedTextRenderer.Mark(
                "Abkürzung „Rückmeldeschlüssel“", "Abkürzung"));
        assertThat(HitReasons.mark(List.of(HitReason.text("Email", new HitReason.Relation("E-Mail", "Email",
                "variant"), List.of(), List.of()))).full()).isEqualTo("Schreibvariante „Email“");
        assertThat(HitReasons.mark(List.of(HitReason.text("Nachtlauf", null, List.of(), List.of())
                .fromVariant(new HitReason.Variant(2, HitReason.Variant.Origin.RULES)))))
                .isEqualTo(new MarkedTextRenderer.Mark("Variante „Nachtlauf“", "Variante"));
    }

    @Test
    void withoutAFullTextHitTheMarkSaysHowItWasFound() {
        // Auflage Architekt: gerade hier ist die Ursache nicht offensichtlich.
        assertThat(HitReasons.mark(List.of(HitReason.source("RSL", null))).full())
                .isEqualTo("von der Quelle gemeldet");
        assertThat(HitReasons.mark(List.of(HitReason.semantic("RSL"))).full()).isEqualTo("ähnlich im Inhalt");
    }

    @Test
    void theDetailsNameWordsOnlyWhereTheIndexReportedThem() {
        String details = HitReasons.details(List.of(
                HitReason.text("Rückmeldeschlüssel", ABBREVIATION, List.of("rückmeldeschlüssel"), List.of())
                        .fromVariant(new HitReason.Variant(1, HitReason.Variant.Origin.MODEL)),
                HitReason.source("RSL", null),
                HitReason.semantic("RSL")));

        assertThat(details).contains("Gesucht mit „Rückmeldeschlüssel“ im Volltext.", "Im Titel: rückmeldeschlüssel",
                "„Rückmeldeschlüssel“ statt „RSL“, bestätigt als Abkürzung.", "Variante 1 der Suche, vom KI-Modell.",
                "Von der Quelle gemeldet auf „RSL“. Noch nicht im Cache geprüft, daher keine Fundstelle.",
                "Ähnlich im Inhalt zu „RSL“").doesNotContain("Im Text:");
    }

    @Test
    void theMarkStaysOutOfTheExcerptText() {
        KeywordSearch.Hit core = new KeywordSearch.Hit("wiki", "7", "DEV", "Betrieb", "Der RSL steht oben", null,
                "3", null, null, null, null, List.of(HitReason.text("Rückmeldeschlüssel", ABBREVIATION, List.of(),
                List.of("rückmeldeschlüssel"))));

        SearchView.Hit hit = SearchView.Hit.of(core);
        assertThat(hit.excerpt()).isEqualTo("Der RSL steht oben");
        assertThat(hit.mark().brief()).isEqualTo("Abkürzung");
    }
}
