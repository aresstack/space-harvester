package com.aresstack.spaceharvester.output.convert;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.convert.ExportConverters;
import com.aresstack.spaceharvester.application.convert.ExportReadingView;
import com.aresstack.spaceharvester.application.export.ExportConversions;
import com.aresstack.spaceharvester.application.export.Exports;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.output.export.FileSystemExportArtifactWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** JSON mit Medien (json-package/3): verlustfreie Rohdaten, Baum und Paketmanifest für einen späteren Import. */
class JsonPackageConverterTest {

    private static final byte[] PRETTY = "{\n  \"id\" : \"101\",\n  \"title\" : \"Grüße\",\n  \"weight\" : 1.10\n}\n"
            .getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path root;

    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC);
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(1, 5, Duration.ofHours(1)),
            clock);
    private final InMemoryPageCache cache = new InMemoryPageCache();

    @Test
    void packageCarriesRawBytesTreeAndManifestNextToPagesJson() throws Exception {
        cache.publish("confluence", "DEV", confirmed("g1"), List.of(page("confluence", "DEV", "200", null),
                page("confluence", "DEV", "100", null), page("confluence", "DEV", "101", "100", PRETTY),
                page("confluence", "DEV", "102", "101")));
        FileSystemExportArtifactWriter writer = new FileSystemExportArtifactWriter(root);
        ExportConverters converters = new ExportConverters(List.of(new JsonArrayConverter(),
                new JsonPackageConverter()), ExportConverters.DEFAULT_MAX_BYTES);
        Exports exports = new Exports(jobs, cache, writer, converters, clock);
        String id = exports.start("confluence", "DEV", "client-a", List.of("json")).id();
        assertThat(jobs.status(id).state()).isEqualTo(JobState.COMPLETED);
        String version = cache.page(new PageKey("confluence", "101")).orElseThrow().snapshot().revision();
        ExportReadingView view = ExportReadingView.available("101", version, "<p>Eingebunden</p>",
                "https://wiki.example.org/", "Eingebunden");
        writer.putReadingView(id, view);

        ExportConversions.ConvertedFile converted = new ExportConversions(jobs, writer, converters)
                .convert(id, "client-a", JsonPackageConverter.FORMAT, root.resolve("out"));

        Map<String, byte[]> zip = unzip(converted.path());
        assertThat(zip.keySet()).containsSubsequence("pages.json", "assets.json", "attachments.json",
                "raw/100.json", "raw/101.json", "views/101.json", "raw/102.json", "raw/200.json", "tree.json",
                "manifest.json");
        assertThat(ExportFileJson.readReadingView(json.readTree(zip.get("views/101.json")), "101")).contains(view);
        // Byte für Byte, nicht neu geschrieben: der Hash aus pages.json passt zur Datei.
        assertThat(zip.get("raw/101.json")).isEqualTo(PRETTY);
        JsonNode pages = json.readTree(zip.get("pages.json"));
        assertThat(pages.get(1).path("rawSha256").asString()).isEqualTo(sha256(PRETTY));

        JsonNode tree = json.readTree(zip.get("tree.json"));
        assertThat(tree.path("roots").valueStream().map(n -> n.path("id").asString()).toList())
                .containsExactly("100", "200");
        assertThat(tree.path("roots").get(0).path("children").get(0).path("id").asString()).isEqualTo("101");

        JsonNode manifest = json.readTree(zip.get("manifest.json"));
        assertThat(manifest.path("format").asString()).isEqualTo(JsonPackageConverter.PACKAGE_FORMAT);
        assertThat(manifest.path("converter").asString()).isEqualTo("json-package/5");
        assertThat(manifest.path("sourceId").asString()).isEqualTo("confluence");
        assertThat(manifest.path("spaceKey").asString()).isEqualTo("DEV");
        assertThat(manifest.path("pageCount").asInt()).isEqualTo(4);
        assertThat(manifest.path("assets").isNull()).isTrue();
        JsonNode second = manifest.path("pages").get(1);
        assertThat(second.path("pageId").asString()).isEqualTo("101");
        assertThat(second.path("depth").asInt()).isEqualTo(1);
        assertThat(second.path("raw").asString()).isEqualTo("raw/101.json");
        assertThat(second.path("rawSha256").asString()).isEqualTo(sha256(PRETTY));
        assertThat(second.path("size").asInt()).isEqualTo(PRETTY.length);
        assertThat(second.path("mediaType").asString()).isEqualTo("application/json");
        assertThat(second.path("nativeFormat").asString()).isNotBlank();
        assertThat(second.path("resourceId").asString()).isNotBlank();
        assertThat(second.path("retrievedAt").asString()).isNotBlank();
        assertThat(second.path("metadataHash").asString()).startsWith("sha256:");
        assertThat(second.path("view").asString()).isEqualTo("views/101.json");
        assertThat(manifest.path("pages").get(0).path("view").isNull()).isTrue();
    }

    @Test
    void readingViewSurvivesTheRoundTrip() {
        ExportReadingView view = ExportReadingView.available("101", "3", "<p>Hallo</p>", "https://wiki.example.org/",
                "Hallo");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator generator = json.createGenerator(out)) {
            ExportFileJson.writeReadingView(generator, view);
        }
        JsonNode node = json.readTree(out.toByteArray());

        assertThat(ExportFileJson.readReadingView(node, "101")).contains(view);
        assertThat(ExportFileJson.readReadingView(node, "102")).isEmpty();
    }

    @Test
    void rawFileNamesStayInsideTheirFolder() {
        assertThat(ExportFileJson.rawFileName("101", "application/json")).isEqualTo("101.json");
        assertThat(ExportFileJson.rawFileName("101", "text/html")).isEqualTo("101.bin");
        assertThat(ExportFileJson.rawFileName("../x", "application/json")).matches("id-[0-9a-f]{40}\\.json");
    }

    private static Map<String, byte[]> unzip(Path file) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(file); ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
