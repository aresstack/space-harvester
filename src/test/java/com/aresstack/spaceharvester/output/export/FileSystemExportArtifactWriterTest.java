package com.aresstack.spaceharvester.output.export;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.export.ExportArtifact;
import com.aresstack.spaceharvester.application.export.ExportArtifactWriter;
import com.aresstack.spaceharvester.application.export.ExportScope;
import com.aresstack.spaceharvester.application.export.ExportSummary;
import com.aresstack.spaceharvester.application.export.Exports;
import com.aresstack.spaceharvester.application.export.PageFailure;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.tree.PageTree;
import java.io.BufferedReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class FileSystemExportArtifactWriterTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
    private static final ExportScope SCOPE = new ExportScope("confluence", "DEV", confirmed("g1"));
    // Pretty-printed, mit Zeilenumbrüchen, Unicode und einer Dezimalzahl: Rohdatei muss Byte für Byte gleich bleiben.
    private static final byte[] PRETTY = ("{\n  \"id\" : \"100\",\n  \"title\" : \"Grüße\",\n  \"weight\" : 1.10,\n"
            + "  \"body\" : {\"storage\" : {\"value\" : \"<p>a</p>\\n<p>b</p>\"}}\n}\n").getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path root;

    private final JsonMapper json = JsonMapper.builder().build();

    private static PageSnapshot snapshot(String pageId, String parentPageId, byte[] payload) {
        return InMemoryPageCache.page("confluence", "DEV", pageId, parentPageId, payload);
    }

    private static void write(ExportArtifactWriter.Session session, PageSnapshot snapshot) {
        session.page(snapshot.header(), snapshot);
    }

    private static PageTree tree(PageTree.Node... roots) {
        return new PageTree(List.of(roots), List.of(), 0, 0);
    }

    private ExportSummary summary(String exportId, long pages, List<PageFailure> failures, boolean complete) {
        return new ExportSummary(exportId, SCOPE, NOW, NOW.plusSeconds(5), pages, failures, List.of(), complete);
    }

    @Test
    void writesFlatPagesRawBytesTreeAndManifest() throws Exception {
        FileSystemExportArtifactWriter writer = new FileSystemExportArtifactWriter(root);
        ExportArtifact artifact;
        try (ExportArtifactWriter.Session session = writer.begin("job-1", SCOPE)) {
            write(session, snapshot("100", null, PRETTY));
            write(session, snapshot("101", "100", "{\"id\":\"101\"}".getBytes(StandardCharsets.UTF_8)));
            artifact = session.publish(tree(new PageTree.Node("100", List.of(new PageTree.Node("101", List.of())))),
                    summary("job-1", 2, List.of(), true));
        }

        Path dir = root.resolve("job-1");
        assertThat(Files.readAllBytes(dir.resolve("raw/100.json"))).isEqualTo(PRETTY);

        List<String> lines = Files.readAllLines(dir.resolve("pages.ndjson"));
        assertThat(lines).hasSize(2);
        JsonNode first = json.readTree(lines.getFirst());
        assertThat(first.path("header").path("pageId").asString()).isEqualTo("100");
        assertThat(first.path("header").path("parentPageId").isNull()).isTrue();
        assertThat(first.path("header").path("metadataHash").asString()).startsWith("sha256:");
        assertThat(first.path("raw").path("revision").asString()).isEqualTo("3");
        assertThat(first.path("header").path("attributes").path("confluence.labels").asString()).isEqualTo("api");
        assertThat(first.path("raw").path("file").asString()).isEqualTo("raw/100.json");
        assertThat(first.path("raw").path("sha256").asString()).isEqualTo(sha256(PRETTY));
        assertThat(first.path("raw").path("bytes").asLong()).isEqualTo(PRETTY.length);
        // Inhaltlich gleich wie die Rohantwort, auch die Dezimalzahl.
        assertThat(first.path("content")).isEqualTo(json.readTree(PRETTY));
        assertThat(lines.getFirst()).contains("\"weight\":1.10");
        assertThat(json.readTree(lines.get(1)).path("header").path("parentPageId").asString()).isEqualTo("100");

        JsonNode tree = json.readTree(dir.resolve("tree.json").toFile());
        assertThat(tree.path("roots").get(0).path("id").asString()).isEqualTo("100");
        assertThat(tree.path("roots").get(0).path("children").get(0).path("id").asString()).isEqualTo("101");

        JsonNode manifest = json.readTree(dir.resolve("manifest.json").toFile());
        assertThat(manifest.path("schemaVersion").asString()).isEqualTo("space-harvester-export/v1");
        assertThat(manifest.path("complete").asBoolean()).isTrue();
        assertThat(manifest.path("cache").path("state").asString()).isEqualTo("CONFIRMED");
        assertThat(manifest.path("counts").path("pages").asLong()).isEqualTo(2);
        for (JsonNode file : manifest.path("files")) {
            byte[] bytes = Files.readAllBytes(dir.resolve(file.path("name").asString()));
            assertThat(file.path("sha256").asString()).isEqualTo(sha256(bytes));
            assertThat(file.path("bytes").asLong()).isEqualTo(bytes.length);
        }

        assertThat(writer.find("job-1")).contains(artifact);
        assertThat(artifact.files()).extracting(ExportArtifact.File::name).containsExactly("pages.ndjson", "tree.json");
        assertThat(root.resolve(".incomplete/job-1")).doesNotExist();
    }

    @Test
    void headerIsTheCurrentOneWhileRawKeepsThePayloadsRevision() throws Exception {
        FileSystemExportArtifactWriter writer = new FileSystemExportArtifactWriter(root);
        PageSnapshot stored = snapshot("1", null, "{}".getBytes(StandardCharsets.UTF_8));
        // Die Synchronisierung hat nur den Titel aktualisiert, ohne die Payload neu zu laden.
        PageHeader current = PageHeader.of("confluence", new com.aresstack.spaceharvester.application.sources
                .PageMetadata("1", stored.metadata().resourceId(), "Neuer Titel", "DEV", null, "3",
                stored.metadata().modifiedAt(), null, stored.metadata().attributes()), NOW);
        try (ExportArtifactWriter.Session session = writer.begin("job-6", SCOPE)) {
            session.page(current, stored);
            session.publish(tree(new PageTree.Node("1", List.of())), summary("job-6", 1, List.of(), true));
        }

        JsonNode line = json.readTree(Files.readAllLines(root.resolve("job-6/pages.ndjson")).getFirst());
        assertThat(line.path("header").path("title").asString()).isEqualTo("Neuer Titel");
        assertThat(line.path("header").path("metadataHash").asString()).isEqualTo(current.metadataHash());
        assertThat(line.path("raw").path("retrievedAt").asString()).isEqualTo(stored.retrievedAt().toString());
    }

    @Test
    void artifactIsInvisibleUntilPublishedAndDiscardedOnClose() {
        FileSystemExportArtifactWriter writer = new FileSystemExportArtifactWriter(root);
        try (ExportArtifactWriter.Session session = writer.begin("job-2", SCOPE)) {
            write(session, snapshot("1", null, "{}".getBytes(StandardCharsets.UTF_8)));
            assertThat(writer.find("job-2")).isEmpty();
            assertThat(root.resolve("job-2")).doesNotExist();
        }

        assertThat(writer.find("job-2")).isEmpty();
        assertThat(root.resolve(".incomplete/job-2")).doesNotExist();
    }

    @Test
    void publishedArtifactIsNeverOverwritten() {
        FileSystemExportArtifactWriter writer = new FileSystemExportArtifactWriter(root);
        try (ExportArtifactWriter.Session session = writer.begin("job-3", SCOPE)) {
            session.publish(tree(), summary("job-3", 0, List.of(), true));
        }

        assertThatThrownBy(() -> writer.begin("job-3", SCOPE)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void incompleteExportSaysSoInTheManifest() throws Exception {
        FileSystemExportArtifactWriter writer = new FileSystemExportArtifactWriter(root);
        PageFailure missing = new PageFailure("7", "PAYLOAD_MISSING", "Payload von Seite 7 fehlt");
        try (ExportArtifactWriter.Session session = writer.begin("job-4", SCOPE)) {
            session.publish(tree(), summary("job-4", 0, List.of(missing), false));
        }

        JsonNode manifest = json.readTree(root.resolve("job-4/manifest.json").toFile());
        assertThat(manifest.path("complete").asBoolean()).isFalse();
        assertThat(manifest.path("counts").path("failedPages").asLong()).isEqualTo(1);
        assertThat(manifest.path("failures").get(0).path("pageId").asString()).isEqualTo("7");
        assertThat(writer.find("job-4")).get().extracting(ExportArtifact::complete).isEqualTo(false);
    }

    @Test
    void unreadablePayloadIsKeptRawWithWarningAndNullContent() throws Exception {
        FileSystemExportArtifactWriter writer = new FileSystemExportArtifactWriter(root);
        byte[] broken = {'{', '"', 'a', (byte) 0xff, '"'};
        try (ExportArtifactWriter.Session session = writer.begin("job-5", SCOPE)) {
            write(session, snapshot("1", null, broken));
            session.publish(tree(new PageTree.Node("1", List.of())), summary("job-5", 1, List.of(), true));
        }

        assertThat(Files.readAllBytes(root.resolve("job-5/raw/1.json"))).isEqualTo(broken);
        assertThat(json.readTree(Files.readAllLines(root.resolve("job-5/pages.ndjson")).getFirst())
                .path("content").isNull()).isTrue();
        assertThat(json.readTree(root.resolve("job-5/manifest.json").toFile()).path("warnings").get(0).asString())
                .contains("Seite 1");
    }

    @Test
    void fileNamesComeFromPageIdsAndNeverCollide() {
        assertThat(FileSystemExportArtifactWriter.rawFileName("123", "application/json")).isEqualTo("123.json");
        assertThat(FileSystemExportArtifactWriter.rawFileName("../etc/passwd", "application/json"))
                .startsWith("id-").doesNotContain("/", "..");
        // Groß-/Kleinschreibung darf auf Windows-Dateisystemen nicht zusammenfallen.
        assertThat(FileSystemExportArtifactWriter.rawFileName("Ab", "application/json"))
                .isNotEqualTo(FileSystemExportArtifactWriter.rawFileName("ab", "application/json"));
        assertThat(FileSystemExportArtifactWriter.rawFileName("1", "text/html")).isEqualTo("1.bin");
    }

    @Test
    void rejectsUnsafeExportIds() {
        FileSystemExportArtifactWriter writer = new FileSystemExportArtifactWriter(root);

        assertThatThrownBy(() -> writer.begin("../x", SCOPE)).isInstanceOf(IllegalArgumentException.class);
        assertThat(writer.find("../x")).isEmpty();
    }

    @Test
    void exportsManyPagesEndToEndFromTheCacheReader() throws Exception {
        int pages = 5_000;
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(1, 1, Duration.ofHours(1)), clock);
        FileSystemExportArtifactWriter writer = new FileSystemExportArtifactWriter(root);
        InMemoryPageCache cache = new InMemoryPageCache();
        cache.publish("confluence", "DEV", confirmed("g1"), IntStream.range(0, pages)
                .mapToObj(i -> InMemoryPageCache.page("confluence", "DEV", String.valueOf(i),
                        i == 0 ? null : String.valueOf(i / 10)))
                .toList());
        Exports exports = new Exports(jobs, cache, writer, clock);

        String id = exports.start("confluence", "DEV", "client-a").id();

        assertThat(jobs.status(id).state()).isEqualTo(JobState.COMPLETED);
        assertThat(exports.artifact(id, "client-a")).get().extracting(ExportArtifact::complete).isEqualTo(true);
        Path dir = root.resolve(id);
        Set<String> pageIds = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(dir.resolve("pages.ndjson"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode page = json.readTree(line);
                String pageId = page.path("header").path("pageId").asString();
                assertThat(pageIds.add(pageId)).isTrue();
                byte[] raw = Files.readAllBytes(dir.resolve(page.path("raw").path("file").asString()));
                assertThat(page.path("raw").path("sha256").asString()).isEqualTo(sha256(raw));
            }
        }
        assertThat(pageIds).hasSize(pages);
        // Jede Seite des Baums steht im Seiten-Array und umgekehrt.
        Set<String> treeIds = new HashSet<>();
        collect(json.readTree(dir.resolve("tree.json").toFile()).path("roots"), treeIds);
        assertThat(treeIds).isEqualTo(pageIds);
        try (Stream<Path> raw = Files.list(dir.resolve("raw"))) {
            assertThat(raw.count()).isEqualTo(pages);
        }
    }

    private static void collect(JsonNode nodes, Set<String> ids) {
        for (JsonNode node : nodes) {
            assertThat(ids.add(node.path("id").asString())).isTrue();
            collect(node.path("children"), ids);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    @Test
    void listsDeletesAndDiscardsArtifactsSeparately() throws Exception {
        FileSystemExportArtifactWriter writer = new FileSystemExportArtifactWriter(root);
        assertThat(writer.published()).isEmpty();
        assertThat(writer.unpublished()).isEmpty();
        try (ExportArtifactWriter.Session session = writer.begin("done", SCOPE)) {
            write(session, snapshot("1", null, PRETTY));
            session.publish(tree(new PageTree.Node("1", List.of())), summary("done", 1, List.of(), true));
        }
        ExportArtifactWriter.Session open = writer.begin("running", SCOPE);
        write(open, snapshot("1", null, PRETTY));
        // Rest eines Laufs, der beim Beenden des Dienstes nicht mehr aufräumen konnte.
        Files.createDirectories(root.resolve(".incomplete/crashed/raw"));

        assertThat(writer.published()).containsExactly("done");
        assertThat(writer.unpublished()).containsExactlyInAnyOrder("running", "crashed");

        writer.discard("crashed");
        writer.discard("done");
        writer.delete("running");
        assertThat(writer.published()).containsExactly("done");
        assertThat(writer.unpublished()).containsExactly("running");

        writer.delete("done");
        writer.delete("done");
        writer.delete("../outside");
        assertThat(writer.published()).isEmpty();
        assertThat(writer.find("done")).isEmpty();
        assertThat(root.resolve("done")).doesNotExist();
        open.close();
    }
    @Test
    void pagesAndTreeReferToEachPageByIdAndUrl() throws Exception {
        FileSystemExportArtifactWriter writer = new FileSystemExportArtifactWriter(root);
        try (ExportArtifactWriter.Session session = writer.begin("urls", SCOPE)) {
            write(session, withUrl("100", null, "https://wiki.example.org/pages/viewpage.action?pageId=100"));
            write(session, withUrl("101", "100", "https://wiki.example.org/pages/viewpage.action?pageId=101"));
            write(session, snapshot("102", "100", PRETTY));
            session.publish(tree(new PageTree.Node("100", List.of(new PageTree.Node("101", List.of()),
                    new PageTree.Node("102", List.of())))), summary("urls", 3, List.of(), true));
        }

        List<String> lines = Files.readAllLines(root.resolve("urls/pages.ndjson"));
        assertThat(json.readTree(lines.get(1)).path("header").path("url").asString())
                .isEqualTo("https://wiki.example.org/pages/viewpage.action?pageId=101");
        assertThat(json.readTree(lines.get(2)).path("header").path("url").isNull()).isTrue();
        JsonNode root100 = json.readTree(root.resolve("urls/tree.json").toFile()).path("roots").get(0);
        assertThat(root100.path("url").asString()).isEqualTo("https://wiki.example.org/pages/viewpage.action?pageId=100");
        assertThat(root100.path("children").get(0).path("url").asString()).endsWith("pageId=101");
        assertThat(root100.path("children").get(1).path("url").isNull()).isTrue();
    }

    private static PageSnapshot withUrl(String pageId, String parentPageId, String url) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:wiki.example.org/page/" + pageId,
                "Seite " + pageId, "DEV", parentPageId, "3", NOW, URI.create(url), PageMetadata.sorted(java.util.Map.of()));
        return PageSnapshot.of("confluence", metadata, new NativePayload(("{\"id\":\"" + pageId + "\"}")
                .getBytes(StandardCharsets.UTF_8), "application/json", "confluence-rest-content+json"), NOW);
    }
}
