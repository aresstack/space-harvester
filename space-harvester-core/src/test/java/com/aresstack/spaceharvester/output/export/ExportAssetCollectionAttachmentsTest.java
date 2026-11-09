package com.aresstack.spaceharvester.output.export;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.configuration.ExportSettings;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest;
import com.aresstack.spaceharvester.application.convert.ExportConverters;
import com.aresstack.spaceharvester.application.export.ExportAssetCollection;
import com.aresstack.spaceharvester.application.export.ExportConversions;
import com.aresstack.spaceharvester.application.export.Exports;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.application.sources.AttachmentFile;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageAttachment;
import com.aresstack.spaceharvester.application.sources.PageAttachments;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import com.aresstack.spaceharvester.output.convert.JsonArrayConverter;
import com.aresstack.spaceharvester.output.convert.JsonPackageConverter;
import com.aresstack.spaceharvester.persistence.files.FileSystemAssetStore;
import com.aresstack.spaceharvester.source.DocumentFixtures;
import com.aresstack.spaceharvester.source.TikaDocumentText;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Dateianhänge im Export (ADR 0023): derselbe Auftrag, eigene {@code attachments.json} mit Original und Text. */
class ExportAssetCollectionAttachmentsTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};
    private static final byte[] PDF = DocumentFixtures.pdf("Betriebshandbuch Stapelverarbeitung");
    private static final byte[] TXT = "Notizen zur Freigabe".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ZIP = {'P', 'K', 3, 4, 0, 0, 0, 0};
    private static final byte[] BIG = new byte[2048];

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-09T02:00:00Z"), ZoneOffset.UTC);
    private final Queue<Runnable> queued = new ArrayDeque<>();
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), queued::add, new JobLimits(2, 10, Duration.ofHours(1)),
            clock);
    private final InMemoryPageCache cache = new InMemoryPageCache();
    private final ExportConverters converters = new ExportConverters(List.of(new JsonArrayConverter(),
            new JsonPackageConverter()), ExportConverters.DEFAULT_MAX_BYTES);
    private final Source source = new Source();

    @TempDir
    Path data;

    @Test
    void documentAttachmentsLandInAttachmentsJsonWithOriginalAndText() throws Exception {
        Result result = export(new ExportSettings(true, ExportSettings.DEFAULT_MEDIA_TYPES, 1024));

        // Die Bilder bleiben, wie sie waren: nur das Bild in assets.json, pages.json unverändert vorhanden.
        assertThat(result.files).containsKey("pages.json");
        assertThat(result.json("assets.json").path("assets")).hasSize(1);
        assertThat(result.json("assets.json").path("assets").get(0).path("title").asString()).isEqualTo("bild.png");

        JsonNode attachments = result.json("attachments.json");
        assertThat(attachments.path("format").asString()).isEqualTo("attachments/v1");
        Map<String, JsonNode> byTitle = new LinkedHashMap<>();
        attachments.path("attachments").forEach(node -> byTitle.put(node.path("title").asString(), node));
        assertThat(byTitle).containsOnlyKeys("handbuch.pdf", "notiz.txt", "archiv.zip", "gross.pdf");

        JsonNode pdf = byTitle.get("handbuch.pdf");
        assertThat(pdf.path("pageId").asString()).isEqualTo("100");
        assertThat(pdf.path("attachmentId").asString()).isEqualTo("d1");
        assertThat(pdf.path("mediaType").asString()).isEqualTo("application/pdf");
        assertThat(pdf.path("status").asString()).isEqualTo("STORED");
        assertThat(pdf.path("reason").isNull()).isTrue();
        assertThat(pdf.path("sha256").asString()).isEqualTo(Assets.sha256(PDF));
        assertThat(pdf.path("size").asLong()).isEqualTo(PDF.length);
        assertThat(pdf.path("path").asString()).isEqualTo("attachments/100/handbuch.pdf");
        assertThat(pdf.path("textPath").asString()).isEqualTo("attachments/100/handbuch.pdf.txt");
        assertThat(result.files.get("attachments/100/handbuch.pdf")).isEqualTo(PDF);
        assertThat(new String(result.files.get("attachments/100/handbuch.pdf.txt"), StandardCharsets.UTF_8))
                .contains("Betriebshandbuch Stapelverarbeitung");

        JsonNode txt = byTitle.get("notiz.txt");
        assertThat(txt.path("status").asString()).isEqualTo("STORED");
        assertThat(result.files.get(txt.path("path").asString())).isEqualTo(TXT);
        assertThat(new String(result.files.get(txt.path("textPath").asString()), StandardCharsets.UTF_8))
                .contains("Notizen zur Freigabe");

        // Ausgeschlossener Typ und zu groß: im Manifest mit Grund, ohne Inhalt und ohne Download.
        JsonNode zip = byTitle.get("archiv.zip");
        assertThat(zip.path("status").asString()).isEqualTo("EXCLUDED");
        assertThat(zip.path("reason").asString()).isEqualTo("media-type-excluded");
        assertThat(zip.path("path").isNull()).isTrue();
        assertThat(zip.path("textPath").isNull()).isTrue();
        assertThat(zip.path("sha256").isNull()).isTrue();
        JsonNode big = byTitle.get("gross.pdf");
        assertThat(big.path("status").asString()).isEqualTo("EXCLUDED");
        assertThat(big.path("reason").asString()).isEqualTo("too-large");
        assertThat(big.path("path").isNull()).isTrue();
        assertThat(source.downloaded).doesNotContain("d3", "d4");
        assertThat(result.files.keySet()).noneMatch(name -> name.contains("archiv") || name.contains("gross"));

        // Ausgeschlossene Dokumente fehlen nicht: die Bilder gelten weiter als vollständig.
        assertThat(result.manifest.complete()).isTrue();
        assertThat(result.manifest.forPage("100")).extracting(entry -> entry.reference().kind())
                .containsOnly(ExportAssetManifest.Kind.ATTACHMENT);
    }

    @Test
    void anUnreadableDocumentKeepsItsOriginalWithReasonTextUnavailable() throws Exception {
        source.brokenPdf = true;
        Result result = export(ExportSettings.DEFAULT);

        JsonNode pdf = find(result.json("attachments.json"), "handbuch.pdf");
        assertThat(pdf.path("status").asString()).isEqualTo("STORED");
        assertThat(pdf.path("reason").asString()).isEqualTo("text-unavailable");
        assertThat(pdf.path("textPath").isNull()).isTrue();
        assertThat(result.files).containsKey(pdf.path("path").asString());
        assertThat(result.files.keySet()).noneMatch(name -> name.startsWith("attachments/100/handbuch.pdf.txt"));
    }

    @Test
    void disabledThereAreNoAttachmentEntriesAndNoDocumentIsDownloaded() throws Exception {
        Result result = export(new ExportSettings(false, ExportSettings.DEFAULT_MEDIA_TYPES,
                ExportSettings.DEFAULT_MAX_SIZE));

        assertThat(result.json("attachments.json").path("attachments")).isEmpty();
        assertThat(result.manifest.documents()).isEmpty();
        assertThat(result.files.keySet()).noneMatch(name -> name.startsWith("attachments/"));
        assertThat(source.downloaded).containsOnly("img");
    }

    @Test
    void awkwardFileNamesBecomeSafeUniquePaths() throws Exception {
        source.awkward = true;
        Result result = export(ExportSettings.DEFAULT);

        List<String> paths = new ArrayList<>();
        result.json("attachments.json").path("attachments").forEach(node -> {
            if (node.path("title").asString().startsWith("Bericht")) {
                paths.add(node.path("path").asString());
                paths.add(node.path("textPath").asString());
            }
        });
        assertThat(paths).hasSize(10).doesNotHaveDuplicates().allSatisfy(path -> {
            assertThat(path).startsWith("attachments/100/").doesNotContain(" ", "&", "+", "%", "..");
            assertThat(path.substring("attachments/100/".length())).matches("[\\p{L}\\p{N}._-]+");
            assertThat(result.files).containsKey(path);
        });
        assertThat(paths).contains("attachments/100/Bericht_Ä_Ö.txt");
    }

    private static JsonNode find(JsonNode attachments, String title) {
        for (JsonNode node : attachments.path("attachments")) {
            if (node.path("title").asString().equals(title)) {
                return node;
            }
        }
        throw new AssertionError("kein Anhang " + title);
    }

    private Result export(ExportSettings settings) throws Exception {
        FileSystemExportArtifactWriter writer = new FileSystemExportArtifactWriter(data.resolve("exports"));
        SourceCatalog catalog = new SourceCatalog() {
            @Override
            public List<HarvestSource> all() {
                return List.of(source);
            }

            @Override
            public Optional<HarvestSource> find(String sourceId) {
                return Optional.of(source);
            }
        };
        Assets assets = new Assets(catalog, new FileSystemAssetStore(data.resolve("assets")), 1_000_000, clock, null,
                new TikaDocumentText());
        ExportAssetCollection collection = new ExportAssetCollection(jobs, writer, new SourceQueries(catalog), assets,
                (owner, sourceId) -> true, com.aresstack.spaceharvester.application.signin.SignInRequests.none(),
                () -> settings);
        Exports exports = new Exports(jobs, cache, writer, converters, clock);
        exports.onPublished(collection::start);
        ExportConversions conversions = new ExportConversions(jobs, writer, converters, collection);
        cache.publish("confluence", "DEV", confirmed("g1"), List.of(page("confluence", "DEV", "100", null)));
        JobStatus export = exports.start("confluence", "DEV", "client-a", List.of("json"));
        while (!queued.isEmpty()) {
            queued.poll().run();
        }
        ExportConversions.ConvertedFile zip = conversions.convert(export.id(), "client-a", JsonPackageConverter.FORMAT,
                data.resolve("out"));
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip.path()))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                in.transferTo(out);
                files.put(entry.getName(), out.toByteArray());
            }
        }
        return new Result(files, writer.assets(export.id()).orElseThrow());
    }

    private record Result(Map<String, byte[]> files, ExportAssetManifest manifest) {

        JsonNode json(String name) {
            return JsonMapper.builder().build().readTree(files.get(name));
        }
    }

    /** Seite 100 mit einem Bild und Dokument-Anhängen. */
    private static final class Source implements HarvestSource {

        final Set<String> downloaded = new HashSet<>();
        boolean brokenPdf;
        boolean awkward;

        @Override
        public String id() {
            return "confluence";
        }

        @Override
        public String type() {
            return "confluence";
        }

        @Override
        public String title() {
            return "Wiki";
        }

        @Override
        public List<Partition> partitions() {
            return List.of(new Partition("DEV", "Entwicklung", null));
        }

        @Override
        public PageMetadataBatch pageMetadata(String partitionKey, String cursor, int limit) {
            return new PageMetadataBatch(List.of(), null);
        }

        @Override
        public PageMetadata pageMetadata(String pageId) {
            return new PageMetadata(pageId, "confluence:wiki/page/" + pageId, "Seite " + pageId, "DEV", null, "3",
                    null, null, PageMetadata.sorted(Map.of()));
        }

        @Override
        public PageAttachments attachments(String pageId) {
            List<PageAttachment> list = new ArrayList<>();
            list.add(attachment("img", "bild.png", "image/png", PNG.length));
            if (awkward) {
                int i = 0;
                for (String name : List.of("Bericht & Plan.txt", "Bericht Plan.txt", "Bericht Ä Ö.txt",
                        "Bericht+Plan.txt", "Bericht%Plan.txt")) {
                    list.add(attachment("w" + i++, name, "text/plain", TXT.length));
                }
            } else {
                list.add(attachment("d1", "handbuch.pdf", "application/pdf", PDF.length));
                list.add(attachment("d2", "notiz.txt", "text/plain; charset=UTF-8", TXT.length));
                list.add(attachment("d3", "archiv.zip", "application/zip", ZIP.length));
                list.add(attachment("d4", "gross.pdf", "application/pdf", BIG.length));
            }
            return new PageAttachments(pageId, list, List.of(), "application/json", "test", List.of(),
                    Instant.parse("2026-11-09T01:00:00Z"));
        }

        private static PageAttachment attachment(String id, String title, String type, long size) {
            return new PageAttachment(id, "confluence:wiki/attachment/" + id, title, type, size, "1", null, null);
        }

        @Override
        public AttachmentFile attachmentFile(PageAttachment attachment, long maxBytes) {
            downloaded.add(attachment.attachmentId());
            byte[] content = switch (attachment.attachmentId()) {
                case "img" -> PNG;
                case "d1" -> brokenPdf ? "%PDF-1.7\nkaputt".getBytes(StandardCharsets.US_ASCII) : PDF;
                case "d3" -> ZIP;
                case "d4" -> BIG;
                default -> TXT;
            };
            return new AttachmentFile(attachment, content, Instant.parse("2026-11-09T01:00:00Z"));
        }

        @Override
        public List<ViewImage> viewImages(String pageId) {
            return List.of();
        }

        @Override
        public ReadingPage readingPage(String pageId) {
            return new ReadingPage("3", "<p>Seite</p>", "", "Seite", List.of());
        }

        @Override
        public byte[] viewImage(ViewImage image, long maxBytes) {
            return PNG;
        }
    }
}
