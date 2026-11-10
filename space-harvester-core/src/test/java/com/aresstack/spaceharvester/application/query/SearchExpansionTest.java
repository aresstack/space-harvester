package com.aresstack.spaceharvester.application.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link SearchExpansion}: bestätigte Gegenstücke als eigene Anfragen, je Quelle, begrenzt, nie transitiv. */
class SearchExpansionTest {

    private static final AccessContext APP = AccessContext.technicalClient("app");

    private final List<String> asked = new ArrayList<>();
    private final Map<String, List<String>> relations = Map.of(
            "rsl", List.of("Rückmeldeschlüssel"),
            "rückmeldeschlüssel", List.of("RSL", "Antwortschlüssel"),
            "antwortschlüssel", List.of("Rückmeldeschlüssel", "Rückmeldecode"),
            "e-mail", List.of("Email"),
            "application programming interface", List.of("API"));
    private final SearchExpansion expansion = new SearchExpansion((caller, term, sourceId, spaceKey) -> {
        asked.add(term + "@" + sourceId + "/" + spaceKey);
        return relations.getOrDefault(term.toLowerCase(java.util.Locale.ROOT), List.of());
    });

    @Test
    void theWholeQueryAndEachWordGetTheirConfirmedCounterpartsWithoutTransitivity() {
        List<String> queries = expansion.expand(APP, "RSL Meldung", "wiki", "DEV");

        // „Rückmeldeschlüssel“ wird nicht noch einmal zu „Antwortschlüssel“ erweitert.
        assertThat(queries).containsExactly("Rückmeldeschlüssel Meldung");
        assertThat(asked).containsExactly("RSL Meldung@wiki/DEV", "RSL@wiki/DEV", "Meldung@wiki/DEV");
    }

    @Test
    void aMultiWordTermIsLookedUpAsAWholeFirstAndTheResultIsBounded() {
        assertThat(expansion.expand(APP, "Application Programming Interface", "wiki", null))
                .containsExactly("API");
        assertThat(expansion.expand(APP, "Antwortschlüssel", null, null)).containsExactly("Rückmeldeschlüssel",
                "Rückmeldecode");
        assertThat(expansion.expand(APP, "Rückmeldeschlüssel Antwortschlüssel E-Mail", "wiki", null))
                .hasSize(SearchExpansion.MAX_EXPANSIONS)
                .containsExactly("RSL Antwortschlüssel E-Mail", "Antwortschlüssel Antwortschlüssel E-Mail",
                        "Rückmeldeschlüssel Rückmeldeschlüssel E-Mail");
    }

    @Test
    void sentencesCaseAndFailuresDoNotExpand() {
        assertThat(expansion.expand(APP, "Wie wird der RSL in der Meldung der Fachabteilung heute vergeben", "wiki",
                null)).isEmpty();
        assertThat(expansion.expand(APP, "rsl", "wiki", null)).containsExactly("Rückmeldeschlüssel");
        assertThat(SearchExpansion.replace("Der RSL-Wert", "RSL", "X")).isEqualTo("Der RSL-Wert");
        assertThat(SearchExpansion.replace("rsl heute", "RSL", "Rückmeldeschlüssel"))
                .isEqualTo("Rückmeldeschlüssel heute");
        assertThat(SearchExpansion.NONE.expand(APP, "RSL", "wiki", null)).isEmpty();
        SearchExpansion failing = new SearchExpansion((caller, term, sourceId, spaceKey) -> {
            throw new IllegalStateException("kaputt");
        });
        assertThat(failing.expand(APP, "RSL", "wiki", null)).isEmpty();
        assertThat(expansion.expand(APP, "  ", "wiki", null)).isEmpty();
    }
}
