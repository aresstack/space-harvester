package com.aresstack.spaceharvester.application.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Eine Inventur ist nur nach allen Cursorn und ohne Widersprüche bestätigt; sonst gibt es keinen Fingerprint. */
class InventoryCollectorTest {

    private final FakeSource source = new FakeSource()
            .put("1", "DOC", null, "3", "a")
            .put("2", "DOC", "1", "1", null)
            .put("3", "DOC", null, "7", null)
            .put("4", "DOC", "3", "2", "b,c")
            .put("5", "DOC", "4", "1", null)
            .put("9", "OPS", null, "1", null);

    private Inventory collect() {
        return new InventoryCollector(source.queries(), FakeSource.CLOCK, 2).collect("wiki", "DOC");
    }

    @Test
    void readsAllCursorsIncludingSeveralRoots() {
        Inventory inventory = collect();

        assertThat(inventory.status()).isEqualTo(Inventory.Status.COMPLETE);
        assertThat(inventory.problem()).isNull();
        assertThat(inventory.pages()).extracting(entry -> entry.page().pageId())
                .containsExactly("1", "2", "3", "4", "5");
        assertThat(inventory.spaceFingerprint()).isPresent();
    }

    @Test
    void anAbortedListingIsIncompleteAndHasNoFingerprint() {
        source.failListingAtCall(1);

        Inventory inventory = collect();

        assertThat(inventory.status()).isEqualTo(Inventory.Status.INCOMPLETE);
        assertThat(inventory.problem()).contains("UNAVAILABLE");
        assertThat(inventory.pages()).extracting(entry -> entry.page().pageId()).containsExactly("1", "2");
        assertThat(inventory.confirmed()).isFalse();
        assertThat(inventory.spaceFingerprint()).isEmpty();
    }

    @Test
    void aPageFromAnotherSpaceMakesItInconsistent() {
        source.tamper = batch -> batch.nextCursor() == null ? withExtra(batch, FakeSource.page("9", "OPS", null,
                "1", null)) : batch;

        Inventory inventory = collect();

        assertThat(inventory.status()).isEqualTo(Inventory.Status.INCONSISTENT);
        assertThat(inventory.problem()).contains("9", "OPS");
        assertThat(inventory.spaceFingerprint()).isEmpty();
    }

    @Test
    void aPageChangedBetweenBatchesMakesItInconsistent() {
        source.tamper = batch -> batch.nextCursor() == null ? withExtra(batch, FakeSource.page("1", "DOC", null,
                "4", "a")) : batch;

        Inventory inventory = collect();

        assertThat(inventory.status()).isEqualTo(Inventory.Status.INCONSISTENT);
        assertThat(inventory.problem()).contains("Seite 1");
    }

    @Test
    void anIdenticalDuplicateFromOverlappingBatchesCountsOnce() {
        source.tamper = batch -> batch.nextCursor() == null ? withExtra(batch, FakeSource.page("1", "DOC", null,
                "3", "a")) : batch;

        Inventory inventory = collect();

        assertThat(inventory.status()).isEqualTo(Inventory.Status.COMPLETE);
        assertThat(inventory.pages()).hasSize(5);
    }

    @Test
    void aRepeatedCursorMakesItInconsistentInsteadOfLoopingForever() {
        source.tamper = batch -> new PageMetadataBatch(batch.pages(), "2");

        Inventory inventory = collect();

        assertThat(inventory.status()).isEqualTo(Inventory.Status.INCONSISTENT);
        assertThat(inventory.problem()).contains("Cursor");
    }

    private static PageMetadataBatch withExtra(PageMetadataBatch batch,
                                               PageMetadata extra) {
        List<PageMetadata> pages = new ArrayList<>(batch.pages());
        pages.add(extra);
        return new PageMetadataBatch(pages, batch.nextCursor());
    }
}
