package com.aresstack.spaceharvester.app.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Der Quellen-Assistent ergänzt die Konfigurationsdatei, ohne sie sonst zu verändern, und schreibt nie ein
 * Geheimnis hinein, nur Platzhalter.
 */
class ConfigFileEditorTest {

    @TempDir
    Path home;

    private final AppSettingsLoader loader = new AppSettingsLoader(new Placeholders(Map.of("WIKI_TOKEN", "t")::get),
            Path.of("unbenutzt"));
    private final ConfigFileEditor editor = new ConfigFileEditor(loader);

    @Test
    void appendsToTheExampleAndKeepsCommentsAndPlaceholders() throws Exception {
        Path file = home.resolve("app.yml");
        String example;
        try (InputStream in = getClass().getResourceAsStream("/com/aresstack/spaceharvester/app/space-harvester-app.example.yml")) {
            example = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Files.writeString(file, example);

        ConfigFileEditor.Draft draft = editor.draft(file, wiki());
        editor.save(file, draft);

        String text = Files.readString(file);
        assertThat(text).startsWith(example.substring(0, example.indexOf("space-harvester:")))
                .contains("# - id: handbuch", "token: ${CONFLUENCE_TOKEN}", "token: ${WIKI_TOKEN}",
                        "#   mode: pac-url-powershell", "thumbprint: \"AB:CD\"");
        assertThat(home.resolve("app.yml.bak")).hasContent(example);
        AppSettings settings = loader.load(file);
        assertThat(settings.sources()).extracting(AppSettings.SourceEntry::id).containsExactly("confluence", "wiki");
        assertThat(settings.proxy().mode()).as("Beispiel läuft auch ohne Windows")
                .isEqualTo(new AppSettingsLoader(new Placeholders(name -> null), home).defaultProxy().mode());
        AppSettings.SourceEntry wiki = settings.sources().get(1);
        assertThat(wiki.secretMissing()).isFalse();
        assertThat(wiki.config().baseUrl()).hasToString("https://wiki.example.org");
        assertThat(wiki.config().clientCertificate().type())
                .isEqualTo(SourceSettingsConfig.CertificateStoreType.WINDOWS_MY);
        assertThat(wiki.config().trustBundle()).isEqualTo("firma");
    }

    @Test
    void createsAMissingFile() throws Exception {
        Path file = home.resolve("neu").resolve("app.yml");
        editor.save(file, editor.draft(file, files("handbuch", "C:\\Daten\\Hand \"buch\"")));

        AppSettings settings = loader.load(file);
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.sources()).singleElement().satisfies(source -> {
            assertThat(source.config().type()).isEqualTo("files");
            assertThat(source.config().options()).containsEntry("directory", "C:\\Daten\\Hand \"buch\"");
        });
        assertThat(home.resolve("neu").resolve("app.yml.bak")).doesNotExist();
    }

    @Test
    void addsTheSourcesListWhereItIsMissingOrEmpty() {
        String withoutList = """
                space-harvester:
                    network:
                        async-transport: web-client
                """;
        String inserted = ConfigFileEditor.insert(withoutList, files("a", "/tmp/a"));
        assertThat(inserted).contains("    sources:\n      - id: \"a\"\n");
        assertThat(loader.read(null, inserted).sources()).hasSize(1);
        assertThat(loader.read(null, inserted).problems()).isEmpty();

        String empty = "space-harvester:\n  sources: []   # noch nichts\n  export:\n    directory: /tmp/x\n";
        String filled = ConfigFileEditor.insert(empty, files("b", "/tmp/b"));
        assertThat(loader.read(null, filled).sources()).extracting(AppSettings.SourceEntry::id).containsExactly("b");
        assertThat(filled).contains("export:\n    directory: /tmp/x");

        String other = "spring:\n  ssl: {}\n";
        assertThat(loader.read(null, ConfigFileEditor.insert(other, files("c", "/tmp/c"))).sources()).hasSize(1);
    }

