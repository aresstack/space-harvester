package com.aresstack.spaceharvester.application.enrichment.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Standards wie {@code StatementSettings} im Enterprise AI Client; Werte aus Properties ohne Umgebung. */
class StatementSettingsTest {

    @Test
    void defaultsAreOffWithTheLimitsOfTheClient() {
        assertThat(StatementSettings.fromProperties(new Properties())).isEqualTo(StatementSettings.DEFAULT)
                .isEqualTo(new StatementSettings(false, true, 40, 1500));
    }

    @Test
    void readsKeysWithAndWithoutPrefix() {
        Properties properties = new Properties();
        properties.setProperty("space-harvester.enrichment.statements.enabled", "true");
        properties.setProperty("verify", "false");
        properties.setProperty("space-harvester.enrichment.statements.max-paragraphs", "5");
        properties.setProperty("max-paragraph-chars", "800");

        assertThat(StatementSettings.fromProperties(properties)).isEqualTo(new StatementSettings(true, false, 5, 800));
    }

    @Test
    void rejectsInvalidValuesWithoutNamingThem() {
        Properties properties = new Properties();
        properties.setProperty("max-paragraphs", "viele");
        assertThatThrownBy(() -> StatementSettings.fromProperties(properties))
                .hasMessageContaining("max-paragraphs").hasMessageNotContaining("viele");
        assertThatThrownBy(() -> new StatementSettings(true, true, 0, 1500))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
