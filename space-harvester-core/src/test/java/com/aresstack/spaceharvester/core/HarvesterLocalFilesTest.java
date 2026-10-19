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

    @BeforeEach
    void linkOutOfTheDirectory() throws IOException {
        for (String name : List.of("a.md", "b.md")) {
            Files.writeString(elsewhere.resolve(name), "Inhalt " + name, StandardCharsets.UTF_8);
            Files.createSymbolicLink(directory.resolve(name), elsewhere.resolve(name));
        }
    }

    @Test
    void changedRootsAllowAndBlockAccessFromTheNextReadOn() throws Exception {
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
    void withoutLiveConfigurationNothingOutsideTheDirectoryIsGranted() {
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
