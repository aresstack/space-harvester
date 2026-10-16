package com.aresstack.spaceharvester.application.query;

import java.util.List;

/**
 * Ein Stapel der flachen Seitenliste eines Space aus dem Cache.
 *
 * @param pages      sichtbare Seiten, aufsteigend nach {@code pageId}; kann kürzer als das Limit oder leer sein, auch
 *                   wenn {@code complete} noch {@code false} ist
 * @param nextCursor Cursor für den nächsten Stapel oder {@code null}, wenn {@code complete}
 * @param complete   {@code true}, wenn die Generation bis zum Ende gelesen ist
 */
public record PageListing(String sourceId, String spaceKey, CacheGeneration generation, List<PageHeader> pages,
                          String nextCursor, boolean complete) {

    public PageListing {
        pages = List.copyOf(pages);
    }
}
