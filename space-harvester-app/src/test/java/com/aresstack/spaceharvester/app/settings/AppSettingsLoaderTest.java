package com.aresstack.spaceharvester.app.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Die App liest dieselben Schlüssel wie der Dienst; Geheimnisse kommen aus Platzhaltern und erscheinen nirgends. */
class AppSettingsLoaderTest {

    @TempDir
    Path home;

    private AppSettings load(String yaml, Map<String, String> env) throws Exception {
        Path file = home.resolve("space-harvester-app.yml");
        Files.writeString(file, yaml);
        return new AppSettingsLoader(new Placeholders(env::get), home).load(file);
    }

    @Test
    void readsSourcesProxyAndStorageLikeTheServer() throws Exception {
        AppSettings settings = load("""
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                    - id: wiki
                      type: confluence
                      title: Wiki
                      baseUrl: https://wiki.example.org
                      auth:
                        type: pat
                        token: ${WIKI_TOKEN}
                      client-certificate:
                        enabled: true
                        type: windows-my
                        thumbprint: ABCDEF
                    - id: handbuch
                      type: files
                      options:
                        directory: /srv/handbuch
                  network:
                    proxy:
                      mode: pac-url-manual
                      pac-url: http://proxy.example.org/proxy.pac
                  assets:
                    max-size: 5MB
                """.formatted(home.resolve("daten")), Map.of("WIKI_TOKEN", "tok-123"));

        assertThat(settings.problems()).isEmpty();
        assertThat(settings.sources()).extracting(AppSettings.SourceEntry::id).containsExactly("wiki", "handbuch");
        SourceSettingsConfig wiki = settings.sources().get(0).config();
        assertThat(wiki.auth().type()).isEqualTo(SourceSettingsConfig.AuthType.PAT);
        assertThat(settings.sources().get(0).secretVariable()).as("Name bleibt auch aufgelöst erhalten")
                .isEqualTo("WIKI_TOKEN");
        assertThat(settings.sources().get(0).secretMissing()).isFalse();
        assertThat(wiki.clientCertificate().type()).isEqualTo(SourceSettingsConfig.CertificateStoreType.WINDOWS_MY);
        assertThat(settings.proxy().mode()).isEqualTo(ProxySettings.Mode.PAC_URL_MANUAL);
        assertThat(settings.storage().dataDirectory()).isEqualTo(home.resolve("daten"));
        assertThat(settings.storage().maxAssetBytes()).isEqualTo(5L * 1024 * 1024);
        assertThat(settings.toString()).doesNotContain("tok-123");
    }

    @Test
    void keepsASourceWhoseSecretIsMissingAndNamesTheVariable() throws Exception {
        AppSettings settings = load("""
                space-harvester:
                  sources:
                    - id: wiki
                      type: confluence
                      base-url: https://wiki.example.org
                      auth:
                        type: basic
                        username: angelo
                        password: ${WIKI_PASSWORD}
                """, Map.of());

        AppSettings.SourceEntry entry = settings.sources().get(0);
        assertThat(entry.secretMissing()).isTrue();
        assertThat(entry.secretVariable()).isEqualTo("WIKI_PASSWORD");
        assertThat(settings.storage().dataDirectory()).isEqualTo(home.resolve("data"));
    }

    @Test
    void reportsBrokenSourcesWithoutStoppingAndWithoutContent() throws Exception {
        AppSettings settings = load("""
                space-harvester:
                  sources:
                    - id: kaputt
                      type: confluence
                      base-url: "http://exa mple.org/geheim-123"
                    - id: gut
                      type: files
                      options:
                        directory: /srv
                """, Map.of());

        assertThat(settings.sources()).extracting(AppSettings.SourceEntry::id).containsExactly("gut");
        assertThat(settings.problems()).singleElement().asString().contains("kaputt").doesNotContain("geheim-123");
    }

