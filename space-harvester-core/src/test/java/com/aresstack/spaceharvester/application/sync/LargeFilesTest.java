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

    private int downloads;
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
            return pageId.equals("fig") ? FIG : NOTE;
        }

        @Override
        public StoredPage storedPage(String pageId) {
            downloads++;
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
    private final SourceSnapshots snapshots = new SourceSnapshots(catalog);

    {
        snapshots.maxBytes(limit::get);
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
