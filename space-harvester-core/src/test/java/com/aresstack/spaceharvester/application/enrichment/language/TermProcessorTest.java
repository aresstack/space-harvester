package com.aresstack.spaceharvester.application.enrichment.language;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.terms.TermKind;
import com.aresstack.spaceharvester.application.terms.TermOccurrence;
import com.aresstack.spaceharvester.application.terms.TermPayload;
import java.util.List;
import org.junit.jupiter.api.Test;

class TermProcessorTest {

    private final TermProcessor processor = new TermProcessor();

    private static List<TermPayload.Entry> terms(String text) {
        return TermPayload.parse(new TermProcessor().process(new PageText("test/v1", text, List.of())).payloadJson());
    }

    private static TermPayload.Entry entry(List<TermPayload.Entry> terms, String normalized, TermKind kind) {
        return terms.stream().filter(entry -> entry.normalized().equals(normalized) && entry.kind() == kind)
                .findFirst().orElseThrow(() -> new AssertionError(normalized + " fehlt in " + terms));
    }

    @Test
    void everyRepeatedOccurrenceKeepsItsOwnPosition() {
        String text = "Der Beleg wird geprüft. Danach wird der Beleg archiviert. Der Beleg bleibt.";
        TermPayload.Entry beleg = entry(terms(text), "beleg", TermKind.NOUN);

        assertThat(beleg.count()).isEqualTo(3);
        assertThat(beleg.occurrences()).hasSize(3);
        for (TermOccurrence occurrence : beleg.occurrences()) {
            assertThat(text.substring(occurrence.start(), occurrence.end())).isEqualTo("Beleg");
        }
    }

    @Test
    void singularAndPluralStayTwoTerms() {
        List<TermPayload.Entry> terms = terms("Der Beleg wird geprüft. Die Belege werden archiviert.");

        assertThat(entry(terms, "beleg", TermKind.NOUN).count()).isEqualTo(1);
        assertThat(entry(terms, "belege", TermKind.NOUN).count()).isEqualTo(1);
    }

    @Test
    void anAcronymFoundByBothRulesCountsOnceWithItsExpansion() {
        String text = "Die API (Application Programming Interface) ist dokumentiert. Die API ist stabil. Zwei APIs "
                + "fehlen.";
        List<TermPayload.Entry> terms = terms(text);
        TermPayload.Entry api = entry(terms, "api", TermKind.ACRONYM);

        assertThat(api.count()).isEqualTo(3);
        assertThat(api.expansion()).isEqualTo("Application Programming Interface");
        assertThat(api.occurrences()).extracting(occurrence -> text.substring(occurrence.start(), occurrence.end()))
                .containsExactly("API", "API", "APIs");
        assertThat(terms).filteredOn(entry -> entry.normalized().equals("api")).hasSize(1);
    }

    @Test
    void acronymsInNonGermanSentencesAreStillFound() {
        String text = "The RSL is configured by the operator.";

        assertThat(entry(terms(text), "rsl", TermKind.ACRONYM).occurrences()).containsExactly(
                new TermOccurrence(4, 7));
    }

    @Test
    void umlautsAndCaseAreKeptInTheTermButFoldedInTheSearchValue() {
        TermPayload.Entry entry = entry(terms("Der Rückmeldeschlüssel wird gesetzt."), "rückmeldeschlüssel",
                TermKind.NOUN);

        assertThat(entry.term()).isEqualTo("Rückmeldeschlüssel");
    }

    @Test
    void resultIsSortedBySearchValueThenKind() {
        List<TermPayload.Entry> terms = terms("Die Zuständigkeit liegt beim RSL. Der Antrag geht an die Abteilung.");

        assertThat(terms).extracting(TermPayload.Entry::normalized).isSorted();
        assertThat(processor.id()).isEqualTo("terms");
    }
}
