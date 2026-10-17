package com.aresstack.spaceharvester.application.enrichment.language;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntityProcessorTest {

    private final EntityProcessor processor = new EntityProcessor();

    @Test
    void groupsTermsByNormalizedFormWithKindCountSectionAndFirstPlace() {
        String text = "Betrieb\nDie API liefert JSON an den Kunden. Der Kunde ruft die Api erneut auf.";
        PageText page = new PageText("t/v1", text, List.of(new PageText.Section("Betrieb", 2, 0, text.length())));

        EnrichmentOutput output = processor.process(page);

        int api = text.indexOf("API");
        assertThat(output.payloadJson()).isEqualTo("{\"entities\":["
                + "{\"term\":\"API\",\"normalized\":\"api\",\"kind\":\"acronym\",\"count\":2,\"section\":\"Betrieb\","
                + "\"charStart\":" + api + ",\"charEnd\":" + (api + 3) + "},"
                + "{\"term\":\"Betrieb\",\"normalized\":\"betrieb\",\"kind\":\"noun\",\"count\":1,"
                + "\"section\":\"Betrieb\",\"charStart\":0,\"charEnd\":7},"
                + "{\"term\":\"JSON\",\"normalized\":\"json\",\"kind\":\"acronym\",\"count\":1,\"section\":\"Betrieb\","
                + "\"charStart\":" + text.indexOf("JSON") + ",\"charEnd\":" + (text.indexOf("JSON") + 4) + "},"
                + "{\"term\":\"Kunde\",\"normalized\":\"kunde\",\"kind\":\"noun\",\"count\":1,\"section\":\"Betrieb\","
                + "\"charStart\":" + text.indexOf("Kunde ") + ",\"charEnd\":" + (text.indexOf("Kunde ") + 5) + "},"
                + "{\"term\":\"Kunden\",\"normalized\":\"kunden\",\"kind\":\"noun\",\"count\":1,"
                + "\"section\":\"Betrieb\",\"charStart\":" + text.indexOf("Kunden") + ",\"charEnd\":"
                + (text.indexOf("Kunden") + 6) + "}]}");
        assertThat(output.warnings()).isEmpty();
    }

    @Test
    void ignoresPronounsAndFunctionWords() {
        assertThat(processor.process(plain("Er sagt, dass es nicht geht und wir warten.")).payloadJson())
                .isEqualTo("{\"entities\":[]}");
    }

    @Test
    void reportsIdentifiersAndMultiWordTerms() {
        assertThat(processor.process(plain("Das Ticket ABC-1234 betrifft Confluence Data Center.")).payloadJson())
                .contains("{\"term\":\"Ticket ABC-1234\",\"normalized\":\"ticket abc-1234\",\"kind\":\"noun\"")
                .contains("{\"term\":\"Confluence Data Center\",\"normalized\":\"confluence data center\"");
        assertThat(processor.process(plain("Mit ABC-1234 geht es.")).payloadJson())
                .contains("{\"term\":\"ABC-1234\",\"normalized\":\"abc-1234\",\"kind\":\"identifier\"");
    }

    @Test
    void limitsTheNumberOfEntriesWithAWarning() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < EntityProcessor.MAX_ENTITIES + 3; i++) {
            text.append("Wort").append((char) ('a' + i % 26)).append((char) ('a' + i / 26 % 26)).append(".\n");
        }
        EnrichmentOutput output = processor.process(plain(text.toString()));

        assertThat(output.payloadJson().split("\"term\":")).hasSize(EntityProcessor.MAX_ENTITIES + 1);
        assertThat(output.warnings()).containsExactly("Nur die 500 häufigsten von 503 Begriffen");
    }

    @Test
    void isDeterministic() {
        PageText page = plain("Die neue Version ersetzt die alte Version. Die Version ist stabil.");
        assertThat(processor.process(page)).isEqualTo(processor.process(page));
    }

    private static PageText plain(String text) {
        return new PageText("t/v1", text, List.of());
    }
}
