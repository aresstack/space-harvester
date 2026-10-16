package com.aresstack.spaceharvester.application.sources;

import java.util.List;

/**
 * Ein Stapel von Seitenmetadaten einer Partition.
 *
 * @param pages      die Seiten dieses Stapels in der Reihenfolge der Quelle
 * @param nextCursor Cursor für den nächsten Stapel oder {@code null}, wenn die Partition vollständig gelistet ist
 */
public record PageMetadataBatch(List<PageMetadata> pages, String nextCursor) {

    public PageMetadataBatch {
        pages = List.copyOf(pages);
        nextCursor = nextCursor == null || nextCursor.isEmpty() ? null : nextCursor;
    }

    public boolean complete() {
        return nextCursor == null;
    }
}
