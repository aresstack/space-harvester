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
            assertThat(read(harvester, "a.md")).contains("Inhalt a.md");

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
    void folderPageIdentitySelectsOnlyItsSubtreeForExplicitIndexing() throws Exception {
        Files.createDirectories(directory.resolve("selected"));
        Files.writeString(directory.resolve("selected/one.md"), "Teilbaumprobe");
        Files.writeString(directory.resolve("outside.md"), "Aussenprobe");
        try (Harvester harvester = Harvester.builder().source(docs()).dataDirectory(data)
                .backgroundFill(false).build()) {
            String folder = LocalFilesSourceAdapter.folderPageId(Path.of("selected"));
            var job = harvester.indexing().index("docs", "docs", folder, Harvester.OWNER,
                    com.aresstack.spaceharvester.application.sync.SyncMode.CATALOG_ONLY);
            var result = harvester.await(job.id(), java.time.Duration.ofSeconds(30));
            assertThat(result.state()).isEqualTo(com.aresstack.spaceharvester.application.jobs.JobState.COMPLETED);
            assertThat(harvester.indexing().marks().marked("docs", "docs", folder)).isTrue();
            var inside = new com.aresstack.spaceharvester.application.query.KeywordSearch.Query(
                    "Teilbaumprobe", "docs", java.util.Set.of(), java.util.Set.of());
            var outside = new com.aresstack.spaceharvester.application.query.KeywordSearch.Query(
                    "Aussenprobe", "docs", java.util.Set.of(), java.util.Set.of());
            assertThat(harvester.keywordSearch().search(harvester.caller(), inside, null, 10).hits()).isNotEmpty();
            assertThat(harvester.keywordSearch().search(harvester.caller(), outside, null, 10).hits()).isEmpty();
        }
    }

    @Test
    void defaultStorageConnectsTheRealCacheReaderAndSearchPipeline() throws IOException {
        Path file = directory.resolve("notiz.txt");
        Files.writeString(file, "Beginning\n" + "ordinary paragraph\n\n".repeat(7000) + "Zielbegriff");
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "notiz.txt".getBytes(StandardCharsets.UTF_8));
        try (Harvester harvester = Harvester.builder().source(docs()).dataDirectory(data)
                .build()) {
            PageSnapshot snapshot = harvester.pages().page(harvester.caller(), "docs", id, false).snapshot();
            var reference = com.aresstack.spaceharvester.application.snapshot.LocalOriginalReference
                    .read(snapshot.payload()).orElseThrow();
            assertThat(reference.extractionKey()).isNotNull();
            assertThat(reference.preview()).hasSizeLessThanOrEqualTo(8192).doesNotContain("Zielbegriff");
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
                .build()) {
            PageSnapshot snapshot = harvester.pages().page(harvester.caller(), "docs", id, false).snapshot();
            assertThat(com.aresstack.spaceharvester.application.snapshot.LocalOriginalReference
                    .read(snapshot.payload())).isPresent();
            assertThat(harvester.pageText(snapshot)).map(PageText::text)
                    .hasValueSatisfying(text -> assertThat(text).contains("Zielbegriff"));
        }
    }

    @Test
    void unsupportedBinaryIsRepresentedWithoutAnOriginalHashOrExtraction() throws IOException {
        Files.write(directory.resolve("backup.bin"), new byte[] {0, 1, 2, 3});
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "backup.bin".getBytes(StandardCharsets.UTF_8));
        try (Harvester harvester = Harvester.builder().source(docs()).dataDirectory(data)
                .build()) {
            PageSnapshot snapshot = harvester.pages().page(harvester.caller(), "docs", id, false).snapshot();
            var reference = com.aresstack.spaceharvester.application.snapshot.LocalOriginalReference
                    .read(snapshot.payload()).orElseThrow();
            assertThat(reference.originalSha256()).isNull();
            assertThat(reference.extractionKey()).isNull();
            assertThat(reference.preview()).isEmpty();
        }
    }

    @Test
    void localExportsContainVerifiedOriginalsOrSelfContainedTextWithoutReadingTheSource() throws Exception {
        Path original = directory.resolve("notiz.txt");
        String content = "Beginn\n" + "ordinary paragraph\n\n".repeat(7000) + "Zielbegriff";
        Files.writeString(original, content, StandardCharsets.UTF_8);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "notiz.txt".getBytes(StandardCharsets.UTF_8));
        var formats = List.of("json", "json-package", "markdown");
        var owner = Harvester.OWNER;
        try (Harvester harvester = Harvester.builder().source(docs()).dataDirectory(data)
                .build()) {
            var request = com.aresstack.spaceharvester.application.export.ExportRequest.of("docs", "docs", owner,
                    formats);
            var normal = harvester.exports().start(request);
            var normalStatus = harvester.jobs().await(normal.id(), owner, java.time.Duration.ofSeconds(60));
            assertThat(normalStatus.state().name()).as("Export: %s", normalStatus.error()).isEqualTo("COMPLETED");
            Path rawPackage = data.resolve("original-export.zip");
            try (var zip = harvester.exports().artifactFile(normal.id(), owner, "export.zip").orElseThrow()) {
                Files.copy(zip, rawPackage);
            }
            var reader = new com.aresstack.spaceharvester.output.export.ZipExportPackageReader(
                    20L * 1024 * 1024, 20L * 1024 * 1024);
            try (var exported = reader.open(rawPackage)) {
                var page = exported.snapshot(id);
                assertThat(page.payload().nativeFormat()).isEqualTo("file");
                assertThat(page.payload().bytes()).isEqualTo(content.getBytes(StandardCharsets.UTF_8));
            }
            try (var md = harvester.exports().artifactFile(normal.id(), owner, "pages.md").orElseThrow()) {
                assertThat(new String(md.readAllBytes(), StandardCharsets.UTF_8)).contains("Zielbegriff");
            }
            var cached = harvester.pages().cached(harvester.caller(), "docs", id).orElseThrow().snapshot();
            var reference = com.aresstack.spaceharvester.application.snapshot.LocalOriginalReference
                    .read(cached.payload()).orElseThrow();
            Files.delete(original);
            var holdings = harvester.exports().startFromHoldings(request.withHoldingsOnly(true));
            var holdingsStatus = harvester.jobs().await(holdings.id(), owner, java.time.Duration.ofSeconds(60));
            assertThat(holdingsStatus.state().name()).as("Export: %s", holdingsStatus.error()).isEqualTo("COMPLETED");
            Path portablePackage = data.resolve("portable-export.zip");
            try (var zip = harvester.exports().artifactFile(holdings.id(), owner, "export.zip").orElseThrow()) {
                Files.copy(zip, portablePackage);
            }
            try (var exported = reader.open(portablePackage)) {
                var page = exported.snapshot(id);
                var portable = com.aresstack.spaceharvester.application.snapshot.PortableLocalDocument
                        .read(page.payload()).orElseThrow();
                assertThat(portable.originalSha256()).isEqualTo(reference.originalSha256());
                assertThat(portable.referenceSha256()).isEqualTo(cached.payload().rawSha256());
                assertThat(portable.text()).contains("Zielbegriff");
                assertThat(portable.textTruncated()).isFalse();
                assertThat(page.payload().rawSha256()).isNotEqualTo(reference.originalSha256());
                assertThat(new com.aresstack.spaceharvester.source.KnowledgeDocumentTextProjector()
                        .project(page)).map(PageText::text).hasValueSatisfying(text -> assertThat(text)
                        .contains("Zielbegriff"));
            }
            try (var json = harvester.exports().artifactFile(holdings.id(), owner, "pages.json").orElseThrow()) {
                assertThat(new String(json.readAllBytes(), StandardCharsets.UTF_8)).contains("Zielbegriff");
            }
            try (var md = harvester.exports().artifactFile(holdings.id(), owner, "pages.md").orElseThrow()) {
                assertThat(new String(md.readAllBytes(), StandardCharsets.UTF_8)).contains("Zielbegriff");
            }
            assertThat(harvester.pages().cached(harvester.caller(), "docs", id).orElseThrow()
                    .snapshot().payload().rawSha256()).isEqualTo(cached.payload().rawSha256());

            // Import into a clean data directory without access to the original or its extraction store.
            try (Harvester imported = Harvester.builder().source(docs()).dataDirectory(data.resolve("imported"))
                    .build()) {
                var job = imported.packageImport().start(portablePackage, "docs", false, owner);
                var status = imported.jobs().await(job.id(), owner, java.time.Duration.ofSeconds(60));
                assertThat(status.state().name()).as("Import: %s", status.error()).isEqualTo("COMPLETED");
                var page = imported.pages().cached(imported.caller(), "docs", id).orElseThrow().snapshot();
                assertThat(imported.pageText(page)).map(PageText::text)
                        .hasValueSatisfying(text -> assertThat(text).contains("Zielbegriff"));
            }
        }
    }

    private static String read(Harvester harvester, String name) {
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8));
        PageSnapshot snapshot = harvester.pages().page(harvester.caller(), "docs", id, false).snapshot();
        return harvester.pageText(snapshot).map(PageText::text).orElseThrow().strip();
    }

    private SourceSettingsConfig docs() {
        return new SourceSettingsConfig("docs", "files", "Dokumente", null, null,
                Map.of("directory", directory.toString()));
    }
}
