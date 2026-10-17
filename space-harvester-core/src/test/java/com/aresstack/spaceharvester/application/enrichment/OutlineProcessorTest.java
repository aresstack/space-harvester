package com.aresstack.spaceharvester.application.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class OutlineProcessorTest {

    @Test
    void reportsWordsPerSectionAndForThePage() {
        String text = "Vorwort hier\nZiele\nDrei kurze Ziele.\nBetrieb \"Nacht\"\nx";
        int ziele = text.indexOf("Ziele");
        int betrieb = text.indexOf("Betrieb");
        PageText page = new PageText("t/v1", text, List.of(new PageText.Section("Ziele", 1, ziele, betrieb),
                new PageText.Section("Betrieb \"Nacht\"", 2, betrieb, text.length())));

        assertThat(new OutlineProcessor().process(page).payloadJson()).isEqualTo("{\"words\":9,\"sections\":["
                + "{\"heading\":\"Ziele\",\"level\":1,\"charStart\":" + ziele + ",\"charEnd\":" + betrieb
                + ",\"words\":4},"
                + "{\"heading\":\"Betrieb \\\"Nacht\\\"\",\"level\":2,\"charStart\":" + betrieb + ",\"charEnd\":"
                + text.length() + ",\"words\":3}]}");
    }

    @Test
    void anEmptyPageHasNoWords() {
        assertThat(new OutlineProcessor().process(new PageText("t/v1", "", List.of())).payloadJson())
                .isEqualTo("{\"words\":0,\"sections\":[]}");
    }
}
