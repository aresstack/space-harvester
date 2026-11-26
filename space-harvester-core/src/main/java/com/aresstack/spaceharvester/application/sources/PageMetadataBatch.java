package com.aresstack.spaceharvester.application.sources;

import java.util.List;

/**
 * Ein Stapel von Seitenmetadaten einer Partition.
 *
 * @param pages      die Seiten dieses Stapels in der Reihenfolge der Quelle
 * @param nextCursor Cursor für den nächsten Stapel oder {@code null}, wenn die Partition vollständig gelistet ist
 * @param checkpoint nur auf dem letzten Stapel einer Änderungssuche: exakte Position der Quelle danach (ADR 0042,
 *                   Punkt 5), sonst {@code null}
 */
public record PageMetadataBatch(List<PageMetadata> pages, String nextCursor, String checkpoint) {

    public PageMetadataBatch {
        pages = List.copyOf(pages);
        nextCursor = nextCursor == null || nextCursor.isEmpty() ? null : nextCursor;
        checkpoint = nextCursor != null || checkpoint == null || checkpoint.isEmpty() ? null : checkpoint;
    }

    public PageMetadataBatch(List<PageMetadata> pages, String nextCursor) {
        this(pages, nextCursor, null);
    }

    public boolean complete() {
        return nextCursor == null;
    }
}
