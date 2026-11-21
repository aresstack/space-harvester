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
    void aHitOnlyThroughASynonymIsMarkedAndItsTooltipNamesTermAndSynonym() {
        // K5a: bestätigt „Synonym“, vorgeschlagen „Synonym (Vorschlag)“; Tooltip mit Fachbegriff und Synonym.
        List<HitReason> confirmed = List.of(HitReason.text("Dunsthöhe", new HitReason.Relation("Wolkenpegel",
                "Dunsthöhe", "synonym"), List.of(), List.of("dunsthöhe")));
        List<HitReason> proposed = List.of(HitReason.text("Dunsthöhe", new HitReason.Relation("Wolkenpegel",
                "Dunsthöhe", "synonym-proposed"), List.of(), List.of("dunsthöhe")));

        assertThat(HitReasons.mark(confirmed).brief()).isEqualTo("Synonym");
        assertThat(HitReasons.tip(confirmed)).isEqualTo("Fachbegriff „Wolkenpegel“, Synonym „Dunsthöhe“");
        assertThat(HitReasons.mark(proposed)).isEqualTo(new MarkedTextRenderer.Mark(
                "Synonym (Vorschlag) „Dunsthöhe“", "Synonym (Vorschlag)"));
        assertThat(HitReasons.tip(proposed))
                .isEqualTo("Fachbegriff „Wolkenpegel“, vorgeschlagenes Synonym „Dunsthöhe“");
        assertThat(HitReasons.details(proposed)).contains("als Synonym des Fachbegriffs vorgeschlagen")
                .doesNotContain("bestätigt als");
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

    @Test
    void aNeighborIsMarkedShortWithTheSeedInTheTipAndTheOverlay() {
        HitReason.Neighbor via = new HitReason.Neighbor("wiki", "1", "Betrieb", HitReason.Neighbor.Edge.CHILD);
        List<HitReason> reasons = List.of(HitReason.text("Nachtlauf", null, List.of(), List.of("nachtlauf")),
                HitReason.neighbor("Nachtlauf", via));

        assertThat(HitReasons.mark(reasons)).isEqualTo(new MarkedTextRenderer.Mark("Nachbar", "Nachbar"));
        assertThat(HitReasons.tip(reasons)).isEqualTo("Unterseite von „Betrieb“");
        assertThat(HitReasons.details(reasons)).contains("Nachbarseite: Unterseite von „Betrieb“")
                .contains("gegen „Nachtlauf“ geprüft");
        assertThat(HitReasons.tip(List.of(HitReason.semantic("Nachtlauf")))).isNull();
        assertThat(HitReasons.neighborLine(new HitReason.Neighbor("wiki", "1", "Betrieb",
                HitReason.Neighbor.Edge.LINKS_TO))).isEqualTo("„Betrieb“ verweist hierher");
    }
}
