package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Die Vorzugsmodelle kommen aus der mitgelieferten Datei und wählen wie im Enterprise AI Client. */
class PreferredModelsTest {

    @Test
    void theBundledPreferencesPickTheEnterpriseDefaultsOrFallBack() {
        PreferredModels preferred = PreferredModels.bundled();
        assertThat(preferred.model("embedding")).isEqualTo("danielheinz/e5-base-sts-en-de");
        assertThat(preferred.match("embedding")).isEmpty();
        assertThat(ModelPreselection.choose(List.of("kipitz:danielheinz/e5-base-sts-en-de", "bge-m3"), "",
                preferred.model("embedding"), preferred.match("embedding")))
                .isEqualTo("kipitz:danielheinz/e5-base-sts-en-de");
        assertThat(ModelPreselection.choose(List.of("gpt-4o", "gpt-oss-120b-instruct"), "",
                preferred.model("chat"))).as("genau ein Modell mit dem Namensteil").isEqualTo("gpt-oss-120b-instruct");
        assertThat(ModelPreselection.choose(List.of("a", "b"), "b", preferred.model("chat")))
                .as("eine angebotene Wahl bleibt").isEqualTo("b");
        assertThat(ModelPreselection.choose(List.of(), "eigenes", "x")).isEqualTo("eigenes");
    }
}
