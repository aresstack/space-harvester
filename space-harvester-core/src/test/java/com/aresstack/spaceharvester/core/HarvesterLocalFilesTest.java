package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.configuration.ConfigurationManager;
import com.aresstack.spaceharvester.application.configuration.HarvesterConfiguration;
import com.aresstack.spaceharvester.application.configuration.LocalFileSettings;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceType;
import com.aresstack.spaceharvester.source.LocalFilesSourceAdapter;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.testsupport.TestSymbolicLinks;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Der eingebaute Typ {@code files} folgt den freigegebenen Ordnern der wirksamen Konfiguration ohne eigenen Adapter
 * (ADR 0015); ein ersetzter Typ behält seinen Platz in der Typenliste.
 */
class HarvesterLocalFilesTest {

    @TempDir
    Path data;
    @TempDir
    Path directory;
    @TempDir
    Path elsewhere;

    private void linkOutOfTheDirectory() throws IOException {
        for (String name : List.of("a.md", "b.md")) {
            Files.writeString(elsewhere.resolve(name), "Inhalt " + name, StandardCharsets.UTF_8);
            TestSymbolicLinks.createOrSkipUnsupported(directory.resolve(name), elsewhere.resolve(name));
        }
    }

    @Test
    void changedRootsAllowAndBlockAccessFromTheNextReadOn() throws Exception {
        linkOutOfTheDirectory();
        ConfigurationManager manager = new ConfigurationManager(HarvesterConfiguration.DEFAULT);
        try (Harvester harvester = Harvester.builder().source(docs()).dataDirectory(data).configuration(manager)
                .build()) {
            assertThatThrownBy(() -> read(harvester, "a.md")).as("Standard: keine Freigabe").isInstanceOf(SourceAccessException.class);

            assertThat(manager.apply(HarvesterConfiguration.DEFAULT.withLocalFiles(
                    new LocalFileSettings(false, List.of(elsewhere)))).activated()).isTrue();
            assertThat(read(harvester, "a.md")).isEqualTo("Inhalt a.md");

            assertThat(manager.apply(HarvesterConfiguration.DEFAULT).activated()).isTrue();
            assertThatThrownBy(() -> read(harvester, "b.md")).isInstanceOf(SourceAccessException.class);
        }
    }

    @Test
    void withoutLiveConfigurationNothingOutsideTheDirectoryIsGranted() throws Exception {
        linkOutOfTheDirectory();
        try (Harvester harvester = Harvester.builder().source(docs()).dataDirectory(data).build()) {
            assertThatThrownBy(() -> read(harvester, "a.md")).isInstanceOf(SourceAccessException.class);
        }
    }

    @Test
    void aReplacedTypeKeepsItsPlace() {
        List<String> standard;
        try (Harvester harvester = Harvester.builder().dataDirectory(data).build()) {
            standard = harvester.sourceTypes().stream().map(SourceType::id).toList();
        }
        try (Harvester harvester = Harvester.builder().dataDirectory(data)
                .sourceAdapter(new LocalFilesSourceAdapter()).build()) {
            assertThat(harvester.sourceTypes()).extracting(SourceType::id).containsExactlyElementsOf(standard);
        }
    }

    @Test
    void theTextOfAFolderPageComesFromTheSameProjectorsAsTheIndex() throws IOException {
        Files.writeString(directory.resolve("notiz.md"), "Wartung am Freitag", StandardCharsets.UTF_8);
        try (Harvester harvester = Harvester.builder().source(docs()).dataDirectory(data).build()) {
            String id = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    "notiz.md".getBytes(StandardCharsets.UTF_8));
            PageSnapshot snapshot = harvester.pages().page(harvester.caller(), "docs", id, false).snapshot();

            assertThat(harvester.pageText(snapshot)).map(PageText::text).hasValueSatisfying(text ->
                    assertThat(text).contains("Wartung am Freitag"));
        }
    }

    @Test
    void referenceModeConnectsTheRealCacheReaderAndSearchPipeline() throws IOException {
        Path file = directory.resolve("notiz.txt");
        Files.writeString(file, "Beginning\n" + "ordinary paragraph\n\n".repeat(7000) + "Zielbegriff");
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "notiz.txt".getBytes(StandardCharsets.UTF_8));
        try (Harvester harvester = Harvester.builder().source(docs()).dataDirectory(data)
                .localOriginalReferences(true).build()) {
            PageSnapshot snapshot = harvester.pages().page(harvester.caller(), "docs", id, false).snapshot();
            var reference = com.aresstack.spaceharvester.application.snapshot.LocalOriginalReference
                    .read(snapshot.payload()).orElseThrow();
            assertThat(reference.extractionKey()).isNotNull();
            assertThat(reference.originalSha256()).isNotEqualTo(snapshot.payload().rawSha256());
            assertThat(harvester.pageText(snapshot)).map(PageText::text)
                    .hasValueSatisfying(text -> assertThat(text).contains("Zielbegriff"));
            var query = new com.aresstack.spaceharvester.application.query.KeywordSearch.Query(
                    "Zielbegriff", "docs", java.util.Set.of(), java.util.Set.of());
            assertThat(harvester.keywordSearch().search(harvester.caller(), query, null, 10).hits())
                    .extracting(com.aresstack.spaceharvester.application.query.KeywordSearch.Hit::pageId)
                    .contains(id);
            assertThat(Files.readString(file)).endsWith("Zielbegriff");
        }
        try (Harvester harvester = Harvester.builder().source(docs()).dataDirectory(data)
                .localOriginalReferences(true).build()) {
            PageSnapshot snapshot = harvester.pages().page(harvester.caller(), "docs", id, false).snapshot();
            assertThat(com.aresstack.spaceharvester.application.snapshot.LocalOriginalReference
                    .read(snapshot.payload())).isPresent();
            assertThat(harvester.pageText(snapshot)).map(PageText::text)
                    .hasValueSatisfying(text -> assertThat(text).contains("Zielbegriff"));
        }
    }

    private static String read(Harvester harvester, String name) {
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8));
        return new String(harvester.pages().page(harvester.caller(), "docs", id, false).snapshot().payload().bytes(),
                StandardCharsets.UTF_8);
    }

    private SourceSettingsConfig docs() {
        return new SourceSettingsConfig("docs", "files", "Dokumente", null, null,
                Map.of("directory", directory.toString()));
    }
}
