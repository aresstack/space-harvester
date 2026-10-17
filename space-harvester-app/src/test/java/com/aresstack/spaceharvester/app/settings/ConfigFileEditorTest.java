package com.aresstack.spaceharvester.app.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
                        "mode: disabled", "thumbprint: \"AB:CD\"");
        assertThat(home.resolve("app.yml.bak")).hasContent(example);
        AppSettings settings = loader.load(file);
        assertThat(settings.sources()).extracting(AppSettings.SourceEntry::id).containsExactly("confluence", "wiki");
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
                null, null, null);
        assertThat(basic.problems()).isEmpty();
        assertThat(basic.yaml("")).contains("password: ${INTERN_PASSWORD}", "password: ${INTERN_CLIENT_PASSWORD}",
                "username: \"svc-harvester\"");
        assertThat(SourceDraft.suggestedVariable("mein-wiki", SourceSettingsConfig.AuthType.PAT))
                .isEqualTo("MEIN_WIKI_TOKEN");
        assertThat(new SourceDraft("w", "confluence", null, "https://w", null, null, SourceSettingsConfig.AuthType.PAT,
                null, "kein gültiger name", null, null, null, null, null, null).problems())
                .anySatisfy(p -> assertThat(p).contains("Umgebungsvariable"));
    }

    private static SourceDraft wiki() {
        return new SourceDraft("wiki", "confluence", "Wiki", "https://wiki.example.org", null, null,
                SourceSettingsConfig.AuthType.PAT, null, "WIKI_TOKEN",
                SourceSettingsConfig.CertificateStoreType.WINDOWS_MY, null, null, null, "AB:CD", "firma");
    }

    private static SourceDraft files(String id, String directory) {
        return new SourceDraft(id, "files", null, null, directory, "*.md", null, null, null, null, null, null, null,
                null, null);
    }
}
