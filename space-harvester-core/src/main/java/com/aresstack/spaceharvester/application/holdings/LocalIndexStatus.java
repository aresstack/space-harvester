package com.aresstack.spaceharvester.application.holdings;

import com.aresstack.spaceharvester.application.assets.ExtractionStatus;
import com.aresstack.spaceharvester.application.query.PageTextIndex;
import com.aresstack.spaceharvester.application.query.PageTextIndexing;

/** Resolve the actual index revision and content availability from lightweight stored metadata. */
public final class LocalIndexStatus {
    private LocalIndexStatus() {
    }

    public static IndexState state(HoldingsCatalog.CatalogPage page, String spaceKey, HoldingState holding,
                                   PageTextIndex.ContentState indexed) {
        if (page.folder()) {
            return IndexState.FOLDER;
        }
        if (holding == HoldingState.LOCKED) {
            return IndexState.LOCKED;
        }
        if (holding == HoldingState.OUTDATED) {
            return IndexState.OUTDATED;
        }
        if (holding != HoldingState.LOCAL) {
            return IndexState.NOT_INDEXED;
        }
        if (page.extractionStatus() == ExtractionStatus.FAILED) {
            return IndexState.FAILED;
        }
        if (page.extractionStatus() == ExtractionStatus.UNSUPPORTED
                || page.extractionStatus() == ExtractionStatus.EXCLUDED) {
            return IndexState.NO_TEXT;
        }
        if (indexed == null || !indexed.verified() || page.rawSha256() == null || page.extractionStatus() == null
                || !indexed.inputSha256().equals(PageTextIndexing.entryState(
                        page.rawSha256(), spaceKey, page.title()))) {
            return IndexState.NOT_INDEXED;
        }
        if (!indexed.hasText()) {
            return IndexState.NO_TEXT;
        }
        return page.extractionStatus() == ExtractionStatus.PARTIAL ? IndexState.PARTIAL : IndexState.INDEXED;
    }
}
