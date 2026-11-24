package com.aresstack.spaceharvester.application.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.snapshot.LargeFiles;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.snapshot.SourceSnapshots;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import com.aresstack.spaceharvester.application.sources.StoredPage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Angelo 20.11.2026: Eine Datei über der Grenze „Größte Datei im Cache“ wird nicht heruntergeladen; an ihrer Stelle
 * steht ein Snapshot nur mit Kopfdaten. Passt sie nach dem Erhöhen der Grenze, lädt das nächste Öffnen sie ganz.
 */
class LargeFilesTest {

    private static final PageMetadata FIG = file("fig", 40_268_060);
    private static final PageMetadata NOTE = file("note", 1_000);
    private static final PageMetadata PDF = file("pdf", 30_000_000);

    @Test
    void oneNoteIsRecognizedAboveTheCacheLimit() {
        PageMetadata metadata = new PageMetadata("notes", "source:notes", "Notes.ONE", "space", null,
                "1", Instant.now(), null, PageMetadata.sorted(Map.of(PageMetadata.RESOURCE_KIND, PageMetadata.FILE_KIND,
                LargeFiles.FILE_SIZE, "26214400")));
        assertThat(LargeFiles.viewableType(metadata)).isEqualTo("application/onenote");
        assertThat(LargeFiles.tooLarge(metadata, 20L << 20)).isTrue();
        assertThat(com.aresstack.spaceharvester.source.OneNotePreviewReader.supportsSize(20L << 20)).isTrue();
        assertThat(com.aresstack.spaceharvester.source.OneNotePreviewReader.supportsSize(LargeFiles.size(metadata)))
                .isFalse();
    }

    private int downloads;
    private int extracts;
    /** So lange braucht jeder Abruf der Quelle. */
    private volatile long slowMillis;
    private final HarvestSource source = new HarvestSource() {
        @Override
        public String id() {
            return "bscw";
        }

        @Override
        public String type() {
            return "fake";
        }

        @Override
        public String title() {
            return "BSCW";
        }

        @Override
        public List<Partition> partitions() {
            return List.of();
        }

        @Override
        public PageMetadataBatch pageMetadata(String partitionKey, String cursor, int limit) {
            return new PageMetadataBatch(List.of(FIG, NOTE), null);
        }

        @Override
        public PageMetadata pageMetadata(String pageId) {
            return pageId.equals("fig") ? FIG : pageId.equals("pdf") ? PDF : NOTE;
        }

        @Override
        public Optional<String> downloadOriginal(String pageId, java.nio.file.Path target,
                                                 java.util.function.Predicate<String> wanted, long maxBytes) {
            // Die Figma-Datei hat keinen lesbaren Text: nichts laden. Das „PDF“ ist hier eine Textdatei.
            String type = pageId.equals("pdf") ? "text/plain" : "application/octet-stream";
            if (!wanted.test(type)) {
                return Optional.empty();
            }
            extracts++;
            pause();
            try {
                java.nio.file.Files.writeString(target, "Haushaltsplan Datenhaus", StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return Optional.of(type);
        }

        @Override
        public StoredPage storedPage(String pageId) {
            downloads++;
            pause();
            return new StoredPage(pageMetadata(pageId), new byte[] {1, 2, 3}, "application/octet-stream", "stored",
                    Instant.parse("2026-11-20T10:00:00Z"));
        }
    };
    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of(source);
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return source.id().equals(sourceId) ? Optional.of(source) : Optional.empty();
        }
    };
    private final AtomicLong limit = new AtomicLong(20L << 20);
    private final AtomicLong textLimit = new AtomicLong(200L << 20);
    private final SourceSnapshots snapshots = new SourceSnapshots(catalog);

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path temporary;

    @org.junit.jupiter.api.BeforeEach
    void wire() {
        snapshots.maxBytes(limit::get);
        snapshots.maxTextBytes(textLimit::get);
        // Der echte Weg: Original auf die Platte, Extraktion als Strom (ADR 0026), Datei danach gelöscht.
        snapshots.largeFileText(new com.aresstack.spaceharvester.source.LargeFileTexts(
                new com.aresstack.spaceharvester.source.TikaDocumentStructure(),
                () -> com.aresstack.spaceharvester.application.assets.ExtractionLimits.DEFAULTS,
                temporary.resolve("large-files"))::text);
    }

