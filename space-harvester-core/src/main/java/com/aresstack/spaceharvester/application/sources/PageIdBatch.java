package com.aresstack.spaceharvester.application.sources;

import java.util.List;

/**
 * Ein Stapel reiner Seiten-IDs einer Partition ({@link HarvestSource#pageIds}), ohne Metadaten.
 *
 * @param pageIds    die IDs dieses Stapels in der Reihenfolge der Quelle
 * @param nextCursor Cursor für den nächsten Stapel oder {@code null}, wenn die Partition vollständig gelistet ist
 */
public record PageIdBatch(List<String> pageIds, String nextCursor) {

    public PageIdBatch {
        pageIds = List.copyOf(pageIds);
        nextCursor = nextCursor == null || nextCursor.isEmpty() ? null : nextCursor;
    }
}
