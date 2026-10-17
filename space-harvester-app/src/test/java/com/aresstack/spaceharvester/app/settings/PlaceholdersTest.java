package com.aresstack.spaceharvester.app.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PlaceholdersTest {

    private final Placeholders placeholders = new Placeholders(Map.of("TOKEN", "geheim", "HOST", "wiki")::get);

    @Test
    void resolvesValuesDefaultsAndNesting() {
        assertThat(placeholders.resolve("${TOKEN}")).isEqualTo("geheim");
        assertThat(placeholders.resolve("https://${HOST}.example.org")).isEqualTo("https://wiki.example.org");
        assertThat(placeholders.resolve("${MISSING:standard}")).isEqualTo("standard");
        assertThat(placeholders.resolve("${MISSING:}")).isEmpty();
        assertThat(placeholders.resolve("${MISSING:${HOST}}")).isEqualTo("wiki");
        assertThat(placeholders.resolve("ohne")).isEqualTo("ohne");
    }

    @Test
    void namesTheMissingVariableButNoValue() {
        assertThatThrownBy(() -> placeholders.resolve("${CONFLUENCE_TOKEN}"))
                .isInstanceOf(Placeholders.UnresolvedPlaceholderException.class)
                .hasMessageContaining("CONFLUENCE_TOKEN");
    }
}
