package com.aresstack.spaceharvester.output.convert;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.convert.ExportConverters;
import com.aresstack.spaceharvester.application.export.Conversion;
import com.aresstack.spaceharvester.application.export.ExportArtifact;
import com.aresstack.spaceharvester.application.export.ExportArtifactWriter;
import com.aresstack.spaceharvester.application.export.ExportScope;
import com.aresstack.spaceharvester.application.export.Exports;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.output.export.FileSystemExportArtifactWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Plain JSON als Converter, Ende zu Ende über Cache, Export und Dateiablage. */
class JsonArrayConverterTest {

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
    void writesFlatPagesInTreeOrderWithNativeContentAndRecordsItInTheManifest() throws Exception {
        cache.publish("confluence", "DEV", confirmed("g1"), List.of(page("confluence", "DEV", "200", null),
                page("confluence", "DEV", "100", null), page("confluence", "DEV", "101", "100", PRETTY),
                page("confluence", "DEV", "102", "101")));
        FileSystemExportArtifactWriter writer = new FileSystemExportArtifactWriter(root);
        Exports exports = new Exports(jobs, cache, writer,
                new ExportConverters(List.of(new JsonArrayConverter()), ExportConverters.DEFAULT_MAX_BYTES), clock);

        String id = exports.start("confluence", "DEV", "client-a", List.of("json")).id();

        assertThat(jobs.status(id).state()).isEqualTo(JobState.COMPLETED);
        Path dir = root.resolve(id);
        JsonNode pages = json.readTree(dir.resolve("pages.json").toFile());
        assertThat(pages.isArray()).isTrue();
        assertThat(pages.valueStream().map(p -> p.path("header").path("pageId").asString()).toList())
                .containsExactly("100", "101", "102", "200");
        assertThat(pages.valueStream().map(p -> p.path("depth").asInt()).toList()).containsExactly(0, 1, 2, 0);
        JsonNode second = pages.get(1);
        assertThat(second.path("content")).isEqualTo(json.readTree(PRETTY));
        assertThat(second.path("rawSha256").asString()).isEqualTo(sha256(PRETTY));
        assertThat(second.path("header").path("parentPageId").asString()).isEqualTo("100");
        // Kein eingebetteter Baum: die Hierarchie steht nur in tree.json.
        assertThat(second.has("children")).isFalse();

        ExportArtifact artifact = exports.artifact(id, "client-a").orElseThrow();
        assertThat(artifact.files()).extracting(ExportArtifact.File::name)
                .containsExactly("pages.ndjson", "tree.json", "pages.json");
        JsonNode manifest = json.readTree(dir.resolve("manifest.json").toFile());
        JsonNode conversion = manifest.path("conversions").get(0);
        assertThat(conversion.path("format").asString()).isEqualTo("json");
        assertThat(conversion.path("file").asString()).isEqualTo("pages.json");
        assertThat(conversion.path("converter").asString()).isEqualTo(JsonArrayConverter.VERSION);
        assertThat(conversion.path("input").path("hashVersion").asString()).isEqualTo(Conversion.INPUT_HASH_VERSION);
        assertThat(conversion.path("input").path("sha256").asString()).startsWith("sha256:");
        assertThat(manifest.path("files").valueStream().filter(f -> f.path("name").asString().equals("pages.json"))
                .findFirst().orElseThrow().path("sha256").asString())
                .isEqualTo(sha256(Files.readAllBytes(dir.resolve("pages.json"))));
        assertThat(writer.open(id, "pages.json")).isPresent();
    }

    @Test
    void convertedFilesCannotReplaceArtifactFilesOrLeaveTheExport() throws Exception {
        FileSystemExportArtifactWriter writer = new FileSystemExportArtifactWriter(root);
        try (ExportArtifactWriter.Session session = writer.begin("job-1",
                new ExportScope("confluence", "DEV", confirmed("g1")))) {
            for (String name : List.of("manifest.json", "tree.json", "../x.json", "raw/a.json", "Pages.json")) {
                assertThatThrownBy(() -> session.converted(conversion(name), out -> out.write(1)))
                        .as(name).isInstanceOf(IllegalArgumentException.class);
            }
            session.converted(conversion("pages.json"), out -> out.write('1'));
            assertThatThrownBy(() -> session.converted(conversion("pages.json"), out -> out.write('2')))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static Conversion conversion(String fileName) {
        return new Conversion("json", "test/1", "application/json", fileName, "sha256:00", Map.of());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
