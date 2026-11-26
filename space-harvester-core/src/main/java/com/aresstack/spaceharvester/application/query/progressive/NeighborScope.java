package com.aresstack.spaceharvester.application.query.progressive;

import com.aresstack.spaceharvester.application.query.DocumentKind;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.structure.PageRef;

/**
 * Der Umfang einer Suche für ihre Nachbarn (R2): Quelle, gewählte Seiten, Spaces (auch ausgeschlossene) und
 * Dateitypen wie in der Suche selbst. Was außerhalb liegt, wird weder Kandidat noch geladen. Ist Space oder Art eines
 * fehlenden Nachbarn unbekannt, zählt er bei einer Grenze auf Spaces als außerhalb; ohne bekannten Titel gilt er als
 * Seite (Elternseiten und Verweisziele sind Seiten).
 */
final class NeighborScope {

    private NeighborScope() {
    }

    static boolean allows(KeywordSearch.Query query, PageKey key, PageRef place) {
        if (query.sourceId() != null ? !query.sourceId().equals(key.sourceId())
                : !query.sourceIds().isEmpty() && !query.sourceIds().contains(key.sourceId())) {
            return false;
        }
        if (!query.admits(key.sourceId(), key.pageId())) {
            return false;
        }
        String spaceKey = place == null ? null : place.spaceKey();
        boolean limited = !query.spaceKeys().isEmpty() || !query.excludedSpaceKeys().isEmpty();
        if (spaceKey == null ? limited : !query.spaceKeys().isEmpty() && !query.spaceKeys().contains(spaceKey)
                || query.excludedSpaceKeys().contains(spaceKey)) {
            return false;
        }
        String title = place == null ? null : place.title();
        return query.kinds().isEmpty() || query.kinds().contains(title == null ? DocumentKind.PAGE
                : DocumentKind.of(title, null));
    }
}