    @Test
    void yamlErrorsNameTheLineOnly() throws Exception {
        AppSettings settings = load("space-harvester:\n  sources: [\n    token: geheim-456\n", Map.of());

        assertThat(settings.sources()).isEmpty();
        assertThat(settings.problems()).isNotEmpty().allSatisfy(p -> assertThat(p).doesNotContain("geheim-456"));
    }

    @Test
    void aMissingFileMeansDefaultsWithoutSources() {
        AppSettings settings = new AppSettingsLoader(new Placeholders(name -> null), home)
                .load(home.resolve("fehlt.yml"));

        assertThat(settings.sources()).isEmpty();
        assertThat(settings.problems()).isEmpty();
    }

    @Test
    void aProxyPasswordInTheFileIsNeverUsed() throws Exception {
        AppSettings settings = load("""
                space-harvester:
                  network:
                    proxy:
                      mode: pac-url-powershell
                      auth:
                        username: proxy-nutzer
                        password: geheim
                """, Map.of());
        assertThat(settings.proxy().authentication().username()).isEqualTo("proxy-nutzer");
        assertThat(settings.proxy().authentication().password()).isNull();
        assertThat(settings.problems()).singleElement().asString().contains("auth.password", "Tresor")
                .doesNotContain("geheim");
    }

    @Test
    void withoutAProxySectionWindowsUsesThePacAddressViaPowerShell() throws Exception {
        Path file = home.resolve("ohne-proxy.yml");
        Files.writeString(file, "space-harvester:\n  sources: []\n");
        assertThat(new AppSettingsLoader(new Placeholders(name -> null), home, true).load(file).proxy().mode())
                .isEqualTo(ProxySettings.Mode.PAC_URL_POWERSHELL);
        assertThat(new AppSettingsLoader(new Placeholders(name -> null), home, false).load(file).proxy())
                .isEqualTo(ProxySettings.disabled());
        Files.writeString(file, "space-harvester:\n  network:\n    proxy:\n      mode: disabled\n");
        assertThat(new AppSettingsLoader(new Placeholders(name -> null), home, true).load(file).proxy().mode())
                .as("ausdrücklich gesetzt gilt").isEqualTo(ProxySettings.Mode.DISABLED);
    }

    @Test
    void disabledSourcesAsAMappingIsReportedNotIgnored() throws Exception {
        Path file = home.resolve("abgeschaltet.yml");
        Files.writeString(file, "space-harvester-app:\n  disabled-sources:\n    wiki: true\n");
        AppSettings settings = new AppSettingsLoader(new Placeholders(name -> null), home).load(file);
        assertThat(settings.problems()).anyMatch(problem -> problem.contains("disabled-sources"));
    }

    @Test
    void sourceRulesTheCoreWouldRejectAreReportedAndFallBackToGlobal() throws Exception {
        AppSettings settings = load("""
                space-harvester:
                  sync:
                    sources:
                      wiki:
                        interval: PT30S
                        inventory-interval: PT2H
                        refresh-strategy: time-based
                        withheld-retention: -P1D
                      docs:
                        max-age: P400D
                """, Map.of());
        assertThat(settings.problems())
                .anyMatch(problem -> problem.contains("sync.sources.wiki.interval"))
                .anyMatch(problem -> problem.contains("sync.sources.wiki.refresh-strategy"))
                .anyMatch(problem -> problem.contains("sync.sources.wiki.withheld-retention"))
                .anyMatch(problem -> problem.contains("sync.sources.docs.max-age"));
        var wiki = settings.syncRule("wiki");
        assertThat(wiki.changeSearchInterval()).as("unter einer Minute gilt global").isNull();
        assertThat(wiki.inventoryInterval()).isEqualTo(java.time.Duration.ofHours(2));
        assertThat(wiki.refreshStrategy()).as("zeitbasiert ohne Frist gilt global").isNull();
        assertThat(wiki.withheldRetention()).isNull();
        assertThat(settings.syncRule("docs").maxAge()).isNull();
    }
}
