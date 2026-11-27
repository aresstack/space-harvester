package com.aresstack.spaceharvester.app.runtime;

import com.aresstack.spaceharvester.ai.AiServiceSettings;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelBudgetTest {

    @Test
    void theChatBudgetTakesTheSettingFirstThenTheCatalogOfTheService() {
        assertThat(ModelBudget.chat(32_000, 128_000, 4_000)).isEqualTo(new ModelBudget(32_000, 4_000));
        assertThat(ModelBudget.chat(null, 128_000, 4_000)).as("aus dem Modellkatalog")
                .isEqualTo(new ModelBudget(128_000, 4_000));
        assertThat(ModelBudget.chat(null, 128_000, null)).as("Ausgabelimit wie im Core gesendet")
                .isEqualTo(new ModelBudget(128_000, AiServiceSettings.DEFAULT_CHAT_MAX_TOKENS));
        assertThat(ModelBudget.chat(null, null, 4_000)).as("keine Kontextlänge bekannt").isNull();
    }

    @Test
    void textReadAsBinaryCountsPerByteNotPerCharacter() {
        // Erfundener Inhalt: EBCDIC-Bytes eines Lademoduls, als ISO-8859-1 gelesen – „Binärsalat mit Tofu“.
        byte[] raw = new byte[3_000];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (0x80 + i % 0x7F);
        }
        String salad = new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(ModelBudget.tokens(salad)).as("je Zeichen zwei UTF-8-Bytes, also zwei Tokens")
                .isEqualTo(6_000);
        String cobol = "       IDENTIFICATION DIVISION.\n       PROGRAM-ID. ABC.\n";
        assertThat(ModelBudget.units(cobol, 0, cobol.length())).as("Quelltext unverändert nach Zeichen")
                .isEqualTo(cobol.length());
        assertThat(ModelBudget.units("\u0001", 0, 1)).as("Steuerzeichen ein Token")
                .isEqualTo(ModelBudget.CHARACTERS_PER_TOKEN);
    }

    @Test
    void fitStopsWhereTheUnitsRunOutAndNeverSplitsASurrogatePair() {
        String text = "ab\u00e4\uD83D\uDE00c";
        assertThat(ModelBudget.fit(text, 0, 2)).isEqualTo(2);
        assertThat(ModelBudget.fit(text, 0, 7)).as("ä kostet 2 Bytes × 3").isEqualTo(2);
        assertThat(ModelBudget.fit(text, 0, 8)).isEqualTo(3);
        assertThat(ModelBudget.fit(text, 0, 19)).as("Emoji ganz oder gar nicht").isEqualTo(3);
        assertThat(ModelBudget.fit(text, 0, 20)).isEqualTo(5);
        assertThat(ModelBudget.fit(text, 0, Long.MAX_VALUE)).isEqualTo(text.length());
    }
}
