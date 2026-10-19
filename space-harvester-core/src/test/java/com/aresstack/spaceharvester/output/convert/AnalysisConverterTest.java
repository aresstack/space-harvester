package com.aresstack.spaceharvester.output.convert;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.convert.ExportConverters;
import com.aresstack.spaceharvester.application.enrichment.AcronymProcessor;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.OutlineProcessor;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.export.EnrichmentAnalyses;
import com.aresstack.spaceharvester.application.export.ExportConversions;
import com.aresstack.spaceharvester.application.export.Exports;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.output.export.FileSystemExportArtifactWriter;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Analyse-Export (#359): Abschnitt je Prozessor mit Zustand, Fakten, Zuordnung über IDs; die Ablage bleibt unberührt. */
class AnalysisConverterTest {

    private static final byte[] ACRONYM = "{\"text\":\"Die API (Application Programming Interface) ist neu.\"}"
            .getBytes(StandardCharsets.UTF_8);
    /** Projiziert jede Payload als ihren Text. */
    private static final PageTextProjector PROJECTOR = snapshot -> Optional.of(new PageText("test/v1",
            new String(snapshot.payload().bytes(), StandardCharsets.UTF_8), List.of()));

    @TempDir
    Path root;

    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-19T02:00:00Z"), ZoneOffset.UTC);
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(1, 5, Duration.ofHours(1)),
            clock);
    private final InMemoryPageCache cache = new InMemoryPageCache();
    private final InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();
    private final Enrichments enrichments = new Enrichments(PROJECTOR,
            List.of(new AcronymProcessor(), new OutlineProcessor(), new Broken()), store, clock);

    @Test
    void exportsEveryProcessorPerPageWithStateFactsAndIdsAndLeavesTheStoreUntouched() throws Exception {
        PageSnapshot root100 = page("confluence", "DEV", "100", null, ACRONYM);
        PageSnapshot child101 = page("confluence", "DEV", "101", "100");
        cache.publish("confluence", "DEV", confirmed("g1"), List.of(root100, child101));
        enrichments.refresh(root100);
        // 101: Akronyme aus einer älteren Fassung, Gliederung fehlt, dazu ein Prozessor, den es nicht mehr gibt.
        store.save(new Enrichment(child101.key(), "acronyms", "2", "sha256:alt", "2", Enrichment.Status.OK,
                "{\"acronyms\":[]}", List.of(), null, clock.instant()));
        store.save(new Enrichment(child101.key(), "gone", "7", "sha256:alt", "2", Enrichment.Status.OK, "{\"x\":1}",
                List.of("w"), null, clock.instant()));
        List<Enrichment> before100 = store.find(root100.key());
        List<Enrichment> before101 = store.find(child101.key());

        FileSystemExportArtifactWriter writer = new FileSystemExportArtifactWriter(root);
        ExportConverters converters = new ExportConverters(List.of(new JsonArrayConverter(),
                new AnalysisConverter(new EnrichmentAnalyses(enrichments))), ExportConverters.DEFAULT_MAX_BYTES);
        Exports exports = new Exports(jobs, cache, writer, converters, clock);
        String id = exports.start("confluence", "DEV", "client-a", List.of("json")).id();
        assertThat(jobs.status(id).state()).isEqualTo(JobState.COMPLETED);
        assertThat(Files.exists(root.resolve(id).resolve("analysis.json"))).isFalse();

        // Erst auf Abruf erzeugt, aus dem Datenbestand des Exports.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ExportConversions(jobs, writer, converters).convert(id, "client-a", AnalysisConverter.FORMAT, out);
        JsonNode analysis = json.readTree(out.toByteArray());

        assertThat(analysis.path("format").asString()).isEqualTo(AnalysisConverter.SCHEMA);
        assertThat(analysis.path("sourceId").asString()).isEqualTo("confluence");
        assertThat(analysis.path("spaceKey").asString()).isEqualTo("DEV");
        assertThat(analysis.path("processors").valueStream().map(p -> p.path("id").asString() + "@"
                + p.path("version").asString()).toList()).containsExactly("acronyms@4;sentences=splitter", "outline@1", "broken@1");
        JsonNode pages = analysis.path("pages");
        assertThat(pages.valueStream().map(p -> p.path("pageId").asString()).toList()).containsExactly("100", "101");

        JsonNode first = pages.get(0);
        assertThat(first.path("sourceId").asString()).isEqualTo("confluence");
        assertThat(first.path("revision").asString()).isEqualTo("3");
        assertThat(first.path("rawSha256").asString()).isEqualTo(root100.payload().rawSha256());
        String input = PROJECTOR.project(root100).orElseThrow().inputSha256();
        assertThat(first.path("inputSha256").asString()).isEqualTo(input);
        JsonNode acronyms = first.path("enrichments").path("acronyms");
        assertThat(acronyms.path("state").asString()).isEqualTo("CURRENT");
        assertThat(acronyms.path("inputSha256").asString()).isEqualTo(input);
        assertThat(acronyms.path("payload").toString()).contains("Application Programming Interface");
        assertThat(first.path("enrichments").path("outline").path("state").asString()).isEqualTo("CURRENT");
        JsonNode broken = first.path("enrichments").path("broken");
        assertThat(broken.path("state").asString()).isEqualTo("FAILED");
        assertThat(broken.path("errorCode").asString()).isEqualTo(Enrichments.PROCESSOR_ERROR);
        assertThat(broken.path("payload").isNull()).isTrue();
        assertThat(first.path("facts").valueStream().map(f -> f.path("type").asString()).toList())
                .contains("ACRONYM_DEFINITION");
        assertThat(first.path("facts").get(0).path("processorId").asString()).isEqualTo("acronyms");
        assertThat(first.path("facts").get(0).path("origin").asString()).isEqualTo("AUTOMATIC");
        assertThat(first.path("facts").get(0).path("review").asString()).isEqualTo("UNREVIEWED");

        JsonNode second = pages.get(1);
        assertThat(second.path("parentPageId").asString()).isEqualTo("100");
        assertThat(second.path("depth").asInt()).isEqualTo(1);
        JsonNode sections = second.path("enrichments");
        assertThat(sections.propertyNames()).containsExactly("acronyms", "outline", "broken", "gone");
        assertThat(sections.path("acronyms").path("state").asString()).isEqualTo("STALE");
        assertThat(sections.path("acronyms").path("revision").asString()).isEqualTo("2");
        assertThat(sections.path("outline").path("state").asString()).isEqualTo("MISSING");
        assertThat(sections.path("outline").path("payload").isNull()).isTrue();
        assertThat(sections.path("gone").path("state").asString()).isEqualTo("STALE");
        assertThat(sections.path("gone").path("currentProcessorVersion").isNull()).isTrue();
        assertThat(sections.path("gone").path("payload").path("x").asInt()).isEqualTo(1);

        // Nichts nachgerechnet, nichts verändert.
        assertThat(store.find(root100.key())).isEqualTo(before100);
        assertThat(store.find(child101.key())).isEqualTo(before101);
    }

    @Test
    void isOfferedAsFormatOfTheExportItself() throws Exception {
        PageSnapshot only = page("confluence", "DEV", "100", null, ACRONYM);
        cache.publish("confluence", "DEV", confirmed("g1"), List.of(only));
        FileSystemExportArtifactWriter writer = new FileSystemExportArtifactWriter(root);
        Exports exports = new Exports(jobs, cache, writer, new ExportConverters(List.of(
                new AnalysisConverter(new EnrichmentAnalyses(enrichments))), ExportConverters.DEFAULT_MAX_BYTES), clock);

        String id = exports.start("confluence", "DEV", "client-a", List.of("analysis")).id();

        JsonNode analysis = json.readTree(root.resolve(id).resolve("analysis.json").toFile());
        assertThat(analysis.path("pages").get(0).path("enrichments").path("acronyms").path("state").asString())
                .isEqualTo("MISSING");
        assertThat(store.find(only.key())).isEmpty();
    }

    /** Scheitert immer. */
    private static final class Broken implements EnrichmentProcessor {

        @Override
        public String id() {
            return "broken";
        }

        @Override
        public String version() {
            return "1";
        }

        @Override
        public EnrichmentOutput process(PageText text) {
            throw new IllegalStateException("kaputt");
        }
    }
}
