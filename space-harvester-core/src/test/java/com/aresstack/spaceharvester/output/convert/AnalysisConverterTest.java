package com.aresstack.spaceharvester.output.convert;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.assets.AssetStoreException;
import com.aresstack.spaceharvester.application.assets.ImageDescription;
import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ConvertiblePage;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest;
import com.aresstack.spaceharvester.application.convert.ExportAssets;
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
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.tree.PageTree;
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
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
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
                + p.path("version").asString()).toList()).containsExactly("acronyms@3", "outline@1", "broken@1");
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
        assertThat(first.path("facts").valueStream().map(f -> f.path("state").asString()))
                .isNotEmpty().containsOnly("CURRENT");

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

    @Test
    void listsStoredDescriptionsOfTheExportedImageBytesEvenWithoutPageImageAnalysis() {
        PageSnapshot only = page("confluence", "DEV", "100", null, ACRONYM);
        ImageDescription plan = new ImageDescription("sha256:aa", "openai-compatible-vision-v1", "vision-1",
                "Ein Bauplan", clock.instant());
        // Einzeln beschrieben: im Asset-Speicher, kein Ergebnis "images" an der Seite.
        Map<String, ImageDescription> described = Map.of("sha256:aa", plan);
        // Ein unlesbarer Eintrag lässt nur dieses Bild ohne Beschreibung.
        AnalysisConverter converter = new AnalysisConverter(new EnrichmentAnalyses(enrichments, null, sha256 -> {
            if (sha256.equals("sha256:cc")) {
                throw new AssetStoreException("kaputt", null);
            }
            return Optional.ofNullable(described.get(sha256));
        }));
        ExportAssetManifest manifest = new ExportAssetManifest(true, List.of(
                new ExportAssetManifest.Entry("100", ExportAssetManifest.Reference.attachment("att1", "plan.png"),
                        "att1", "2", "plan.png", "image/png", 3, "sha256:aa", ExportAssetManifest.Status.STORED,
                        null),
                // Andere Bytes (ersetztes Bild): keine Beschreibung, kein Eintrag.
                new ExportAssetManifest.Entry("100", ExportAssetManifest.Reference.attachment("att2", "neu.png"),
                        "att2", "1", "neu.png", "image/png", 3, "sha256:bb", ExportAssetManifest.Status.STORED,
                        null),
                new ExportAssetManifest.Entry("100", ExportAssetManifest.Reference.attachment("att4", "kaputt.png"),
                        "att4", "1", "kaputt.png", "image/png", 3, "sha256:cc", ExportAssetManifest.Status.STORED,
                        null),
                new ExportAssetManifest.Entry("100", ExportAssetManifest.Reference.attachment("att3", "weg.png"),
                        "att3", null, "weg.png", "image/png", 0, null, ExportAssetManifest.Status.MISSING,
                        "not-found")));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        converter.convert(new Input(only, manifest), out);
        JsonNode images = json.readTree(out.toByteArray()).path("pages").get(0).path("images");

        assertThat(images.size()).isEqualTo(1);
        assertThat(images.get(0).path("attachmentId").asString()).isEqualTo("att1");
        assertThat(images.get(0).path("sha256").asString()).isEqualTo("sha256:aa");
        assertThat(images.get(0).path("describer").asString()).isEqualTo("openai-compatible-vision-v1");
        assertThat(images.get(0).path("modelVersion").asString()).isEqualTo("vision-1");
        assertThat(images.get(0).path("description").asString()).isEqualTo("Ein Bauplan");
        assertThat(store.find(only.key())).isEmpty();
    }

    /** Eine Seite mit Bildern im Datenbestand. */
    private record Input(PageSnapshot page, ExportAssetManifest manifest) implements ConversionInput {

        @Override
        public String sourceId() {
            return "confluence";
        }

        @Override
        public String spaceKey() {
            return "DEV";
        }

        @Override
        public PageTree tree() {
            return new PageTree(List.of(), List.of(), 1, 0);
        }

        @Override
        public List<PageHeader> headers() {
            return List.of(page.header());
        }

        @Override
        public void forEachPage(Consumer<ConvertiblePage> action) {
            action.accept(new ConvertiblePage(page.header(), page, 0));
        }

        @Override
        public ExportAssets assets() {
            return new ExportAssets() {
                @Override
                public Optional<ExportAssetManifest> manifest() {
                    return Optional.of(manifest);
                }

                @Override
                public Optional<byte[]> content(ExportAssetManifest.Entry entry) {
                    return Optional.empty();
                }
            };
        }
    }

    @Test
    void factsOfANewerVersionAreMarkedStaleAgainstTheExportedOne() throws Exception {
        PageSnapshot exported = page("confluence", "DEV", "100", null, ACRONYM);
        cache.publish("confluence", "DEV", confirmed("g1"), List.of(exported));
        enrichments.refresh(exported);
        FileSystemExportArtifactWriter writer = new FileSystemExportArtifactWriter(root);
        ExportConverters converters = new ExportConverters(List.of(new JsonArrayConverter(),
                new AnalysisConverter(new EnrichmentAnalyses(enrichments))), ExportConverters.DEFAULT_MAX_BYTES);
        String id = new Exports(jobs, cache, writer, converters, clock)
                .start("confluence", "DEV", "client-a", List.of("json")).id();

        // Erst nach dem Export kommt eine neue Fassung, deren Fakten die alten ersetzen.
        enrichments.refresh(page("confluence", "DEV", "100", null,
                "{\"text\":\"Das CI (Continuous Integration) ist neu.\"}".getBytes(StandardCharsets.UTF_8)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ExportConversions(jobs, writer, converters).convert(id, "client-a", AnalysisConverter.FORMAT, out);
        JsonNode page = json.readTree(out.toByteArray()).path("pages").get(0);

        assertThat(page.path("inputSha256").asString())
                .isEqualTo(PROJECTOR.project(exported).orElseThrow().inputSha256());
        assertThat(page.path("enrichments").path("acronyms").path("state").asString()).isEqualTo("STALE");
        assertThat(page.path("facts")).isNotEmpty().allSatisfy(fact -> {
            assertThat(fact.path("state").asString()).isEqualTo("STALE");
            assertThat(fact.path("inputSha256").asString()).isNotEqualTo(page.path("inputSha256").asString());
        });
    }

    @Test
    void factsOfAnOlderProcessorVersionAreStaleEvenWithTheSameText() throws Exception {
        PageSnapshot exported = page("confluence", "DEV", "100", null, ACRONYM);
        cache.publish("confluence", "DEV", confirmed("g1"), List.of(exported));
        // Dieselbe Seite, berechnet von einer älteren Fassung des Prozessors.
        new Enrichments(PROJECTOR, List.of(new OlderVersion(new AcronymProcessor())), store, clock).refresh(exported);
        FileSystemExportArtifactWriter writer = new FileSystemExportArtifactWriter(root);
        ExportConverters converters = new ExportConverters(List.of(
                new AnalysisConverter(new EnrichmentAnalyses(enrichments))), ExportConverters.DEFAULT_MAX_BYTES);
        String id = new Exports(jobs, cache, writer, converters, clock)
                .start("confluence", "DEV", "client-a", List.of("analysis")).id();

        JsonNode page = json.readTree(root.resolve(id).resolve("analysis.json").toFile()).path("pages").get(0);

        assertThat(page.path("enrichments").path("acronyms").path("state").asString()).isEqualTo("STALE");
        assertThat(page.path("facts")).isNotEmpty().allSatisfy(fact -> {
            assertThat(fact.path("inputSha256").asString()).isEqualTo(page.path("inputSha256").asString());
            assertThat(fact.path("state").asString()).isEqualTo("STALE");
        });
    }

    /** Ein Prozessor mit einer älteren Version als der aktuelle. */
    private record OlderVersion(EnrichmentProcessor processor) implements EnrichmentProcessor {

        @Override
        public String id() {
            return processor.id();
        }

        @Override
        public String version() {
            return "older-" + processor.version();
        }

        @Override
        public EnrichmentOutput process(PageText text) {
            return processor.process(text);
        }
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
