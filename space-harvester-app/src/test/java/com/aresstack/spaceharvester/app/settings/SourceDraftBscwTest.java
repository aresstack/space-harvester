package com.aresstack.spaceharvester.app.settings;

import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

final class SourceDraftBscwTest {

    private static final String FOLDER_URL = "https://bscw.example.org/sec/bscw.cgi/146377797";

    @Test
    void storesBscwAddressLikeConfluenceWithoutExposingSecrets() {
        SourceDraft source = SourceDraft.from(Map.of(
                "id", "bscw", "type", "bscw", "base-url", FOLDER_URL,
                "auth", Map.of("type", "basic", "username", "reader", "password", "${BSCW_PASSWORD}"),
                "options", Map.of("maxDepth", "5"))).draft();

        assertThat(source.serverAddress()).isTrue();
        assertThat(source.generic()).isFalse();
        assertThat(source.baseUrl()).isEqualTo(FOLDER_URL);
        assertThat(source.authType()).isEqualTo(SourceSettingsConfig.AuthType.BASIC);
        assertThat(source.problems()).isEmpty();
        assertThat(source.yaml("  ")).contains("base-url: \"" + FOLDER_URL + "\"",
                        "type: basic", "username: \"reader\"", "password: ${BSCW_PASSWORD}")
                .doesNotContain("rootUrl:", "secret-value");
    }

    @Test
    void migratesExistingBscwRootUrlWithoutLosingItsOtherOptions() {
        SourceDraft source = SourceDraft.from(Map.of(
                "id", "bscw", "type", "bscw",
                "auth", Map.of("type", "basic", "username", "reader", "password", "${BSCW_PASSWORD}"),
                "options", Map.of("rootUrl", FOLDER_URL, "maxDepth", "7"))).draft();

        assertThat(source.baseUrl()).isEqualTo(FOLDER_URL);
        assertThat(source.extraOptions()).containsEntry("maxDepth", "7")
                .doesNotContainKey("rootUrl");
        assertThat(source.yaml("  ")).contains("base-url: \"" + FOLDER_URL + "\"",
                "maxDepth: \"7\"").doesNotContain("rootUrl:");
    }
}
