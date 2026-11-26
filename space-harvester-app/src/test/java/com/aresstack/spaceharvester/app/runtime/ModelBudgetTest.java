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
}
