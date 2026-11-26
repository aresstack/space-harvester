package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.SourceDraft;
import com.aresstack.spaceharvester.deck.ndv.NdvProfile;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Die Regel „Indexieren“ des NDV-Bereichs als Wissensquelle {@code ndv}: Zuordnung zum Server, Bibliotheken. */
class NdvSourceRulesTest {

    private static SourceSettingsConfig config(String type, Map<String, String> options) {
        return new SourceSettingsConfig("natural", type, "NDV Lager", null, null, options);
    }

    @Test
    void aSourceBelongsToTheServerWithTheSameHostAndPort() {
        NdvProfile profile = new NdvProfile("ndv.example.test", 8011, "");
        assertThat(NdvSourceRules.matches(config("ndv", Map.of("host", "NDV.example.test")), profile))
                .as("Port fehlt = 8011, Host ohne Groß-/Kleinschreibung").isTrue();
        assertThat(NdvSourceRules.matches(config("ndv", Map.of("host", "ndv.example.test", "port", "2700")),
                profile)).isFalse();
        assertThat(NdvSourceRules.matches(config("ftp", Map.of("host", "ndv.example.test")), profile)).isFalse();
        assertThat(NdvSourceRules.matches(config("ndv", Map.of("host", "anderer.example.test")), profile)).isFalse();
    }

    @Test
    void librariesComeFromTheStartPointsAndAreAddedWithoutDuplicates() {
        assertThat(NdvSourceRules.libraries("lager, KASSE/KA*, ,lager")).containsExactly("LAGER");
        assertThat(NdvSourceRules.libraries(null)).isEmpty();
        assertThat(NdvSourceRules.withLibraries("KASSE/KA*", List.of("lager", "kasse"))).isEqualTo("KASSE/KA*, LAGER, KASSE");
        assertThat(NdvSourceRules.withLibraries(null, List.of("lager"))).isEqualTo("LAGER");
    }

    @Test
    void sameEndpointDoesNotProveSameAccount() {
        var configured = config("ndv", Map.of("host", "ndv.example.test", "credentialRef", "keepass:NDV-A"));
        assertThat(NdvSourceRules.sameAccount(configured, null, "NDV-A")).isTrue();
        assertThat(NdvSourceRules.sameAccount(configured, null, "NDV-B")).isFalse();
        assertThat(NdvSourceRules.sameAccount(configured, null, "")).isFalse();
        assertThat(NdvSourceRules.sameAccount(configured, "NDV-B", "NDV-A")).isFalse();
        assertThat(NdvSourceRules.sameAccount(configured, "NDV-B", "NDV-B")).isTrue();
    }

    @Test
    void anExistingSourceIsExtendedAndKeepsEverythingElse() {
        SourceDraft draft = new SourceDraft("natural", "ndv", "NDV Lager", null, null, null,
                SourceSettingsConfig.AuthType.BASIC, "PILOT", "NATURAL_PASSWORD", null, null, null, null, null, null,
                Map.of("host", "ndv.example.test", "startPoints", "KASSE", "maxResources", "100"), false);
        SourceDraft.Editable extended = NdvSourceRules.extended(new SourceDraft.Editable(draft, List.of()),
                List.of("LAGER"));
        assertThat(extended.draft().extraOptions()).containsEntry("startPoints", "KASSE, LAGER")
                .containsEntry("maxResources", "100").containsEntry("host", "ndv.example.test");
        assertThat(extended.draft().username()).isEqualTo("PILOT");
        assertThat(extended.draft().secretVariable()).isEqualTo("NATURAL_PASSWORD");
    }
}
