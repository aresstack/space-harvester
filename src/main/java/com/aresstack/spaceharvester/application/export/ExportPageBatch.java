package com.aresstack.spaceharvester.application.export;

import java.util.List;

/**
 * Ein Stapel Seiten aus dem Cache.
 *
 * @param pages      lieferbare Seiten
 * @param failures   Seiten dieses Stapels, die nicht geliefert werden können
 * @param nextCursor Cursor für den nächsten Stapel oder {@code null}, wenn alle Seiten der Generation gelesen sind
 */
public record ExportPageBatch(List<ExportPage> pages, List<PageFailure> failures, String nextCursor) {

    public ExportPageBatch {
        pages = List.copyOf(pages);
        failures = List.copyOf(failures);
        nextCursor = nextCursor == null || nextCursor.isEmpty() ? null : nextCursor;
    }
}