    @Test
    void listsAtTheSameIndentAsTheKeyAreExtended() {
        String text = """
                space-harvester:
                  sources:
                  - id: eins
                    type: files
                    options:
                      directory: /tmp/eins
                  network:
                    async-transport: web-client
                """;
        String inserted = ConfigFileEditor.insert(text, files("zwei", "/tmp/zwei"));
        AppSettings settings = loader.read(null, inserted);
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.sources()).extracting(AppSettings.SourceEntry::id).containsExactly("eins", "zwei");
        assertThat(inserted).contains("  network:\n    async-transport: web-client");
    }

    @Test
    void refusesDuplicatesIncompleteInputAndInlineLists() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, """
                space-harvester:
                  sources:
                    - id: wiki
                      type: confluence
                """);
        assertThatThrownBy(() -> editor.draft(file, wiki())).hasMessageContaining("gibt es schon");
        assertThatThrownBy(() -> editor.draft(file, files("x", null))).hasMessageContaining("Ordner fehlt");

        Files.writeString(file, "space-harvester:\n  sources: [ {id: a, type: files} ]\n");
        assertThatThrownBy(() -> editor.draft(file, files("x", "/tmp/x"))).hasMessageContaining("von Hand");
        assertThat(Files.readString(file)).contains("sources: [");
    }

    @Test
    void writesOnlyPlaceholdersForSecrets() {
        SourceDraft basic = new SourceDraft("intern", "confluence", null, "https://intern.example.org", null, null,
                SourceSettingsConfig.AuthType.BASIC, "svc-harvester", "INTERN_PASSWORD",
                SourceSettingsConfig.CertificateStoreType.PKCS12, "C:/certs/client.p12", "INTERN_CLIENT_PASSWORD",
                "client", null, null);
        assertThat(basic.problems()).isEmpty();
        assertThat(basic.yaml("")).contains("password: ${INTERN_PASSWORD}", "password: ${INTERN_CLIENT_PASSWORD}",
                "username: \"svc-harvester\"");
        assertThat(SourceDraft.suggestedVariable("mein-wiki", SourceSettingsConfig.AuthType.PAT))
                .isEqualTo("MEIN_WIKI_TOKEN");
        assertThat(new SourceDraft("w", "confluence", null, "https://w", null, null, SourceSettingsConfig.AuthType.PAT,
                null, "kein gültiger name", null, null, null, null, null, null).problems())
                .anySatisfy(p -> assertThat(p).contains("Umgebungsvariable"));
    }

    private static final String THREE = """
            # Kopf bleibt
            space-harvester:
              sources:
                - id: eins
                  type: files
                  options:
                    directory: /tmp/eins
                # zwei ist das Wiki
                - id: zwei
                  type: confluence
                  title: Wiki
                  base-url: https://wiki.example.org
                  auth:
                    type: pat
                    token: ${ZWEI_TOKEN}
                  options:
                    connectTimeoutMillis: 2000
                - id: drei
                  type: files
                  options:
                    directory: /tmp/drei
              network:
                async-transport: web-client
            """;

    @Test
    void editsAnEntryInPlaceAndKeepsOptionsTheWizardDoesNotAsk() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, THREE);

        SourceDraft.Editable editable = editor.editable(file, "zwei");
        assertThat(editable.plaintextSecrets()).isEmpty();
        SourceDraft zwei = editable.draft();
        assertThat(zwei.secretVariable()).isEqualTo("ZWEI_TOKEN");
        assertThat(zwei.extraOptions()).containsEntry("connectTimeoutMillis", "2000");

        SourceDraft renamed = new SourceDraft(zwei.id(), zwei.type(), "Neues Wiki", zwei.baseUrl(), null, null,
                zwei.authType(), null, zwei.secretVariable(), null, null, null, null, null, null, zwei.extraOptions());
        editor.save(file, editor.replace(file, renamed));

        String text = Files.readString(file);
        assertThat(text).startsWith("# Kopf bleibt").contains("# zwei ist das Wiki", "title: \"Neues Wiki\"",
                "connectTimeoutMillis: \"2000\"", "token: ${ZWEI_TOKEN}", "  network:\n    async-transport");
        AppSettings settings = loader.load(file);
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.sources()).extracting(AppSettings.SourceEntry::id).containsExactly("eins", "zwei", "drei");
        assertThat(settings.sources().get(1).config().options()).containsEntry("connectTimeoutMillis", "2000");
        assertThat(home.resolve("app.yml.bak")).hasContent(THREE);
    }

    @Test
    void removesOnlyTheChosenEntry() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, THREE);

        editor.save(file, editor.remove(file, "zwei"));

        String text = Files.readString(file);
        assertThat(text).doesNotContain("id: zwei", "ZWEI_TOKEN").contains("# zwei ist das Wiki", "id: drei");
        assertThat(loader.load(file).sources()).extracting(AppSettings.SourceEntry::id).containsExactly("eins", "drei");
        assertThat(editor.hasPlaintextSecret(file, "eins")).isFalse();
        assertThatThrownBy(() -> editor.remove(file, "zwei")).hasMessageContaining("zwei");
    }

    @Test
    void aPlaintextSecretIsReplacedByAPlaceholderAndLeavesNoBackup() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, THREE.replace("${ZWEI_TOKEN}", "klartext-geheim"));
        Files.writeString(home.resolve("app.yml.bak"), "alt mit klartext-geheim");
        assertThat(editor.hasPlaintextSecret(file, "zwei")).isTrue();

        SourceDraft.Editable editable = editor.editable(file, "zwei");
        assertThat(editable.plaintextSecrets()).containsExactly("auth.token");
        assertThat(editable.draft().secretVariable()).isEqualTo("ZWEI_TOKEN");
        assertThat(editable.draft().toString()).doesNotContain("klartext-geheim");
        editor.save(file, editor.replace(file, editable.draft()), false);

        assertThat(Files.readString(file)).doesNotContain("klartext-geheim").contains("token: ${ZWEI_TOKEN}");
        assertThat(home.resolve("app.yml.bak")).doesNotExist();
    }

    @Test
    void entriesWithKeysTheWizardDoesNotKnowAreNotEdited() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, THREE.replace("      title: Wiki", "      title: Wiki\n      enabled: false"));
        assertThatThrownBy(() -> editor.editable(file, "zwei")).hasMessageContaining("enabled");
    }

    @Test
    void relaxedKeySpellingsAreFoundLikeTheLoaderDoes() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, THREE.replace("space-harvester:", "space_harvester:").replace("  sources:", "  Sources:"));
        assertThat(loader.load(file).sources()).hasSize(3);

        editor.save(file, editor.remove(file, "drei"));
        editor.save(file, editor.draft(file, files("vier", "/tmp/vier")));

        String text = Files.readString(file);
        assertThat(text).containsOnlyOnce("space_harvester:").contains("  Sources:");
        assertThat(loader.load(file).sources()).extracting(AppSettings.SourceEntry::id)
                .containsExactly("eins", "zwei", "vier");
    }

    @Test
    void aDefaultInsideAPlaceholderCountsAsPlaintext() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, THREE.replace("${ZWEI_TOKEN}", "${ZWEI_TOKEN:standard-geheim}"));
        assertThat(editor.hasPlaintextSecret(file, "zwei")).isTrue();
        assertThat(editor.hasPlaintextSecret(file, "eins")).isFalse();
        SourceDraft.Editable editable = editor.editable(file, "zwei");
        assertThat(editable.plaintextSecrets()).containsExactly("auth.token");
        assertThat(editable.draft().secretVariable()).isEqualTo("ZWEI_TOKEN");
    }

    @Test
    void aDisabledCertificateIsNotDroppedByTheWizard() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, THREE.replace("      options:\n        connectTimeoutMillis",
                "      client-certificate:\n        enabled: false\n        type: pkcs12\n"
                        + "        path: /certs/c.p12\n        password: alt-geheim\n"
                        + "      options:\n        connectTimeoutMillis"));
        assertThat(Files.readString(file)).contains("enabled: false");
        assertThat(editor.hasPlaintextSecret(file, "zwei")).isTrue();
        assertThatThrownBy(() -> editor.editable(file, "zwei")).hasMessageContaining("abgeschaltet");
    }

    @Test
    void aPlaintextSecretAnywhereInTheFileCounts() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, THREE);
        assertThat(editor.fileHasPlaintextSecret(file)).isFalse();
        Files.writeString(file, THREE.replace("${ZWEI_TOKEN}", "klartext"));
        assertThat(editor.fileHasPlaintextSecret(file)).as("andere Quelle").isTrue();
        assertThat(editor.hasPlaintextSecret(file, "eins")).isFalse();
        Files.writeString(file, THREE + """
                spring:
                  ssl:
                    bundle:
                      jks:
                        intern:
                          truststore:
                            location: file:/certs/intern.p12
                            password: ${TRUST_PASSWORD:geheim}
                """);
        assertThat(editor.fileHasPlaintextSecret(file)).as("Vertrauensspeicher mit Standardwert").isTrue();
        assertThat(editor.fileHasPlaintextSecret(home.resolve("fehlt.yml"))).isFalse();
    }

    @Test
    void replacesOnlyTheProxyBlockAndKeepsCommentsAndNeighbours() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, """
                # Kopf bleibt
                space-harvester:
                  sources:
                    - id: handbuch
                      type: files
                      options:
                        directory: /daten
                  network:
                    # Proxy der Firma
                    proxy:
                      mode: disabled   # alter Wert
                      resolve-timeout: 7s
                    # danach bleibt
                    async-transport: web-client
                """);
        ConfigFileEditor.Draft base = editor.draft(file, files("notizen", "/notizen"));
        ProxyDraft proxy = new ProxyDraft(ProxySettings.Mode.MANUAL_PROXY, null, "proxy.firma.de", "3128",
                java.util.List.of("localhost", " *.intern ", ""), Duration.ofSeconds(7), null, null);

        ConfigFileEditor.Draft draft = editor.withProxy(file, base, proxy);
        editor.save(file, draft);

        String text = Files.readString(file);
        assertThat(text).startsWith("# Kopf bleibt").contains("    # Proxy der Firma\n    proxy:\n",
                "mode: manual-proxy", "proxy-host: \"proxy.firma.de\"", "proxy-port: 3128", "- \"*.intern\"",
                "resolve-timeout: 7000ms", "    # danach bleibt\n    async-transport: web-client", "- id: \"notizen\"")
                .doesNotContain("alter Wert", "mode: disabled");
        AppSettings settings = loader.load(file);
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.proxy()).isEqualTo(proxy.settings());
        assertThat(settings.proxy().resolveTimeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(settings.sources()).extracting(AppSettings.SourceEntry::id).containsExactly("handbuch", "notizen");
    }

    @Test
    void addsTheProxyWhereNetworkOrTheWholeBlockIsMissing() throws Exception {
        ProxyDraft pac = new ProxyDraft(ProxySettings.Mode.PAC_URL_MANUAL, "http://proxy.firma.de/proxy.pac", null,
                null, null, null, null, null);
        String withoutNetwork = ConfigFileEditor.proxy("""
                space-harvester:
                  sources: []
                """, pac);
        assertThat(withoutNetwork).isEqualTo("""
                space-harvester:
                  network:
                    proxy:
                      mode: pac-url-manual
                      pac-url: "http://proxy.firma.de/proxy.pac"
                  sources: []
                """);
        String withoutProxy = ConfigFileEditor.proxy("""
                space-harvester:
                  network:
                    async-transport: web-client
                """, pac);
        assertThat(withoutProxy).contains("""
                  network:
                    proxy:
                      mode: pac-url-manual
                """).contains("    async-transport: web-client");
        String empty = ConfigFileEditor.proxy("", new ProxyDraft(ProxySettings.Mode.DISABLED, null, null, null, null,
                null, null, null));
        assertThat(loader.read(home.resolve("x.yml"), empty).proxy()).isEqualTo(ProxySettings.disabled());
        assertThatThrownBy(() -> ConfigFileEditor.proxy("""
                space-harvester:
                  network: {proxy: {mode: disabled}}
                """, pac)).hasMessageContaining("network steht in einer Zeile");
    }

    @Test
    void theOutputDirectoryIsDownloadsUntilSetAndStaysInTheAppBlock() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, """
                # Kopf bleibt
                space-harvester-app:
                  data-directory: /daten   # Kommentar bleibt
                space-harvester:
                  sources: []
                """);
        assertThat(loader.load(file).outputDirectory()).isEqualTo(AppSettings.defaultOutputDirectory());
        Path chosen = home.resolve("Ablage \"neu\"");

        editor.save(file, editor.withOutputDirectory(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                chosen));

        assertThat(Files.readString(file)).startsWith("# Kopf bleibt").contains("data-directory: /daten   # Kommentar",
                "  output-directory: ");
        assertThat(loader.load(file).outputDirectory()).isEqualTo(chosen);
        // Erneut setzen ersetzt die Zeile, statt eine zweite anzulegen; null entfernt sie wieder.
        editor.save(file, editor.withOutputDirectory(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                home));
        assertThat(Files.readString(file).split("output-directory", -1)).hasSize(2);
        editor.save(file, editor.withOutputDirectory(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                null));
        assertThat(Files.readString(file)).doesNotContain("output-directory").contains("data-directory");
        assertThat(ConfigFileEditor.outputDirectory("space-harvester:\n  sources: []\n", "/x"))
                .startsWith("space-harvester-app:\n  output-directory: \"/x\"\n\nspace-harvester:");
    }

    @Test
    void writesTheAiBlockWithoutAKeyAndRemovesItWhenEmpty() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, """
                # Kopf bleibt
                space-harvester:
                  ai:
                    base-url: http://alt   # alter Wert
                    timeout: 45s
                  sources:
                    - id: handbuch
                      type: files
                      options:
                        directory: /daten
                """);
        AiDraft ai = new AiDraft(" https://ki.firma.de/v1 ", "gpt-4o-mini", "text-embedding-3-small", "1536",
                Duration.ofSeconds(45), java.util.List.of("handbuch", "", "handbuch"));

        ConfigFileEditor.Draft draft = editor.withAi(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                ai);
        editor.save(file, draft);

        String text = Files.readString(file);
        assertThat(text).startsWith("# Kopf bleibt").contains("  ai:\n    base-url: \"https://ki.firma.de/v1\"",
                "chat-model: \"gpt-4o-mini\"", "embedding-model: \"text-embedding-3-small\"",
                "embedding-dimensions: 1536", "timeout: 45000ms", "external-processing-sources:\n      - \"handbuch\"",
                "  sources:\n    - id: handbuch").doesNotContain("alter Wert", "api-key");
        AppSettings settings = loader.load(file);
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.ai().configured()).isTrue();
        assertThat(AiDraft.of(settings.ai())).isEqualTo(ai);
        assertThat(settings.ai().externalProcessingSources()).containsExactly("handbuch");

        editor.save(file, editor.withAi(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                AiDraft.EMPTY));
        assertThat(Files.readString(file)).doesNotContain("ai:", "base-url").contains("- id: handbuch");
        assertThat(loader.load(file).ai()).isEqualTo(AppSettings.AiSettings.NONE);
        assertThat(ConfigFileEditor.ai("", ai)).startsWith("\nspace-harvester:\n  ai:\n");
        assertThatThrownBy(() -> editor.withAi(file, new ConfigFileEditor.Draft("", null),
                new AiDraft("ftp://x", null, null, "abc", null, null)))
                .hasMessageContaining("http://", "positive ganze Zahl");
    }

    @Test
    void anIncompleteProxyIsRejectedBeforeAnythingIsWritten() throws Exception {
        Path file = home.resolve("app.yml");
        ConfigFileEditor.Draft base = editor.draft(file, files("notizen", "/notizen"));
        assertThatThrownBy(() -> editor.withProxy(file, base, new ProxyDraft(ProxySettings.Mode.MANUAL_PROXY, null,
                null, "99999", null, null, null, null))).hasMessageContaining("Proxy-Host")
                .hasMessageContaining("Proxy-Port");
        assertThat(file).doesNotExist();
        for (String pac : new String[] {"http://bad host/proxy.pac", "http://[invalid"}) {
            assertThat(new ProxyDraft(ProxySettings.Mode.PAC_URL_MANUAL, pac, null, null, null, null, null, null)
                    .problems()).as(pac).containsExactly(
                            "Die PAC-Adresse ist keine gültige Adresse (Leerzeichen oder Sonderzeichen?).");
        }
        assertThat(new ProxyDraft(ProxySettings.Mode.PAC_URL_MANUAL, "file:///C:/proxy.pac", null, null, null, null,
                null, null).problems()).isEmpty();
    }

    private static SourceDraft wiki() {
        return new SourceDraft("wiki", "confluence", "Wiki", "https://wiki.example.org", null, null,
                SourceSettingsConfig.AuthType.PAT, null, "WIKI_TOKEN",
                SourceSettingsConfig.CertificateStoreType.WINDOWS_MY, null, null, "confluence-client", "AB:CD", "firma");
    }

    private static SourceDraft files(String id, String directory) {
        return new SourceDraft(id, "files", null, null, directory, "*.md", null, null, null, null, null, null, null,
                null, null);
    }
}