    private void pause() {
        try {
            Thread.sleep(slowMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void aDocumentStoredWithItsHeaderOnlyGetsItsTextExtractOnTheNextOpen() {
        PageFreshness freshness = new PageFreshness(new SourceQueries(catalog, FakeSource.CLOCK), snapshots,
                FakeSource.CLOCK);
        PageSnapshot old = LargeFiles.placeholder("bscw", PDF, Instant.parse("2026-11-20T09:00:00Z"));

        PageFreshness.Refreshed opened = freshness.check("bscw", "pdf", Optional.of(old.header()), Optional.of(old));
        assertThat(LargeFiles.textOnly(opened.snapshot().payload())).isTrue();

        PageFreshness.Refreshed again = freshness.check("bscw", "pdf", Optional.of(opened.header()),
                Optional.of(opened.snapshot()));
        assertThat(again.outcome()).as("der Textauszug bleibt").isEqualTo(PageFreshness.Outcome.CURRENT);
        assertThat(extracts).isEqualTo(1);
    }

    @Test
    void theViewerGetsTheOriginalOfALargeFileFromTheSourceWithoutStoringIt() {
        assertThat(new String(snapshots.original("bscw", PDF), StandardCharsets.UTF_8))
                .isEqualTo("Haushaltsplan Datenhaus");
        assertThat(temporary.resolve("large-files")).doesNotExist();
        assertThat(LargeFiles.viewableType(file("x", 1))).as("ohne bekannte Endung").isNull();

        textLimit.set(10L << 20);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> snapshots.original("bscw", PDF))
                .isInstanceOf(com.aresstack.spaceharvester.application.sources.SourceAccessException.class);
    }

    @Test
    void aLargeFileGetsItsOwnLongerDeadlineForTheTextExtract() {
        slowMillis = 400;
        PageFreshness freshness = new PageFreshness(new SourceQueries(catalog, FakeSource.CLOCK), snapshots,
                FakeSource.CLOCK, java.time.Duration.ofMillis(100), java.time.Duration.ofSeconds(20));
        PageSnapshot header = LargeFiles.placeholder("bscw", PDF, Instant.parse("2026-11-20T10:00:00Z"));

        PageFreshness.Refreshed pdf = freshness.check("bscw", "pdf", Optional.of(header.header()), Optional.empty());
        assertThat(LargeFiles.textOnly(pdf.snapshot().payload())).isTrue();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> freshness.check("bscw", "note",
                        Optional.of(snapshotOf(NOTE).header()), Optional.empty()))
                .isInstanceOf(com.aresstack.spaceharvester.application.sources.SourceAccessException.class)
                .hasMessageContaining("Frist");
    }

    private static PageSnapshot snapshotOf(PageMetadata metadata) {
        return PageSnapshot.of("bscw", metadata, new com.aresstack.spaceharvester.application.snapshot.NativePayload(
                new byte[] {1}, "application/octet-stream", "stored"), Instant.parse("2026-11-20T10:00:00Z"));
    }

    private static PageMetadata file(String pageId, long size) {
        return new PageMetadata(pageId, "bscw:host/" + pageId, pageId + ".bin", "space", null, "v1",
                Instant.parse("2025-04-09T11:29:02Z"), URI.create("https://bscw.example.org/" + pageId),
                PageMetadata.sorted(Map.of(PageMetadata.RESOURCE_KIND, PageMetadata.FILE_KIND,
                        LargeFiles.FILE_SIZE, String.valueOf(size))));
    }

    @Test
    void aFileOverTheLimitIsNotDownloadedAndOnlyItsHeaderIsKept() {
        PageSnapshot big = snapshots.fetch("bscw", FIG);
        PageSnapshot small = snapshots.fetch("bscw", NOTE);

        assertThat(LargeFiles.placeholder(big.payload())).isTrue();
        assertThat(big.payload().length()).isZero();
        assertThat(big.revision()).isEqualTo("v1");
        assertThat(big.key()).isEqualTo(new PageKey("bscw", "fig"));
        assertThat(LargeFiles.placeholder(small.payload())).isFalse();
        assertThat(downloads).as("nur die kleine Datei geladen").isEqualTo(1);
    }

    @Test
    void aLargeDocumentWithTextKeepsOnlyItsTextExtractWhichTheSearchProjects() {
        PageSnapshot pdf = snapshots.fetch("bscw", PDF);

        assertThat(LargeFiles.textOnly(pdf.payload())).isTrue();
        assertThat(LargeFiles.placeholder(pdf.payload())).as("das Original fehlt").isTrue();
        assertThat(pdf.revision()).isEqualTo("v1");
        assertThat(new com.aresstack.spaceharvester.source.KnowledgeDocumentTextProjector().project(pdf))
                .hasValueSatisfying(text -> assertThat(text.text()).isEqualTo("Haushaltsplan Datenhaus"));
        assertThat(downloads).as("nie das Original im Speicher").isZero();
        assertThat(extracts).isEqualTo(1);
        assertThat(temporary.resolve("large-files")).as("temporäres Original gelöscht").isEmptyDirectory();
        assertThat(LargeFiles.textOnly(snapshots.fetch("bscw", FIG).payload())).as("Figma ohne Text").isFalse();
        assertThat(extracts).as("Figma gar nicht geladen").isEqualTo(1);

        textLimit.set(10L << 20);
        assertThat(LargeFiles.textOnly(snapshots.fetch("bscw", PDF).payload()))
                .as("über der Grenze für den Textauszug nur die Kopfdaten").isFalse();
        assertThat(extracts).isEqualTo(1);
    }

    @Test
    void withoutAReportedSizeOrForFoldersTheFileIsLoaded() {
        PageMetadata unknown = new PageMetadata("x", "bscw:host/x", "x", "space", null, "v1", null, null,
                PageMetadata.sorted(Map.of(PageMetadata.RESOURCE_KIND, PageMetadata.FILE_KIND)));
        assertThat(snapshots.loadsWhole(unknown)).isTrue();
        limit.set(0);
        assertThat(snapshots.loadsWhole(FIG)).as("ohne Grenze").isTrue();
    }

    @Test
    void aStoredPlaceholderIsLoadedOnceTheFileFitsUnderARaisedLimit() {
        PageFreshness freshness = new PageFreshness(new SourceQueries(catalog, FakeSource.CLOCK), snapshots,
                FakeSource.CLOCK);
        PageSnapshot placeholder = snapshots.fetch("bscw", FIG);

        PageFreshness.Refreshed unchanged = freshness.check("bscw", "fig", Optional.of(placeholder.header()),
                Optional.of(placeholder));
        assertThat(unchanged.outcome()).isEqualTo(PageFreshness.Outcome.CURRENT);
        assertThat(downloads).isZero();

        limit.set(100L << 20);
        PageFreshness.Refreshed loaded = freshness.check("bscw", "fig", Optional.of(placeholder.header()),
                Optional.of(placeholder));
        assertThat(loaded.outcome()).isEqualTo(PageFreshness.Outcome.CONTENT_CHANGED);
        assertThat(LargeFiles.placeholder(loaded.snapshot().payload())).isFalse();
        assertThat(downloads).isEqualTo(1);
    }
}
