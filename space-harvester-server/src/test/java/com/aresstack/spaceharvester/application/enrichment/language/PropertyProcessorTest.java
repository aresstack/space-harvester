package com.aresstack.spaceharvester.application.enrichment.language;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import java.util.List;
import org.junit.jupiter.api.Test;

class PropertyProcessorTest {

    private final PropertyProcessor processor = new PropertyProcessor();

    @Test
    void findsAttributivePropertiesWithProposedBaseFormAndStateForParticiples() {
        String text = "Die zuständige Sachbearbeitung prüft den eingereichten Beleg.";

        assertThat(processor.process(plain(text)).payloadJson()).isEqualTo("{\"properties\":["
                + "{\"subject\":\"Sachbearbeitung\",\"subjectNormalized\":\"sachbearbeitung\","
                + "\"property\":\"zuständige\",\"normalized\":\"zuständig\",\"kind\":\"attribute\","
                + "\"form\":\"attributive\",\"negated\":false,\"section\":null,\"charStart\":0,\"charEnd\":30},"
                + "{\"subject\":\"Beleg\",\"subjectNormalized\":\"beleg\",\"property\":\"eingereichten\","
                + "\"normalized\":\"eingereicht\",\"kind\":\"state\",\"form\":\"attributive\",\"negated\":false,"
                + "\"section\":null,\"charStart\":37,\"charEnd\":60}]}");
    }

    @Test
    void findsPredicativePropertiesWithNegationAndCoordination() {
        String json = processor.process(plain("Der Dienst ist heute nicht erreichbar. Die Seite ist veraltet."
                + " Der Export bleibt schnell und zuverlässig. Der Bericht ist danach vollständig.")).payloadJson();

        assertThat(json)
                .contains("{\"subject\":\"Dienst\",\"subjectNormalized\":\"dienst\",\"property\":\"erreichbar\","
                        + "\"normalized\":\"erreichbar\",\"kind\":\"attribute\",\"form\":\"predicative\","
                        + "\"negated\":true")
                .contains("\"subject\":\"Seite\",\"subjectNormalized\":\"seite\",\"property\":\"veraltet\","
                        + "\"normalized\":\"veraltet\",\"kind\":\"state\"")
                .contains("\"property\":\"schnell\"").contains("\"property\":\"zuverlässig\"")
                .contains("\"subject\":\"Bericht\",\"subjectNormalized\":\"bericht\",\"property\":\"vollständig\"");
    }

    @Test
    void findsPredicativePropertiesInInvertedOrder() {
        assertThat(processor.process(plain("Seit heute ist der Dienst wieder verfügbar.")).payloadJson())
                .contains("\"subject\":\"Dienst\",\"subjectNormalized\":\"dienst\",\"property\":\"verfügbar\"");
    }

    @Test
    void keepsTextOrderAcrossBothForms() {
        String json = processor.process(plain("Der Dienst ist stabil und die neue Seite funktioniert.")).payloadJson();

        assertThat(json.indexOf("\"property\":\"stabil\"")).isPositive()
                .isLessThan(json.indexOf("\"property\":\"neue\""));
    }

    @Test
    void ignoresPassiveVoiceNounPredicatesAndOpenClauses() {
        assertThat(processor.process(plain("Der Beleg wird geprüft. Der Dienst ist ein Server. Die Daten sind "
                + "exportiert worden. Der Plan ist zu prüfen.")).payloadJson())
                .isEqualTo("{\"properties\":[]}");
    }

    @Test
    void limitsTheNumberOfEntriesWithAWarning() {
        EnrichmentOutput output = processor.process(plain("Der neue Dienst ist schnell.\n".repeat(600)));

        assertThat(output.payloadJson().split("\"subject\":")).hasSize(PropertyProcessor.MAX_PROPERTIES + 1);
        assertThat(output.warnings()).containsExactly("Nur die ersten 1000 von 1200 Eigenschaften");
    }

    private static PageText plain(String text) {
        return new PageText("t/v1", text, List.of());
    }
}
