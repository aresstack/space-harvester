package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.source.SourceSettingsConfig.Auth;
import com.aresstack.spaceharvester.source.SourceSettingsConfig.AuthType;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfiguredSourceCatalogTest {

    private static final URI BASE = URI.create("https://confluence.example.org/wiki");

    private static SourceSettingsConfig source(String id, Auth auth) {
        return new SourceSettingsConfig(id, "confluence", null, BASE, auth, Map.of());
    }

    @Test
    void validSourcesAreAvailableByIdInConfigurationOrder() {
        ConfiguredSourceCatalog catalog = ConfiguredSourceCatalog.of(List.of(
                source("wiki", new Auth(AuthType.BASIC, "harvester", "secret", null)),
                source("team-wiki", new Auth(AuthType.PAT, null, null, "token")),
                source("public", null)), "test");

        assertThat(catalog.all()).extracting(s -> s.id()).containsExactly("wiki", "team-wiki", "public");
        assertThat(catalog.find("team-wiki")).isPresent();
        assertThat(catalog.find("missing")).isEmpty();
        assertThat(catalog.find("public").orElseThrow().title()).isEqualTo("public");
    }

    @Test
    void disabledCertificateMayKeepAnUnresolvedPasswordPlaceholder() {
        SourceSettingsConfig.ClientCertificate kept = new SourceSettingsConfig.ClientCertificate(false,
                SourceSettingsConfig.CertificateStoreType.PKCS12, java.nio.file.Path.of("client.p12"), "client",
                "${KEYSTORE_PASSWORD}", null);

        assertThat(ConfiguredSourceCatalog.of(List.of(new SourceSettingsConfig("wiki", "confluence", null, BASE,
                new Auth(AuthType.PAT, null, null, "token"), Map.of(), kept, null)), "test").find("wiki")).isPresent();
        assertThatThrownBy(() -> ConfiguredSourceCatalog.of(List.of(source("wiki",
                new Auth(AuthType.PAT, null, null, "${CONFLUENCE_PAT}"))), "test"))
                .hasMessageContaining("auth.token: Platzhalter ${CONFLUENCE_PAT} hat keinen Wert");
    }

    @Test
    void reportsAllProblemsOfAllSourcesAtOnce() {
        assertThatThrownBy(() -> ConfiguredSourceCatalog.of(List.of(
                new SourceSettingsConfig("Bad Id", "confluence", null, BASE, null, Map.of()),
                new SourceSettingsConfig("wiki", "sharepoint", null, null, null, Map.of()),
                source("basic", new Auth(AuthType.BASIC, "harvester", null, null)),
                source("pat", new Auth(AuthType.PAT, "harvester", null, "token")),
                source("none", new Auth(AuthType.NONE, null, "secret", null)),
                source("untyped", new Auth(null, "harvester", "secret", null)),
                new SourceSettingsConfig("reserved", "confluence", null, BASE, null, Map.of("credentialRef", "x")),
                source("basic", null)), "test"))
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .satisfies(e -> assertThat(((InvalidSourceConfigurationException) e).problems()).containsExactly(
                        "sources[0] (id=Bad Id): id fehlt oder ist ungültig (erlaubt: Kleinbuchstaben, Ziffern, "
                                + "Bindestrich; z. B. 'confluence')",
                        "sources[1] (id=wiki): type fehlt oder ist unbekannt (bekannt: confluence)",
                        "sources[1] (id=wiki): base-url fehlt",
                        "sources[2] (id=basic): auth.type basic braucht auth.username und auth.password",
                        "sources[3] (id=pat): auth.username/auth.password passen nicht zu auth.type pat",
                        "sources[4] (id=none): auth.type ist none, aber Zugangsdaten sind gesetzt; auth.type basic "
                                + "oder pat angeben",
                        "sources[5] (id=untyped): auth.type fehlt (none, basic oder pat)",
                        "sources[6] (id=reserved): options.credentialRef ist nicht erlaubt; dafür gibt es eigene "
                                + "Felder",
                        // Doppelt ist doppelt, auch wenn die erste Quelle mit dieser ID selbst fehlerhaft ist.
                        "sources[7] (id=basic): id ist doppelt vergeben")
                        .hasSize(9));
    }

    @Test
    void duplicateIdsAreRejected() {
        assertThatThrownBy(() -> ConfiguredSourceCatalog.of(List.of(source("wiki", null), source("wiki", null)),
                "test"))
                .hasMessageContaining("sources[1] (id=wiki): id ist doppelt vergeben");
    }

    @Test
    void adapterValidationProblemsAreReportedPerSource() {
        assertThatThrownBy(() -> ConfiguredSourceCatalog.of(List.of(new SourceSettingsConfig("wiki", "confluence",
                null, BASE, null, Map.of("pageSize", "0"))), "test"))
                .isInstanceOf(InvalidSourceConfigurationException.class)
                .hasMessageContaining("sources[0] (id=wiki): ")
                .hasMessageContaining("pageSize");
    }

    @Test
    void toStringNeverShowsSecrets() {
        SourceSettingsConfig config = source("wiki", new Auth(AuthType.BASIC, "harvester", "geheim-123", null));
        SourceSettingsConfig pat = source("pat", new Auth(AuthType.PAT, null, null, "token-xyz"));

        assertThat(config.toString()).contains("harvester").doesNotContain("geheim-123");
        assertThat(pat.toString()).doesNotContain("token-xyz");
    }
}
