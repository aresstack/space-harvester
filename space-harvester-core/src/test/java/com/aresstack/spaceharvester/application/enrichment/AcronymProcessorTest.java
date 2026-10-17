package com.aresstack.spaceharvester.application.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AcronymProcessorTest {

    private final AcronymProcessor processor = new AcronymProcessor();

    @Test
    void findsAcronymsWithCountsSectionsAndMatchingExpansions() {
        String text = "Intro\nDie Application Programming Interface (API) nutzt REST.\nBetrieb\nDie API und zwei APIs.";
        PageText page = new PageText("t/v1", text, List.of(new PageText.Section("Intro", 1, 0, 39),
                new PageText.Section("Betrieb", 2, text.indexOf("Betrieb"), text.length())));

        EnrichmentOutput output = processor.process(page);

        assertThat(output.payloadJson()).isEqualTo("{\"acronyms\":["
                + "{\"acronym\":\"API\",\"count\":3,\"expansion\":\"Application Programming Interface\","
                + "\"section\":\"Intro\",\"charStart\":" + text.indexOf("API") + "},"
                + "{\"acronym\":\"REST\",\"count\":1,\"expansion\":null,\"section\":\"Intro\",\"charStart\":"
                + text.indexOf("REST") + "}]}");
        assertThat(output.warnings()).isEmpty();
    }

    @Test
    void takesAnExpansionAfterTheAcronymOnlyWhenTheInitialsMatch() {
        assertThat(processor.process(plain("Das SSO (Single Sign-On) und das CI (keine Ahnung).")).payloadJson())
                .contains("{\"acronym\":\"SSO\",\"count\":1,\"expansion\":\"Single Sign-On\"")
                .contains("{\"acronym\":\"CI\",\"count\":1,\"expansion\":null");
    }

    @Test
    void ignoresWordsThatAreNotAcronyms() {
        assertThat(processor.process(plain("Ein Satz mit A, Ab, aB, ÄNDERUNGSPROTOKOLLE und x86.")).payloadJson())
                .isEqualTo("{\"acronyms\":[]}");
    }

    @Test
    void acceptsDigitsAndUmlauts() {
        assertThat(processor.process(plain("S3 ist kein Akronym, aber K8S und ÜBV sind welche.")).payloadJson())
                .contains("\"acronym\":\"K8S\"").contains("\"acronym\":\"ÜBV\"").doesNotContain("\"S3\"");
    }

    @Test
    void limitsTheNumberOfEntriesWithAWarning() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < AcronymProcessor.MAX_ACRONYMS + 5; i++) {
            text.append("AB").append(i).append(' ');
        }
        EnrichmentOutput output = processor.process(plain(text.toString()));

        assertThat(output.payloadJson().split("\"acronym\":")).hasSize(AcronymProcessor.MAX_ACRONYMS + 1);
        assertThat(output.warnings()).containsExactly("Nur die ersten 500 von 505 Akronymen");
    }

    private static PageText plain(String text) {
        return new PageText("t/v1", text, List.of());
    }
}
