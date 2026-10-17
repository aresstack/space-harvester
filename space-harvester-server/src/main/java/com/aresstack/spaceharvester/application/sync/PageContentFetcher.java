package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;

/**
 * Lädt den nativen Snapshot einer Seite aus der Quelle (Payload und Kopfdaten aus derselben Antwort). Umgesetzt über
 * {@code StoredContentSource} (#8, Strang B); hier nur, was die Synchronisierung davon braucht.
 */
public interface PageContentFetcher {

    /**
     * @throws SourceAccessException wenn die Quelle die Seite nicht liefern kann; die Seite gilt dann als fehlgeschlagen
     *                               und wird im nächsten Lauf erneut abgeglichen
     */
    PageSnapshot fetch(String sourceId, String pageId);
}
