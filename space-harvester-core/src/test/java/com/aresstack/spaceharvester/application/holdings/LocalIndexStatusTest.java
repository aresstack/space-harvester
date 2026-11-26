package com.aresstack.spaceharvester.application.holdings;

import com.aresstack.spaceharvester.application.assets.ExtractionStatus;
import com.aresstack.spaceharvester.application.query.PageTextIndex;
import com.aresstack.spaceharvester.application.query.PageTextIndexing;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalIndexStatusTest {
    private static final String SPACE = "docs";
    private static final String HASH = "sha256:reference";
    private static final String TITLE = "test.pdf";
    private static final PageTextIndex.ContentState INDEXED = new PageTextIndex.ContentState(
            PageTextIndexing.entryState(HASH, SPACE, TITLE), true);

    private static HoldingsCatalog.CatalogPage page(ExtractionStatus status, boolean folder) {
        return new HoldingsCatalog.CatalogPage("file", null, true, "2", "2", HASH, TITLE, folder, status);
    }

    @Test
    void greenRequiresMatchingPrimaryTextAndCompleteExtraction() {
        assertThat(LocalIndexStatus.state(page(ExtractionStatus.OK, false), SPACE, HoldingState.LOCAL, INDEXED))
                .isEqualTo(IndexState.INDEXED);
        assertThat(LocalIndexStatus.state(page(ExtractionStatus.OK, false), SPACE, HoldingState.LOCAL, null))
                .isEqualTo(IndexState.NOT_INDEXED);
        assertThat(LocalIndexStatus.state(page(ExtractionStatus.OK, false), SPACE, HoldingState.LOCAL,
                new PageTextIndex.ContentState("old", true))).isEqualTo(IndexState.NOT_INDEXED);
        assertThat(LocalIndexStatus.state(page(ExtractionStatus.OK, false), SPACE, HoldingState.LOCAL,
                new PageTextIndex.ContentState(INDEXED.inputSha256(), true, false))).isEqualTo(IndexState.NOT_INDEXED);
        assertThat(LocalIndexStatus.state(page(ExtractionStatus.OK, false), SPACE, HoldingState.OUTDATED, INDEXED))
                .isEqualTo(IndexState.OUTDATED);
        assertThat(LocalIndexStatus.state(page(ExtractionStatus.OK, false), SPACE, HoldingState.LOCKED, INDEXED))
                .isEqualTo(IndexState.LOCKED);
        assertThat(LocalIndexStatus.state(page(ExtractionStatus.PARTIAL, false), SPACE, HoldingState.LOCAL, INDEXED))
                .isEqualTo(IndexState.PARTIAL);
        assertThat(LocalIndexStatus.state(page(ExtractionStatus.FAILED, false), SPACE, HoldingState.LOCAL, INDEXED))
                .isEqualTo(IndexState.FAILED);
        assertThat(LocalIndexStatus.state(page(ExtractionStatus.UNSUPPORTED, false), SPACE, HoldingState.LOCAL, INDEXED))
                .isEqualTo(IndexState.NO_TEXT);
        assertThat(LocalIndexStatus.state(page(ExtractionStatus.OK, false), SPACE, HoldingState.LOCAL,
                new PageTextIndex.ContentState(INDEXED.inputSha256(), false))).isEqualTo(IndexState.NO_TEXT);
        assertThat(LocalIndexStatus.state(page(ExtractionStatus.OK, true), SPACE, HoldingState.LOCAL, INDEXED))
                .isEqualTo(IndexState.FOLDER);
    }
}
