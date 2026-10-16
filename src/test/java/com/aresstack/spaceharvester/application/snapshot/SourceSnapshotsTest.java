package com.aresstack.spaceharvester.application.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.StoredPage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Aufbau des Snapshots aus der Antwort der Quelle, unabhängig vom Quellentyp. */
class SourceSnapshotsTest {

    private static final Instant RETRIEVED = Instant.parse("2026-10-16T20:00:00Z");

    @Test
    void snapshotKeepsTheSourceResponseAndItsRetrievalTime() {
        byte[] bytes = "{\"id\":\"7\"}".getBytes(StandardCharsets.UTF_8);
        PageSnapshot snapshot = snapshots(new StoredPage(page("7"), bytes, "application/json", "test+json", RETRIEVED))
                .fetch("wiki", "7");

        assertThat(snapshot.key()).isEqualTo(new PageKey("wiki", "7"));
        assertThat(snapshot.payload()).isEqualTo(new NativePayload(bytes, "application/json", "test+json"));
        assertThat(snapshot.retrievedAt()).isEqualTo(RETRIEVED);
    }

    @Test
    void responseForAnotherPageIsAnInvalidResponse() {
        SourceSnapshots snapshots = snapshots(new StoredPage(page("8"), new byte[] {'{', '}'}, "application/json",
                "test+json", RETRIEVED));

        assertThatThrownBy(() -> snapshots.fetch("wiki", "7")).isInstanceOfSatisfying(SourceAccessException.class,
                e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.INVALID_RESPONSE));
    }

    @Test
    void storedPageNeverShowsItsPayload() {
        StoredPage page = new StoredPage(page("7"), "{\"geheim\":1}".getBytes(StandardCharsets.UTF_8),
                "application/json", "test+json", RETRIEVED);

        assertThat(page.toString()).doesNotContain("geheim").contains("bytes");
    }

    private static PageMetadata page(String pageId) {
        return new PageMetadata(pageId, "test:" + pageId, "Titel", "DEV", null, "1", null, null,
                PageMetadata.sorted(Map.of()));
    }

    private static SourceSnapshots snapshots(StoredPage answer) {
        HarvestSource source = new HarvestSource() {
            @Override
            public String id() {
                return "wiki";
            }

            @Override
            public String type() {
                return "test";
            }

            @Override
            public String title() {
                return "Wiki";
            }

            @Override
            public List<Partition> partitions() {
                return List.of();
            }

            @Override
            public PageMetadataBatch pageMetadata(String partitionKey, String cursor, int limit) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PageMetadata pageMetadata(String pageId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public StoredPage storedPage(String pageId) {
                return answer;
            }
        };
        return new SourceSnapshots(new SourceCatalog() {
            @Override
            public List<HarvestSource> all() {
                return List.of(source);
            }

            @Override
            public Optional<HarvestSource> find(String sourceId) {
                return Optional.of(source).filter(s -> s.id().equals(sourceId));
            }
        });
    }
}
