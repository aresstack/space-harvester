package com.aresstack.spaceharvester.app.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.network.ProxySettings;
import com.aresstack.spaceharvester.source.network.TrustSettings;
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
    void sourceUsageSurvivesEditingAndDefaultsToExistingBehavior() throws Exception {
        Path file = home.resolve("usage.yml");
        editor.save(file, editor.draft(file, files("handbuch", "/tmp/a").withUsage(false, false)));
        var config = loader.load(file).sources().getFirst().config();
        assertThat(config.showInExplorer()).isFalse();
        assertThat(config.includeInGlobalSearch()).isFalse();

        SourceDraft original = editor.editable(file, "handbuch").draft();
        assertThat(original.showInExplorer()).isFalse();
        assertThat(original.includeInGlobalSearch()).isFalse();
        editor.save(file, editor.replace(file, original.withUsage(true, false)));
        assertThat(loader.load(file).sources().getFirst().config().showInExplorer()).isTrue();
        assertThat(loader.load(file).sources().getFirst().config().includeInGlobalSearch()).isFalse();

        editor.save(file, editor.draft(file, files("normal", "/tmp/b")));
        var defaults = loader.load(file).sources().get(1).config();
        assertThat(defaults.showInExplorer()).isTrue();
        assertThat(defaults.includeInGlobalSearch()).isTrue();
    }

    @Test
    void sourcePrefetchOverrideRoundTripsIndependentlyOfBackgroundFill() throws Exception {
        Path file = home.resolve("prefetch.yml");
        editor.save(file, editor.draft(file, files("handbuch", "/tmp/a")));
        var rule = new com.aresstack.spaceharvester.application.configuration.SourceSyncOverride(
                null, null, null, null, null, null, null, null, null, true, false);
        editor.save(file, editor.withSyncRule(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                "handbuch", rule));
        var restored = loader.load(file).syncRule("handbuch");
        assertThat(restored).isEqualTo(rule);
        assertThat(restored.effectivePrefetch(true)).isFalse();
        assertThat(restored.backgroundFill()).isTrue();
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

    /** Andere Typen des Core (#375): die Adresse steht unter options mit dem Schlüssel des Typs (#387). */
    @Test
    void otherSourceTypesKeepTheirAddressUnderOptions() throws Exception {
        Path file = home.resolve("app.yml");
        SourceDraft jes = new SourceDraft("host-jes", "jes", "Host", null, null, null, null, null, null,
                null, null, null, null, null, null, Map.of("host", "mvs.example.org", "credentialRef", "keepass:jes"));
        assertThat(jes.problems()).isEmpty();
        editor.save(file, editor.draft(file, jes));
        assertThat(Files.readString(file)).contains("type: jes", "host: \"mvs.example.org\"",
                "credentialRef: \"keepass:jes\"").doesNotContain("base-url", "directory");

        SourceDraft read = editor.editable(file, "host-jes").draft();
        assertThat(read.generic()).isTrue();
        assertThat(read.baseUrl()).isNull();
        assertThat(read.directory()).isNull();
        assertThat(read.extraOptions()).containsEntry("host", "mvs.example.org");
        AppSettings settings = loader.load(file);
        assertThat(settings.sources().get(0).config().options()).containsEntry("host", "mvs.example.org");
    }

    /** Anmeldung und Vertrauen anderer Typen (ADR 0022) bleiben beim Bearbeiten, auch ohne Passwort in der Datei. */
    @Test
    void theLoginOfOtherSourceTypesSurvivesAnEdit() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, """
                space-harvester:
                  sources:
                    - id: host-jes
                      type: jes
                      title: Host
                      auth:
                        type: basic
                        username: batch
                      options:
                        host: mvs.example.org
                """);
        SourceDraft read = editor.editable(file, "host-jes").draft();
        assertThat(read.secretVariable()).as("kein Passwort in der Datei, der Core fragt das Programm").isNull();
        SourceDraft renamed = new SourceDraft(read.id(), read.type(), "Großrechner", null, null, null,
                read.authType(), read.username(), read.secretVariable(), null, null, null, null, null, null,
                read.extraOptions());
        editor.save(file, editor.replace(file, renamed));
        assertThat(Files.readString(file)).contains("title: \"Großrechner\"", "type: basic", "username: \"batch\"",
                "host: \"mvs.example.org\"").doesNotContain("password");
    }

    /**
     * Rundreise einer JES-Quelle mit „Benutzer und Passwort“ und den Feldern ihres Typs: das Passwort nur als
     * Platzhalter, die Felder unter options, ein Schlüssel ohne Feld bleibt; ohne Passwort keine Zeile (ADR 0022).
     */
    @Test
    void aJesSourceWithBasicLoginAndItsFieldsRoundTrips() throws Exception {
        Path file = home.resolve("app.yml");
        SourceDraft jes = new SourceDraft("host-jes", "jes", "Host", null, null, null,
                SourceSettingsConfig.AuthType.BASIC, "batch", "HOST_JES_PASSWORD", null, null, null, null, null, null,
                Map.of("host", "mvs.example.org", "port", "2121", "status", "ALL", "connectTimeoutMillis", "2000"));
        assertThat(jes.problems()).isEmpty();
        editor.save(file, editor.draft(file, jes));
        assertThat(Files.readString(file)).contains("type: jes", "type: basic", "username: \"batch\"",
                "password: ${HOST_JES_PASSWORD}", "host: \"mvs.example.org\"", "port: \"2121\"", "status: \"ALL\"")
                .doesNotContain("client-certificate", "trust-bundle", "base-url");

        SourceDraft.Editable read = editor.editable(file, "host-jes");
        assertThat(read.plaintextSecrets()).isEmpty();
        assertThat(read.draft()).isEqualTo(jes);
        AppSettings settings = loader.load(file);
        assertThat(settings.sources().get(0).config().options()).containsEntry("host", "mvs.example.org")
                .containsEntry("port", "2121").containsEntry("connectTimeoutMillis", "2000");
        assertThat(settings.sources().get(0).config().auth().username()).isEqualTo("batch");

        SourceDraft without = read.draft().withSecretVariable(null);
        assertThat(without.problems()).as("andere Typen dürfen das Passwort weglassen").isEmpty();
        editor.save(file, editor.replace(file, without));
        assertThat(Files.readString(file)).contains("type: basic", "username: \"batch\"").doesNotContain("password");
        assertThat(editor.editable(file, "host-jes").draft()).isEqualTo(without);

        SourceDraft noUser = new SourceDraft("host-jes", "jes", "Host", null, null, null,
                SourceSettingsConfig.AuthType.BASIC, null, null, null, null, null, null, null, null, jes.extraOptions());
        assertThat(noUser.problems()).containsExactly("Der Benutzername fehlt.");
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
    void proxyUserAndPacScriptRoundTripButNoPasswordEverReachesTheFile() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester:\n  sources: []\n");
        String script = "$url = (Get-ItemProperty 'HKCU:\\Software\\X').AutoConfigURL\r\n\tWrite-Output \"${url}\"";
        ProxyDraft proxy = new ProxyDraft(ProxySettings.Mode.PAC_URL_POWERSHELL, null, null, null, null, null, null,
                null, " proxy-nutzer ", script);

        ConfigFileEditor.Draft draft = editor.withProxy(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                proxy);
        editor.save(file, draft);

        String text = Files.readString(file);
        assertThat(text).contains("auth:\n", "username: \"proxy-nutzer\"", "pac-discovery-script: \"")
                .doesNotContain("password");
        AppSettings settings = loader.load(file);
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.proxy().authentication().username()).isEqualTo("proxy-nutzer");
        assertThat(settings.proxy().authentication().password()).isNull();
        assertThat(settings.proxy().pacDiscoveryScript()).as("wörtlich, ${…} bleibt Teil des Skripts")
                .isEqualTo(script.replace("\r\n", "\n"));
        assertThat(ProxyDraft.of(settings.proxy()).settings()).isEqualTo(proxy.settings());

        ProxyDraft manual = new ProxyDraft(ProxySettings.Mode.MANUAL_PROXY, null, "proxy.firma.de", "3128", null,
                null, null, null, "", script);
        assertThat(manual.settings().authentication()).as("ohne Benutzer keine Anmeldung").isNull();
        assertThat(manual.settings().pacDiscoveryScript()).as("Skript nur für PowerShell/VBScript").isNull();
    }

    @Test
    void tlsSwitchesAndCaFileGoUnderNetworkNextToTheProxy() throws Exception {
        Path file = home.resolve("app.yml");
        Path ca = home.resolve("proxy-ca.pem");
        Files.writeString(ca, "-----BEGIN CERTIFICATE-----\nkein Zertifikat\n-----END CERTIFICATE-----\n");
        Files.writeString(file, "space-harvester:\n  network:\n    proxy:\n      mode: disabled\n");
        TrustSettings broken = new TrustSettings(false, true, false, ca);
        assertThatThrownBy(() -> editor.withTls(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                broken)).as("eine unbrauchbare CA-Datei kommt nicht in die Datei")
                .isInstanceOf(IllegalArgumentException.class);

        TrustSettings tls = new TrustSettings(true, true, true, null);
        editor.save(file, editor.withTls(file, new ConfigFileEditor.Draft(Files.readString(file), null), tls));

        assertThat(Files.readString(file)).contains("    proxy:\n      mode: disabled\n", "    tls:\n",
                "use-jvm-default: true", "use-windows-root: true", "use-windows-ca-stores: true");
        AppSettings settings = loader.load(file);
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.tls()).isEqualTo(tls);
        assertThat(settings.proxy().mode()).isEqualTo(ProxySettings.Mode.DISABLED);
    }

    @Test
    void theChangeLogRetentionIsWrittenOnlyWhenItChangesAndZeroMeansUnlimited() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester:\n  cache:\n    search-timeout: 5s   # bleibt\n");
        editor.save(file, editor.withCache(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                AppSettings.Cache.DEFAULT));
        assertThat(Files.readString(file)).as("Vorgabe ein Jahr: kein Schlüssel nötig").doesNotContain("change-log");

        var days = new com.aresstack.spaceharvester.application.configuration.ChangeLogSettings(Duration.ofDays(90));
        AppSettings.Cache cache = new AppSettings.Cache(Duration.ZERO, true, Duration.ofDays(30), days);
        editor.save(file, editor.withCache(file, new ConfigFileEditor.Draft(Files.readString(file), null), cache));
        assertThat(Files.readString(file)).contains("    search-timeout: 5s   # bleibt",
                "    change-log:\n      retention: 90d");
        assertThat(loader.load(file).cache().changeLog()).isEqualTo(days);

        var unlimited = com.aresstack.spaceharvester.application.configuration.ChangeLogSettings.UNLIMITED;
        editor.save(file, editor.withCache(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                new AppSettings.Cache(Duration.ZERO, true, Duration.ofDays(30), unlimited)));
        assertThat(Files.readString(file)).contains("      retention: 0").doesNotContain("90d");
        AppSettings settings = loader.load(file);
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.cache().changeLog().unlimited()).isTrue();

        var shown = days.withShowAfterUpdate(true);
        editor.save(file, editor.withCache(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                new AppSettings.Cache(Duration.ZERO, true, Duration.ofDays(30), shown)));
        assertThat(Files.readString(file)).contains("      retention: 90d\n      show-after-update: true");
        assertThat(loader.load(file).cache().changeLog()).isEqualTo(shown);
    }

    @Test
    void cacheFreshnessAndWithheldRetentionUseTheServerKeys() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester:\n  cache:\n    search-timeout: 5s   # bleibt\n");
        assertThat(loader.load(file).cache()).isEqualTo(AppSettings.Cache.DEFAULT);

        AppSettings.Cache cache = new AppSettings.Cache(Duration.ofHours(6), false, Duration.ofDays(14));
        editor.save(file, editor.withCache(file, new ConfigFileEditor.Draft(Files.readString(file), null), cache));

        assertThat(Files.readString(file)).contains("    search-timeout: 5s   # bleibt", "    max-age: 6h",
                "    version-check: false", "  sync:\n    withheld-retention: 14d");
        AppSettings settings = loader.load(file);
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.cache()).isEqualTo(cache);
        assertThat(settings.storage().searchTimeout()).isEqualTo(Duration.ofSeconds(5));

        editor.save(file, editor.withCache(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                AppSettings.Cache.DEFAULT));
        assertThat(Files.readString(file)).contains("max-age: 0s", "version-check: true", "withheld-retention: 30d")
                .doesNotContain("6h", "14d");
        Files.writeString(file, "space-harvester:\n  cache:\n    max-age: 400d\n    version-check: vielleicht\n");
        assertThat(loader.load(file).problems()).anyMatch(p -> p.contains("max-age"))
                .anyMatch(p -> p.contains("version-check"));
    }

    @Test
    void syncRulesPerSourceUseTheCoreKeysAndKeepTheOtherSources() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester:\n  sync:\n    withheld-retention: 14d   # bleibt\n");
        var wiki = new com.aresstack.spaceharvester.application.configuration.SourceSyncOverride(Duration.ofHours(1),
                Duration.ofDays(1), null, true, null,
                com.aresstack.spaceharvester.application.sync.SyncMode.SYNC_CACHE,
                com.aresstack.spaceharvester.application.configuration.RefreshStrategy.TIME_BASED_WITH_VERSION_CHECK,
                Duration.ofHours(6), null);
        var docs = new com.aresstack.spaceharvester.application.configuration.SourceSyncOverride(null, null, false,
                null, null, null, null, null, Duration.ofDays(7));
        editor.save(file, editor.withSyncRule(file, new ConfigFileEditor.Draft(Files.readString(file), null), "wiki",
                wiki));
        editor.save(file, editor.withSyncRule(file, new ConfigFileEditor.Draft(Files.readString(file), null), "docs",
                docs));

        String text = Files.readString(file);
        assertThat(text).contains("withheld-retention: 14d   # bleibt", "    sources:", "      \"wiki\":",
                "        interval: 1h", "        inventory-interval: 1d", "        catch-up-on-start: true",
                "        mode: sync-cache", "        refresh-strategy: time-based-with-version-check",
                "        max-age: 6h", "        events: false", "        withheld-retention: 7d");
        AppSettings settings = loader.load(file);
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.syncRule("wiki")).isEqualTo(wiki);
        assertThat(settings.syncRule("docs")).isEqualTo(docs);
        assertThat(settings.cache().withheldRetention()).isEqualTo(Duration.ofDays(14));
        // Die Properties liest der Core genauso.
        assertThat(com.aresstack.spaceharvester.application.configuration.SourceSyncOverride.key("wiki", "interval"))
                .isEqualTo("sync.sources.wiki.interval");

        editor.save(file, editor.withSyncRule(file, new ConfigFileEditor.Draft(Files.readString(file), null), "wiki",
                com.aresstack.spaceharvester.application.configuration.SourceSyncOverride.NONE));
        settings = loader.load(file);
        assertThat(settings.syncRules()).containsOnlyKeys("docs");
        editor.save(file, editor.withSyncRule(file, new ConfigFileEditor.Draft(Files.readString(file), null), "docs",
                com.aresstack.spaceharvester.application.configuration.SourceSyncOverride.NONE));
        assertThat(Files.readString(file)).doesNotContain("sources:").contains("withheld-retention: 14d");
        Files.writeString(file, "space-harvester:\n  sync:\n    sources:\n      wiki:\n        mode: alles\n");
        assertThat(loader.load(file).problems()).anyMatch(p -> p.contains("sync.sources.wiki.mode"));
    }

    @Test
    void theSynonymBudgetGoesUnderTermsSynonymsAndKeepsTheOtherKeys() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester:\n  terms:\n    synonyms:\n      max-terms: 300   # bleibt\n"
                + "      max-judged-pairs: 5\n  jobs:\n    max-queued: 3\n");
        var budget = new com.aresstack.spaceharvester.application.terms.SynonymBudget(300, 500, 40);
        editor.save(file, editor.withSynonymBudget(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                budget));
        editor.save(file, editor.withSynonymBudget(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                budget));

        String text = Files.readString(file);
        assertThat(text).contains("    synonyms:\n      max-terms: 300\n      max-embedded-terms: 500\n"
                + "      max-judged-pairs: 40\n", "max-queued: 3").doesNotContain("max-judged-pairs: 5");
        assertThat(text.indexOf("synonyms:")).as("zweimal gespeichert, ein Block").isEqualTo(text.lastIndexOf("synonyms:"));
        AppSettings settings = loader.load(file);
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.terms().synonyms()).isEqualTo(budget);
        assertThat(settings.jobLimits().maxQueued()).isEqualTo(3);

        Files.writeString(file, "space-harvester:\n  jobs:\n    max-queued: 3\n");
        editor.save(file, editor.withSynonymBudget(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                com.aresstack.spaceharvester.application.terms.SynonymBudget.DEFAULT.withMaxJudgedPairs(20)));
        assertThat(loader.load(file).terms().synonyms().maxJudgedPairs()).as("Block ohne terms neu angelegt")
                .isEqualTo(20);
    }

    @Test
    void theCheckStepGoesUnderStatementsCheckAndLeavesTheStageAlone() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester:\n  statements:\n    simplification:\n      mode: always\n"
                + "      model: einfach\n  jobs:\n    max-queued: 3\n");
        var all = new com.aresstack.spaceharvester.application.configuration.CheckSettings(
                com.aresstack.spaceharvester.application.check.CheckScope.ALL, 4, "pruef:1b");
        editor.save(file, editor.withCheck(file, new ConfigFileEditor.Draft(Files.readString(file), null), all));
        editor.save(file, editor.withCheck(file, new ConfigFileEditor.Draft(Files.readString(file), null), all));

        String text = Files.readString(file);
        assertThat(text).contains("    check:\n      scope: all\n      parallel: 4\n      model: \"pruef:1b\"\n",
                "      mode: always\n      model: einfach\n", "max-queued: 3");
        assertThat(text.indexOf("check:")).as("zweimal gespeichert, ein Block").isEqualTo(text.lastIndexOf("check:"));
        AppSettings settings = loader.load(file);
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.check()).isEqualTo(all);
        assertThat(settings.simplification().model()).as("Vorstufe unberührt").isEqualTo("einfach");

        Files.writeString(file, "space-harvester:\n  jobs:\n    max-queued: 3\n");
        var plain = new com.aresstack.spaceharvester.application.configuration.CheckSettings(
                com.aresstack.spaceharvester.application.check.CheckScope.UNCERTAIN_AND_STAGE, 1, null);
        editor.save(file, editor.withCheck(file, new ConfigFileEditor.Draft(Files.readString(file), null), plain));
        assertThat(Files.readString(file)).as("ohne eigenes Modell kein Schlüssel model")
                .contains("scope: uncertain-and-stage").doesNotContain("model:");
        assertThat(loader.load(file).check()).isEqualTo(plain);
    }

    @Test
    void theAnalyzerGoesUnderStatementsAndKeepsTheOtherKeys() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester:\n  statements:\n    simplification:\n      mode: always\n"
                + "      model: einfach\n  jobs:\n    max-queued: 3\n");
        var model = new com.aresstack.spaceharvester.application.configuration.AnalyzerSettings(
                com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.MODEL, "aussagen:4b");
        editor.save(file, editor.withAnalyzer(file, new ConfigFileEditor.Draft(Files.readString(file), null), model));
        editor.save(file, editor.withAnalyzer(file, new ConfigFileEditor.Draft(Files.readString(file), null), model));

        String text = Files.readString(file);
        assertThat(text).contains("    analyzer: model\n", "    analyzer-model: \"aussagen:4b\"\n",
                "      mode: always\n      model: einfach\n", "max-queued: 3");
        assertThat(text.indexOf("analyzer:")).as("zweimal gespeichert, ein Schlüssel")
                .isEqualTo(text.lastIndexOf("analyzer:"));
        AppSettings settings = loader.load(file);
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.analyzer()).isEqualTo(model);
        assertThat(settings.simplification().model()).as("Vorstufe unberührt").isEqualTo("einfach");

        var parser = com.aresstack.spaceharvester.application.configuration.AnalyzerSettings.DEFAULT;
        editor.save(file, editor.withAnalyzer(file, new ConfigFileEditor.Draft(Files.readString(file), null), parser));
        assertThat(Files.readString(file)).as("ohne eigenes Modell kein Schlüssel analyzer-model")
                .contains("analyzer: parser").doesNotContain("analyzer-model:");
        assertThat(loader.load(file).analyzer()).isEqualTo(parser);
    }

    @Test
    void theTokenLengthOfEachStepGoesNextToItsModel() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester:\n  jobs:\n    max-queued: 3\n");
        var simplification = new com.aresstack.spaceharvester.application.configuration.SimplificationSettings(
                com.aresstack.spaceharvester.application.configuration.SimplificationSettings.Mode.ALWAYS, 2,
                "einfach", 4096);
        var check = new com.aresstack.spaceharvester.application.configuration.CheckSettings(
                com.aresstack.spaceharvester.application.check.CheckScope.ALL, 2, null, 32768);
        var analyzer = new com.aresstack.spaceharvester.application.configuration.AnalyzerSettings(
                com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.MODEL, "aussagen", 16384);
        editor.save(file, editor.withSimplification(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                simplification));
        editor.save(file, editor.withCheck(file, new ConfigFileEditor.Draft(Files.readString(file), null), check));
        editor.save(file, editor.withAnalyzer(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                analyzer));

        String text = Files.readString(file);
        assertThat(text).contains("      max-tokens: 4096\n", "      max-tokens: 32768\n",
                "    analyzer-max-tokens: 16384\n");
        AppSettings settings = loader.load(file);
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.simplification()).isEqualTo(simplification);
        assertThat(settings.check()).isEqualTo(check);
        assertThat(settings.analyzer()).isEqualTo(analyzer);

        editor.save(file, editor.withAnalyzer(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                analyzer.withMaxTokens(null)));
        assertThat(Files.readString(file)).as("ohne eigene Tokenlänge kein Schlüssel")
                .doesNotContain("analyzer-max-tokens");
        Files.writeString(file, "space-harvester:\n  statements:\n    check:\n      max-tokens: viel\n");
        assertThat(loader.load(file).problems()).anyMatch(p -> p.contains("statements.check.max-tokens"));
        assertThat(loader.load(file).check().maxTokens()).isNull();
    }

    @Test
    void theAutomaticImageDescriptionGoesUnderImagesAndKeepsTheOtherKeys() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester:\n  jobs:\n    max-queued: 3\n");
        assertThat(loader.load(file).images()).as("Standard aus").isEqualTo(
                com.aresstack.spaceharvester.application.configuration.ImageSettings.DEFAULT);
        var on = new com.aresstack.spaceharvester.application.configuration.ImageSettings(true, 120);

        editor.save(file, editor.withImages(file, new ConfigFileEditor.Draft(Files.readString(file), null), on));
        editor.save(file, editor.withImages(file, new ConfigFileEditor.Draft(Files.readString(file), null), on));

        String text = Files.readString(file);
        assertThat(text).contains("automatic: true", "max-per-run: 120", "max-queued: 3");
        assertThat(text.indexOf("images:")).as("zweimal gespeichert, ein Block").isEqualTo(text.lastIndexOf("images:"));
        AppSettings settings = loader.load(file);
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.images()).isEqualTo(on);
        assertThat(settings.jobLimits().maxQueued()).isEqualTo(3);

        var unlimited = new com.aresstack.spaceharvester.application.configuration.ImageSettings(false, 0);
        editor.save(file, editor.withImages(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                unlimited));
        assertThat(loader.load(file).images()).isEqualTo(unlimited);

        Files.writeString(file, "space-harvester:\n  images:\n    max-per-run: -1\n");
        assertThat(loader.load(file).problems()).anySatisfy(problem ->
                assertThat(problem).contains("space-harvester.images.max-per-run"));
    }

    @Test
    void theSimplificationStageGoesUnderStatementsSimplificationAndKeepsTheOtherKeys() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester:\n  statements:\n    simplification:\n      mode: where-needed\n"
                + "      model: alt\n  jobs:\n    max-queued: 3\n");
        var always = new com.aresstack.spaceharvester.application.configuration.SimplificationSettings(
                com.aresstack.spaceharvester.application.configuration.SimplificationSettings.Mode.ALWAYS, 4,
                "gemma4:12b");
        editor.save(file, editor.withSimplification(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                always));
        editor.save(file, editor.withSimplification(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                always));

        String text = Files.readString(file);
        assertThat(text).contains("    simplification:\n      mode: always\n      parallel: 4\n"
                + "      model: \"gemma4:12b\"\n", "max-queued: 3").doesNotContain("model: alt");
        assertThat(text.indexOf("simplification:")).as("zweimal gespeichert, ein Block")
                .isEqualTo(text.lastIndexOf("simplification:"));
        AppSettings settings = loader.load(file);
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.simplification()).isEqualTo(always);
        assertThat(settings.jobLimits().maxQueued()).isEqualTo(3);

        Files.writeString(file, "space-harvester:\n  jobs:\n    max-queued: 3\n");
        var plain = new com.aresstack.spaceharvester.application.configuration.SimplificationSettings(
                com.aresstack.spaceharvester.application.configuration.SimplificationSettings.Mode.WHERE_NEEDED, 1,
                null);
        editor.save(file, editor.withSimplification(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                plain));
        assertThat(Files.readString(file)).as("ohne eigenes Modell kein Schlüssel model").doesNotContain("model:");
        assertThat(loader.load(file).simplification()).as("Block ohne statements neu angelegt").isEqualTo(plain);
    }

    @Test
    void retriesGoUnderJobsRetryAndKeepTheOtherJobLimits() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester:\n  jobs:\n    max-queued: 3   # bleibt\n");
        var retry = new com.aresstack.spaceharvester.application.jobs.RetrySettings(4, Duration.ofMillis(500),
                Duration.ofSeconds(20), 7, Duration.ofSeconds(15), Duration.ofMinutes(20));
        editor.save(file, editor.withRetry(file, new ConfigFileEditor.Draft(Files.readString(file), null), retry));
        editor.save(file, editor.withRetry(file, new ConfigFileEditor.Draft(Files.readString(file), null), retry));

        String text = Files.readString(file);
        assertThat(text).contains("max-queued: 3   # bleibt", "    retry:\n      max-attempts: 4\n",
                "      initial-delay: 500ms\n", "      max-delay: 20s\n", "      pause-after-failures: 7\n",
                "      resume-initial-delay: 15s\n", "      resume-max-delay: 20m\n");
        assertThat(text.indexOf("retry:")).as("zweimal gespeichert, ein Block").isEqualTo(text.lastIndexOf("retry:"));
        AppSettings settings = loader.load(file);
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.jobLimits().retry()).isEqualTo(retry);
        assertThat(settings.jobLimits().maxQueued()).isEqualTo(3);
    }

    @Test
    void downloadLimitsGoUnderJobsAndZeroDownloadsIsAllowed() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester:\n  jobs:\n    max-queued: 3   # bleibt\n");
        editor.save(file, editor.withParallel(file, new ConfigFileEditor.Draft(Files.readString(file), null), 3, 0));
        editor.save(file, editor.withParallel(file, new ConfigFileEditor.Draft(Files.readString(file), null), 3, 0));

        String text = Files.readString(file);
        assertThat(text).contains("max-queued: 3   # bleibt", "    parallel-preparations: 3\n",
                "    parallel-downloads: 0\n");
        assertThat(text.indexOf("parallel-downloads")).isEqualTo(text.lastIndexOf("parallel-downloads"));
        AppSettings settings = loader.load(file);
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.jobLimits().parallelPreparations()).isEqualTo(3);
        assertThat(settings.jobLimits().parallelDownloads()).isZero();
        assertThat(settings.jobLimits().maxQueued()).isEqualTo(3);
        assertThatThrownBy(() -> editor.withParallel(file, new ConfigFileEditor.Draft(text, null), 0, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> editor.withParallel(file, new ConfigFileEditor.Draft(text, null), 2, -1))
                .isInstanceOf(IllegalArgumentException.class);

        Files.writeString(file, "space-harvester:\n  jobs:\n    parallel-downloads: 99\n");
        AppSettings outside = loader.load(file);
        assertThat(outside.problems()).anyMatch(problem -> problem.contains("parallel-downloads"));
        assertThat(outside.jobLimits().parallelDownloads()).isEqualTo(4);
    }

    @Test
    void theConnectionBudgetIsWrittenOnceAndReadBack() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester:\n  jobs:\n    parallel-downloads: 2\n");

        editor.save(file, editor.withConnections(file, new ConfigFileEditor.Draft(Files.readString(file), null), 0));
        editor.save(file, editor.withConnections(file, new ConfigFileEditor.Draft(Files.readString(file), null), 16));

        String text = Files.readString(file);
        assertThat(text).contains("    download-connections: 16\n", "    parallel-downloads: 2\n");
        assertThat(text.indexOf("download-connections")).isEqualTo(text.lastIndexOf("download-connections"));
        assertThat(loader.load(file).jobLimits().downloadConnections()).isEqualTo(16);
        assertThatThrownBy(() -> editor.withConnections(file, new ConfigFileEditor.Draft(text, null), 65))
                .isInstanceOf(IllegalArgumentException.class);
        Files.writeString(file, "space-harvester:\n  jobs:\n    download-connections: 0\n");
        assertThat(loader.load(file).jobLimits().downloadConnections()).isZero();
    }

    @Test
    void theConsoleOutputUsesTheServiceKeyAndLeavesTheOtherLoggingKeysAlone() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester:\n  sources: []\n");
        assertThat(loader.load(file).consoleOutput()).as("ohne Angabe still").isEqualTo(ConsoleOutput.OFF);

        editor.save(file, editor.withConsoleOutput(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                ConsoleOutput.WARN));
        assertThat(Files.readString(file)).startsWith("space-harvester:\n  sources: []\n")
                .endsWith("logging:\n  threshold:\n    console: \"warn\"\n");
        assertThat(loader.load(file).consoleOutput()).isEqualTo(ConsoleOutput.WARN);

        // Spring-Boot-Schlüssel daneben (Stufen, Datei-Schwelle) und Kommentare bleiben; erneut setzen ersetzt die Zeile.
        Files.writeString(file, """
                logging:
                  level:
                    root: INFO   # Kommentar bleibt
                  threshold:
                    file: warn
                    console: info
                space-harvester:
                  sources: []
                """);
        editor.save(file, editor.withConsoleOutput(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                ConsoleOutput.OFF));
        String text = Files.readString(file);
        assertThat(text).contains("    root: INFO   # Kommentar bleibt\n", "    file: warn\n", "    console: \"off\"\n")
                .doesNotContain("console: info");
        assertThat(text.split("console:", -1)).hasSize(2);
        assertThat(loader.load(file).consoleOutput()).isEqualTo(ConsoleOutput.OFF);
        for (ConsoleOutput output : ConsoleOutput.values()) {
            String written = editor.withConsoleOutput(file, new ConfigFileEditor.Draft(text, null), output).text();
            assertThat(loader.read(file, written).consoleOutput()).isEqualTo(output);
        }
    }

    @Test
    void aConsoleOutputThatWouldBreakTheFileIsRefusedEvenWhenTheFallbackMatches() throws Exception {
        // Eine Wurzel in Anführungszeichen erkennt der Editor nicht und hängt eine zweite an; das doppelte „logging“
        // ist kein gültiges YAML mehr. Der Loader fiele auf die Vorgaben zurück, und deren Terminal-Ausgabe ist „Aus“:
        // Gerade diese Wahl darf die Datei nicht zerstören.
        Path file = home.resolve("app.yml");
        String original = """
                'logging':
                  threshold:
                    console: info
                space-harvester:
                  sources: []
                """;
        Files.writeString(file, original);
        assertThat(loader.load(file).consoleOutput()).isEqualTo(ConsoleOutput.INFO);
        for (ConsoleOutput output : ConsoleOutput.values()) {
            assertThatThrownBy(() -> editor.withConsoleOutput(file, new ConfigFileEditor.Draft(original, null), output))
                    .as(output.name())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(ConsoleOutput.KEY);
        }
        assertThat(Files.readString(file)).isEqualTo(original);
    }

    /** Angelo 17.11.2026: der Hintergrund des Bildbetrachters steht nur in der Datei, wenn er von der Vorgabe abweicht. */
    @Test
    void theViewerBackgroundIsWrittenUnderTheAppRootAndRemovedForTheDefault() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester-app:\n  output-directory: D:\\out   # bleibt\nspace-harvester:\n"
                + "  sources: []\n");
        editor.save(file, editor.withViewerBackground(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                ViewerBackground.BLACK));
        String text = Files.readString(file);
        assertThat(text).contains("  output-directory: D:\\out   # bleibt\n", "  viewer:\n    background: black\n");
        assertThat(loader.load(file).viewerBackground()).isEqualTo(ViewerBackground.BLACK);

        editor.save(file, editor.withViewerBackground(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                ViewerBackground.GREY));
        text = Files.readString(file);
        assertThat(text).contains("  viewer:\n    background: grey\n").doesNotContain("black");
        assertThat(text.split("viewer:", -1)).hasSize(2);

        editor.save(file, editor.withViewerBackground(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                ViewerBackground.THEME));
        text = Files.readString(file);
        assertThat(text).doesNotContain("viewer").contains("  output-directory: D:\\out   # bleibt\n", "sources: []");
        assertThat(loader.load(file).viewerBackground()).isEqualTo(ViewerBackground.THEME);
    }

    /** Angelo 27.11.2026: die Grenze großer lokaler Ordner ist einstellbar, 0 heißt nie nachfragen. */
    @Test
    void theLargeFolderLimitIsWrittenUnderTheAppRootAndRemovedForTheDefault() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester-app:\n  output-directory: D:\\out   # bleibt\nspace-harvester:\n"
                + "  sources: []\n");
        assertThat(loader.load(file).storage().largeFolderBytes())
                .isEqualTo(AppSettings.Storage.DEFAULT_LARGE_FOLDER_BYTES);

        editor.save(file, editor.withLargeFolder(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                2L << 30));
        assertThat(Files.readString(file)).contains("  large-folder-size: 2GB\n", "# bleibt");
        assertThat(loader.load(file).storage().largeFolderBytes()).isEqualTo(2L << 30);

        editor.save(file, editor.withLargeFolder(file, new ConfigFileEditor.Draft(Files.readString(file), null), 0));
        assertThat(loader.load(file).storage().largeFolderBytes()).isZero();

        editor.save(file, editor.withLargeFolder(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                AppSettings.Storage.DEFAULT_LARGE_FOLDER_BYTES));
        assertThat(Files.readString(file)).doesNotContain("large-folder-size").contains("sources: []");
    }

    @Test
    void theWhisperModelIsWrittenUnderTheAppRootAndRemovedForTheDefault() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester-app:\n  output-directory: D:\\out   # bleibt\nspace-harvester:\n"
                + "  sources: []\n");
        assertThat(loader.load(file).whisperModel())
                .isEqualTo(com.aresstack.spaceharvester.app.transcription.WhisperModel.TINY);

        editor.save(file, editor.withWhisperModel(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                com.aresstack.spaceharvester.app.transcription.WhisperModel.LARGE_V3_TURBO));
        String text = Files.readString(file);
        assertThat(text).contains("  output-directory: D:\\out   # bleibt\n", "  whisper:\n    model: large-v3-turbo\n");
        assertThat(loader.load(file).whisperModel())
                .isEqualTo(com.aresstack.spaceharvester.app.transcription.WhisperModel.LARGE_V3_TURBO);

        editor.save(file, editor.withWhisperModel(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                com.aresstack.spaceharvester.app.transcription.WhisperModel.TINY));
        text = Files.readString(file);
        assertThat(text).doesNotContain("whisper").contains("  output-directory: D:\\out   # bleibt\n", "sources: []");

        Files.writeString(file, "space-harvester-app:\n  whisper:\n    model: riesig\nspace-harvester:\n  sources: []\n");
        AppSettings unknown = loader.load(file);
        assertThat(unknown.whisperModel()).isEqualTo(com.aresstack.spaceharvester.app.transcription.WhisperModel.TINY);
        assertThat(unknown.problems()).anyMatch(problem -> problem.contains("space-harvester-app.whisper.model"));
    }

    /** Angelo 21.11.2026: die Spracheingabe hat ihr eigenes Whisper-Modell, unabhängig von dem der Transkripte. */
    @Test
    void theDictationModelIsWrittenBesideTheTranscriptModelAndBothStayIndependent() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester-app:\n  output-directory: D:\\out   # bleibt\nspace-harvester:\n"
                + "  sources: []\n");
        assertThat(loader.load(file).dictationModel())
                .isEqualTo(com.aresstack.spaceharvester.app.transcription.WhisperModel.TINY);

        editor.save(file, editor.withWhisperModel(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                com.aresstack.spaceharvester.app.transcription.WhisperModel.SMALL));
        editor.save(file, editor.withDictationModel(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                com.aresstack.spaceharvester.app.transcription.WhisperModel.BASE));
        String text = Files.readString(file);
        assertThat(text).contains("  whisper:\n    model: small\n    dictation-model: base\n");
        AppSettings both = loader.load(file);
        assertThat(both.whisperModel()).isEqualTo(com.aresstack.spaceharvester.app.transcription.WhisperModel.SMALL);
        assertThat(both.dictationModel()).isEqualTo(com.aresstack.spaceharvester.app.transcription.WhisperModel.BASE);

        editor.save(file, editor.withWhisperModel(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                com.aresstack.spaceharvester.app.transcription.WhisperModel.TINY));
        text = Files.readString(file);
        assertThat(text).as("Tiny für Transkripte, die Spracheingabe bleibt").doesNotContain("model: tiny")
                .contains("  whisper:\n    dictation-model: base\n");
        assertThat(loader.load(file).dictationModel())
                .isEqualTo(com.aresstack.spaceharvester.app.transcription.WhisperModel.BASE);

        editor.save(file, editor.withDictationModel(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                com.aresstack.spaceharvester.app.transcription.WhisperModel.TINY));
        assertThat(Files.readString(file)).doesNotContain("whisper");

        Files.writeString(file, "space-harvester-app:\n  whisper:\n    dictation-model: riesig\nspace-harvester:\n"
                + "  sources: []\n");
        AppSettings unknown = loader.load(file);
        assertThat(unknown.dictationModel())
                .isEqualTo(com.aresstack.spaceharvester.app.transcription.WhisperModel.TINY);
        assertThat(unknown.problems())
                .anyMatch(problem -> problem.contains("space-harvester-app.whisper.dictation-model"));
    }

    @Test
    void theAnalyticsExportIsWrittenAsABlockUnderTheAppRootAndRemovedWhenAllIsDefault() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester-app:\n  output-directory: D:\\out   # bleibt\nspace-harvester:\n"
                + "  sources: []\n");
        AnalyticsSettings custom = new AnalyticsSettings(Duration.ofDays(90),
                "/rest/confanalytics/1.0/space/details?spaceKey={spaceKey}", null,
                "/rest/confanalytics/2.0/space/paginated/contentViews?spaceKey={spaceKey}&from={from}");
        editor.save(file, editor.withAnalytics(file, new ConfigFileEditor.Draft(Files.readString(file), null), custom));
        String text = Files.readString(file);
        assertThat(text).contains("  output-directory: D:\\out   # bleibt\n", "  analytics:\n    period: 90d\n"
                + "    details: \"/rest/confanalytics/1.0/space/details?spaceKey={spaceKey}\"\n"
                + "    content-views: \"/rest/confanalytics/2.0/space/paginated/contentViews?spaceKey={spaceKey}"
                + "&from={from}\"\n").doesNotContain("activity-by-date");
        assertThat(loader.load(file).analytics()).isEqualTo(custom);

        // Erneut setzen ersetzt den Block; nur noch der Zeitraum weicht ab.
        AnalyticsSettings period = new AnalyticsSettings(Duration.ofDays(30), null, null, null);
        editor.save(file, editor.withAnalytics(file, new ConfigFileEditor.Draft(Files.readString(file), null), period));
        text = Files.readString(file);
        assertThat(text).contains("  analytics:\n    period: 30d\n").doesNotContain("details", "content-views");
        assertThat(text.split("analytics:", -1)).hasSize(2);
        assertThat(loader.load(file).analytics()).isEqualTo(period);

        // Alles Vorgabe: der Block verschwindet, der Rest bleibt.
        editor.save(file, editor.withAnalytics(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                AnalyticsSettings.DEFAULT));
        text = Files.readString(file);
        assertThat(text).doesNotContain("analytics").contains("  output-directory: D:\\out   # bleibt\n",
                "space-harvester:\n  sources: []\n");
        assertThat(loader.load(file).analytics()).isEqualTo(AnalyticsSettings.DEFAULT);

        // Ohne App-Wurzel entsteht sie oben in der Datei.
        Files.writeString(file, "space-harvester:\n  sources: []\n");
        editor.save(file, editor.withAnalytics(file, new ConfigFileEditor.Draft(Files.readString(file), null), period));
        assertThat(Files.readString(file)).startsWith("space-harvester-app:\n  analytics:\n    period: 30d\n\n"
                + "space-harvester:\n");
        assertThat(loader.load(file).analytics()).isEqualTo(period);
    }

    @Test
    void aCommentedRetryHeaderIsReplacedAsAWholeBlock() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester:\n  jobs:\n    retry: # eigene Werte\n      max-attempts: 5\n"
                + "      initial-delay: 2s\n");
        editor.save(file, editor.withRetry(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                com.aresstack.spaceharvester.application.jobs.RetrySettings.DEFAULT));

        String text = Files.readString(file);
        assertThat(text).doesNotContain("max-attempts: 5", "initial-delay: 2s").contains("max-attempts: 2");
        assertThat(text.indexOf("max-attempts")).isEqualTo(text.lastIndexOf("max-attempts"));
        assertThat(loader.load(file).problems()).isEmpty();
    }

    @Test
    void theSharedSyncRulesUseTheCoreKeysAndDropAnIntervalThatIsOff() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester:\n  sync:\n    interval: 2h   # alt\n    withheld-retention: 14d\n");
        var sync = new com.aresstack.spaceharvester.application.configuration.SyncSettings(true, null, java.util.List.of(),
                true, Duration.ofDays(14), true, com.aresstack.spaceharvester.application.sync.SyncMode.FULL,
                Duration.ofDays(1), Map.of());
        editor.save(file, editor.withSync(file, new ConfigFileEditor.Draft(Files.readString(file), null), sync));

        String text = Files.readString(file);
        assertThat(text).contains("    withheld-retention: 14d", "    inventory-interval: 1d", "    mode: full",
                "    catch-up-on-start: true", "    before-search: true", "    events: true")
                .doesNotContain("interval: 2h");
        AppSettings settings = loader.load(file);
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.sync().interval()).as("aus = Schlüssel weg").isNull();
        assertThat(settings.sync().inventoryInterval()).isEqualTo(Duration.ofDays(1));
        assertThat(settings.sync().mode()).isEqualTo(com.aresstack.spaceharvester.application.sync.SyncMode.FULL);
        assertThat(settings.sync().catchUpOnStart()).isTrue();
        assertThat(settings.sync().beforeSearch()).isTrue();
        assertThat(settings.sync().events()).isTrue();
    }

    @Test
    void theLargestFileInTheCacheIsWrittenInMegabytesAndReadBack() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester:\n  cache:\n    max-age: 6h   # bleibt\n");
        assertThat(loader.load(file).storage().maxPageBytes()).as("Vorgabe")
                .isEqualTo(AppSettings.Storage.DEFAULT_MAX_PAGE_BYTES);

        editor.save(file, editor.withMaxPageSize(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                100L << 20));

        assertThat(Files.readString(file)).contains("max-age: 6h   # bleibt", "    max-page-size: 100MB");
        AppSettings settings = loader.load(file);
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.storage().maxPageBytes()).isEqualTo(100L << 20);
        editor.save(file, editor.withLargeFiles(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                100L << 20, 0, java.time.Duration.ofMinutes(1), java.time.Duration.ofMinutes(15)));
        AppSettings timed = loader.load(file);
        assertThat(timed.problems()).isEmpty();
        assertThat(timed.storage().maxTextFileBytes()).as("Textauszug abgeschaltet").isZero();
        assertThat(timed.storage().checkTimeout()).isEqualTo(java.time.Duration.ofMinutes(1));
        assertThat(timed.storage().largeFileTimeout()).isEqualTo(java.time.Duration.ofMinutes(15));
        Files.writeString(file, "space-harvester:\n  cache:\n    max-page-size: 4GB\n");
        assertThat(loader.load(file).problems()).anyMatch(problem -> problem.contains("max-page-size"));
    }

    @Test
    void theBackgroundFillUsesTheCoreKeysGloballyAndPerSource() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester:\n  sync:\n    withheld-retention: 14d   # bleibt\n");
        editor.save(file, editor.withBackground(file, new ConfigFileEditor.Draft(Files.readString(file), null), false,
                5));
        var wiki = new com.aresstack.spaceharvester.application.configuration.SourceSyncOverride(null, null, null, null,
                null, null, null, null, null, true);
        editor.save(file, editor.withSyncRule(file, new ConfigFileEditor.Draft(Files.readString(file), null), "wiki",
                wiki));

        assertThat(Files.readString(file)).contains("withheld-retention: 14d   # bleibt", "    background-fill: false",
                "    background-parallelism: 5", "        background-fill: true");
        AppSettings settings = loader.load(file);
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.sync().backgroundFill()).isFalse();
        assertThat(settings.sync().backgroundParallelism()).isEqualTo(5);
        assertThat(settings.effectiveSync("wiki").backgroundFill()).as("eigene Regel").isTrue();
        assertThat(settings.effectiveSync("docs").backgroundFill()).as("wie global").isFalse();
    }

    @Test
    void theOutputLimitOfTheChatModelGoesIntoTheAiBlock() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester:\n  sources: []\n");
        AiDraft ai = new AiDraft("https://ki.firma.de/v1", "gpt-oss-120b", null, null, null, java.util.List.of())
                .withChatMaxTokens("16384");

        editor.save(file, editor.withAi(file, new ConfigFileEditor.Draft(Files.readString(file), null), ai));

        assertThat(Files.readString(file)).contains("chat-max-tokens: 16384");
        AppSettings settings = loader.load(file);
        assertThat(settings.ai().chatMaxTokens()).isEqualTo(16384);
        assertThat(AiDraft.of(settings.ai())).isEqualTo(ai);
        assertThat(new AiDraft("https://ki.firma.de/v1", "gpt-oss-120b", null, null, null, java.util.List.of())
                .yaml("")).doesNotContain("chat-max-tokens");
        assertThat(ai.withChatMaxTokens("0").problems()).anyMatch(p -> p.contains("Ausgabelimit"));
        assertThat(ai.withChatMaxTokens("viel").problems()).anyMatch(p -> p.contains("Ausgabelimit"));
    }

    @Test
    void theTokenLengthOfVisionAndImageGenerationGoIntoTheAiBlock() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester:\n  sources: []\n");
        AiDraft ai = new AiDraft("https://ki.firma.de/v1", "text-modell", null, null, null, java.util.List.of())
                .withVisionModel("bild-modell").withVisionMaxTokens("2048")
                .withImageGeneration("svg-modell", "65536");

        editor.save(file, editor.withAi(file, new ConfigFileEditor.Draft(Files.readString(file), null), ai));

        assertThat(Files.readString(file)).contains("vision-max-tokens: 2048", "image-model: \"svg-modell\"",
                "image-max-tokens: 65536");
        AppSettings settings = loader.load(file);
        assertThat(settings.ai().visionMaxTokens()).isEqualTo(2048);
        assertThat(settings.ai().imageModel()).isEqualTo("svg-modell");
        assertThat(settings.ai().imageMaxTokens()).isEqualTo(65536);
        assertThat(AiDraft.of(settings.ai())).isEqualTo(ai);
        assertThat(ai.withImageGeneration(null, "viel").problems()).anyMatch(p -> p.contains("Bildgenerierung"));
        assertThat(ai.withVisionMaxTokens("0").problems()).anyMatch(p -> p.contains("Vision-Modells"));
    }

    @Test
    void theThinkingOfEachModelGoesNextToItsTokenLength() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester:\n  sources: []\n");
        AiDraft ai = new AiDraft("https://ki.firma.de/v1", "text-modell", null, null, null, java.util.List.of())
                .withVisionModel("bild-modell").withThinking("high", "low");
        var check = new com.aresstack.spaceharvester.application.configuration.CheckSettings(
                com.aresstack.spaceharvester.application.check.CheckScope.ALL, 2, null, null, "medium");
        var simplification = new com.aresstack.spaceharvester.application.configuration.SimplificationSettings(
                com.aresstack.spaceharvester.application.configuration.SimplificationSettings.Mode.ALWAYS, 2, null,
                null, "none");
        var analyzer = new com.aresstack.spaceharvester.application.configuration.AnalyzerSettings(
                com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.MODEL, null, null, "high");

        editor.save(file, editor.withAi(file, new ConfigFileEditor.Draft(Files.readString(file), null), ai));
        editor.save(file, editor.withCheck(file, new ConfigFileEditor.Draft(Files.readString(file), null), check));
        editor.save(file, editor.withSimplification(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                simplification));
        editor.save(file, editor.withAnalyzer(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                analyzer));

        String text = Files.readString(file);
        assertThat(text).contains("vision-thinking: high", "image-thinking: low", "      thinking: medium\n",
                "      thinking: none\n", "    analyzer-thinking: high\n");
        AppSettings settings = loader.load(file);
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.ai().visionThinking()).isEqualTo("high");
        assertThat(settings.ai().imageThinking()).isEqualTo("low");
        assertThat(settings.check()).isEqualTo(check);
        assertThat(settings.simplification()).isEqualTo(simplification);
        assertThat(settings.analyzer()).isEqualTo(analyzer);
        assertThat(ai.withThinking("gründlich", null).problems()).anyMatch(p -> p.contains("Vision-Modells"));
        Files.writeString(file, "space-harvester:\n  statements:\n    check:\n      thinking: gründlich\n");
        assertThat(loader.load(file).problems()).anyMatch(p -> p.contains("statements.check.thinking"));
        assertThat(loader.load(file).check().thinking()).isNull();
    }

    @Test
    void summaryPlanAndPodcastHaveTheirOwnLinesInTheAiBlock() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester:\n  sources: []\n");
        AiDraft ai = new AiDraft("https://ki.firma.de/v1", "text-modell", null, null, null, java.util.List.of())
                .withUses(new AiDraft.Use(null, "65536", "low"), new AiDraft.Use("plan-modell", null, "high"),
                        new AiDraft.Use("podcast-modell", "8192", null))
                .withImagePlan(new AiDraft.Use("schaubild-plan", "32768", "none"))
                .withDeadline(new AiDraft.Use("fristen-modell", "4096", "low"))
                .withDiagnosis(false, new AiDraft.Use("diagnose-modell", "2048", "high"))
                .withTimeouts(new com.aresstack.spaceharvester.ai.AiServiceSettings.Timeouts(
                        java.time.Duration.ofMinutes(7), null, java.time.Duration.ofSeconds(120)));

        editor.save(file, editor.withAi(file, new ConfigFileEditor.Draft(Files.readString(file), null), ai));

        assertThat(Files.readString(file)).contains("diagnosis-enabled: false", "summary-timeout: 420000ms",
                "expansion-timeout: 120000ms",
                "diagnosis-model: \"diagnose-modell\"", "diagnosis-max-tokens: 2048", "diagnosis-thinking: high","summary-max-tokens: 65536", "summary-thinking: low",
                "summary-plan-model: \"plan-modell\"", "summary-plan-thinking: high",
                "podcast-model: \"podcast-modell\"", "podcast-max-tokens: 8192",
                "image-plan-model: \"schaubild-plan\"", "image-plan-max-tokens: 32768", "image-plan-thinking: none",
                "deadline-model: \"fristen-modell\"", "deadline-max-tokens: 4096", "deadline-thinking: low")
                .doesNotContain("summary-model:");
        AppSettings settings = loader.load(file);
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.ai().summary()).isEqualTo(
                new com.aresstack.spaceharvester.ai.AiServiceSettings.ModelUse(null, 65536, "low"));
        assertThat(settings.ai().summaryPlan().model()).isEqualTo("plan-modell");
        assertThat(settings.ai().podcast().maxTokens()).isEqualTo(8192);
        assertThat(settings.ai().imagePlan()).isEqualTo(
                new com.aresstack.spaceharvester.ai.AiServiceSettings.ModelUse("schaubild-plan", 32768, "none"));
        assertThat(settings.ai().deadline()).isEqualTo(
                new com.aresstack.spaceharvester.ai.AiServiceSettings.ModelUse("fristen-modell", 4096, "low"));
        assertThat(settings.ai().diagnosis().on()).isFalse();
        assertThat(settings.ai().timeouts().summaryTimeout()).isEqualTo(java.time.Duration.ofMinutes(7));
        assertThat(settings.ai().timeouts().labelTimeout()).as("leer: Vorgabe")
                .isEqualTo(com.aresstack.spaceharvester.ai.AiServiceSettings.Timeouts.DEFAULT_LABEL);
        assertThat(settings.ai().diagnosis().model()).isEqualTo("diagnose-modell");
        assertThat(AiDraft.of(settings.ai())).isEqualTo(ai);
        assertThat(ai.withDiagnosis(true, AiDraft.Use.EMPTY).yaml("")).doesNotContain("diagnosis-");
        assertThat(ai.withUses(new AiDraft.Use(null, "viel", null), null, null).problems())
                .anyMatch(p -> p.contains("„Zusammenfassung“"));
        assertThat(ai.withUses(null, null, new AiDraft.Use(null, null, "gründlich")).problems())
                .anyMatch(p -> p.contains("„Podcast“"));
    }

    @Test
    void rerankModelAndChunkingGoIntoTheAiBlock() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester:\n  sources: []\n");
        AiDraft ai = new AiDraft("https://ki.firma.de/v1", null, "text-embedding-3-small", "1536", null,
                java.util.List.of(), "bge-reranker", "500", "2");

        editor.save(file, editor.withAi(file, new ConfigFileEditor.Draft(Files.readString(file), null), ai));

        assertThat(Files.readString(file)).contains("rerank-model: \"bge-reranker\"", "embedding-chunk-tokens: 500",
                "embedding-chunk-overlap-sentences: 2");
        AppSettings settings = loader.load(file);
        assertThat(settings.ai().rerankModel()).isEqualTo("bge-reranker");
        assertThat(settings.ai().chunkTokens()).isEqualTo(500);
        assertThat(settings.ai().chunkOverlapSentences()).isEqualTo(2);
        assertThat(AiDraft.of(settings.ai())).isEqualTo(ai);
        assertThat(new AiDraft("https://x", null, null, null, null, null, null, "4", "-1").problems())
                .anyMatch(p -> p.contains("ab 8")).anyMatch(p -> p.contains("Überlappung"));
    }

    @Test
    void theRequestsAtOnceGoIntoTheAiBlockAndBackIntoTheDraft() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester:\n  sources: []\n");
        AiDraft ai = new AiDraft("http://localhost:11434", "gemma4:12b", null, null, null, java.util.List.of(), null,
                null, null, "gemma4:12b").withProvider(com.aresstack.spaceharvester.ai.AiProvider.OLLAMA)
                .withParallel("6");

        editor.save(file, editor.withAi(file, new ConfigFileEditor.Draft(Files.readString(file), null), ai));

        assertThat(Files.readString(file)).contains("parallel: 6");
        AppSettings settings = loader.load(file);
        assertThat(settings.ai().parallel()).isEqualTo(6);
        assertThat(AiDraft.of(settings.ai()).parallelCount()).isEqualTo(6);
        assertThat(ai.withParallel("0").problems()).anyMatch(p -> p.contains("gleichzeitigen Anfragen"));
        assertThat(ai.withParallel("33").problems()).anyMatch(p -> p.contains("gleichzeitigen Anfragen"));
        assertThat(ai.withParallel(null).problems()).isEmpty();
    }

    @Test
    void theVisionModelGoesIntoTheAiBlockAndSurvivesOtherModelChanges() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester:\n  sources: []\n");
        AiDraft ai = new AiDraft("https://ki.firma.de/v1", "gpt-x", null, null, null, java.util.List.of(), null,
                null, null, "gpt-vision");

        editor.save(file, editor.withAi(file, new ConfigFileEditor.Draft(Files.readString(file), null), ai));

        assertThat(Files.readString(file)).contains("vision-model: \"gpt-vision\"");
        AppSettings settings = loader.load(file);
        assertThat(settings.ai().visionModel()).isEqualTo("gpt-vision");
        assertThat(AiDraft.of(settings.ai())).isEqualTo(ai);
        assertThat(ai.withModels("gpt-y", null, null, null, null, null).visionModel()).isEqualTo("gpt-vision");
        assertThat(new AiDraft(null, null, null, null, null, null, null, null, null, "gpt-vision").problems())
                .anyMatch(p -> p.contains("Basisadresse"));
    }

    @Test
    void anOllamaServerGoesIntoTheAiBlockAsProvider() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, "space-harvester:\n  sources: []\n");
        AiDraft ai = new AiDraft("http://localhost:11434", "gemma4:12b", "bge-m3:latest", "1024", null,
                java.util.List.of(), null, null, null, "gemma4:12b")
                .withProvider(com.aresstack.spaceharvester.ai.AiProvider.OLLAMA).withContextTokens("16384");

        editor.save(file, editor.withAi(file, new ConfigFileEditor.Draft(Files.readString(file), null), ai));

        assertThat(Files.readString(file)).contains("provider: \"ollama\"", "base-url: \"http://localhost:11434\"",
                "context-tokens: 16384");
        AppSettings settings = loader.load(file);
        assertThat(settings.ai().provider()).isEqualTo(com.aresstack.spaceharvester.ai.AiProvider.OLLAMA);
        assertThat(settings.ai().contextTokens()).isEqualTo(16384);
        assertThat(ai.withContextTokens("100").problems()).anyMatch(p -> p.contains("Kontextlänge"));
        assertThat(AiDraft.of(settings.ai())).isEqualTo(ai);
        assertThat(ai.withProvider(null).yaml("")).as("OpenAI-kompatibel ist die Vorgabe").doesNotContain("provider");
        assertThat(new AiDraft(null, null, null, null, null, null)
                .withProvider(com.aresstack.spaceharvester.ai.AiProvider.OLLAMA).problems())
                .anyMatch(p -> p.contains("Basisadresse"));
        Files.writeString(file, """
                space-harvester:
                  ai:
                    provider: lmstudio
                    base-url: "http://x:1"
                """);
        AppSettings unknown = loader.load(file);
        assertThat(unknown.problems()).anyMatch(p -> p.contains("provider"));
        assertThat(unknown.ai().provider()).isEqualTo(com.aresstack.spaceharvester.ai.AiProvider.OPENAI_COMPATIBLE);
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
    void fileAccessUsesTheCoreKeysAsAListAndLeavesTheRestAlone() throws Exception {
        Path file = home.resolve("app.yml");
        String before = """
                # Kopf bleibt
                space-harvester-app:
                  data-directory: /daten   # Kommentar bleibt
                space-harvester:
                  local-files:
                    roots: "/alt/eins;/alt/zwei"   # wird ersetzt
                  sources: []
                """;
        Files.writeString(file, before);
        assertThat(loader.load(file).localFiles().roots()).containsExactly(Path.of("/alt/eins"), Path.of("/alt/zwei"));
        String unc = home.resolve("Team \"Wissen\"").toString();

        editor.save(file, editor.withFileAccess(file, new ConfigFileEditor.Draft(Files.readString(file), null), true,
                java.util.List.of("/srv/handbuecher", unc)));

        String text = Files.readString(file);
        assertThat(text).startsWith("# Kopf bleibt").contains("data-directory: /daten   # Kommentar bleibt",
                "    allow-all: true", "    roots:\n      - \"/srv/handbuecher\"", "  sources: []")
                .doesNotContain("/alt/eins", "wird ersetzt");
        AppSettings settings = loader.load(file);
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.localFiles().allowAll()).isTrue();
        assertThat(settings.localFiles().roots()).containsExactly(Path.of("/srv/handbuecher"), Path.of(unc));
        assertThat(settings.storage().dataDirectory()).isEqualTo(Path.of("/daten"));

        // Erneut setzen ersetzt beide Werte; ohne Ordner bleibt eine leere Liste, kein zweiter Block.
        editor.save(file, editor.withFileAccess(file, new ConfigFileEditor.Draft(Files.readString(file), null), false,
                java.util.List.of()));
        String cleared = Files.readString(file);
        assertThat(cleared.split("local-files", -1)).hasSize(2);
        assertThat(cleared).contains("    allow-all: false", "    roots: []").doesNotContain("handbuecher");
        assertThat(loader.load(file).localFiles())
                .isEqualTo(com.aresstack.spaceharvester.application.configuration.LocalFileSettings.DEFAULT);
        assertThatThrownBy(() -> editor.withFileAccess(file, new ConfigFileEditor.Draft(cleared, null), false,
                java.util.List.of("relativ/ordner"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute");

        // Abgeschaltete Ordner stehen unter space-harvester-app.inactive-roots; leer entfernt den Schlüssel wieder.
        editor.save(file, editor.withFileAccess(file, new ConfigFileEditor.Draft(Files.readString(file), null), false,
                java.util.List.of("/srv/handbuecher"), java.util.List.of(unc)));
        AppSettings paused = loader.load(file);
        assertThat(paused.problems()).isEmpty();
        assertThat(paused.localFiles().roots()).containsExactly(Path.of("/srv/handbuecher"));
        assertThat(paused.inactiveRoots()).containsExactly(Path.of(unc));
        assertThat(Files.readString(file)).contains("  inactive-roots: [\"" + unc.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"]");
        editor.save(file, editor.withFileAccess(file, new ConfigFileEditor.Draft(Files.readString(file), null), false,
                java.util.List.of("/srv/handbuecher"), java.util.List.of()));
        assertThat(Files.readString(file)).doesNotContain("inactive-roots");
        assertThat(loader.load(file).inactiveRoots()).isEmpty();
    }

    @Test
    void aRelativeRootIsReportedWithoutThePathAndGrantsNothing() {
        AppSettings settings = loader.read(home.resolve("app.yml"), """
                space-harvester:
                  local-files:
                    allow-all: vielleicht
                    roots:
                      - geheim/relativ
                      - /srv/doku
                """);
        assertThat(settings.localFiles().allowAll()).isFalse();
        assertThat(settings.localFiles().roots()).containsExactly(Path.of("/srv/doku"));
        assertThat(String.join(" ", settings.problems())).contains("space-harvester.local-files.roots",
                "space-harvester.local-files.allow-all").doesNotContain("geheim");
    }

    @Test
    void disabledSourcesAreAListInTheAppBlockAndVanishWhenAllAreOn() throws Exception {
        Path file = home.resolve("app.yml");
        Files.writeString(file, """
                space-harvester-app:
                  data-directory: /daten
                space-harvester:
                  sources: []
                """);
        assertThat(loader.load(file).disabledSources()).isEmpty();

        editor.save(file, editor.withDisabledSources(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                java.util.Set.of("wiki", "handbuch")));

        assertThat(Files.readString(file)).contains("  disabled-sources: [\"handbuch\", \"wiki\"]", "data-directory");
        assertThat(loader.load(file).disabledSources()).containsExactlyInAnyOrder("wiki", "handbuch");
        assertThat(loader.load(file).disabled("wiki")).isTrue();
        editor.save(file, editor.withDisabledSources(file, new ConfigFileEditor.Draft(Files.readString(file), null),
                java.util.Set.of()));
        assertThat(Files.readString(file)).doesNotContain("disabled-sources").contains("data-directory: /daten");
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
