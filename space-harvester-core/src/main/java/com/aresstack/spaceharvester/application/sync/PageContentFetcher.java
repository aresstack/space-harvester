package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.sources.PageMetadata;
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

    /**
     * Wie {@link #fetch(String, String)}, mit den Kopfdaten aus Inventur bzw. Versionsprüfung: Eine Umsetzung kann daran
     * entscheiden, die Datei gar nicht herunterzuladen ({@code LargeFiles}). Standard: lädt immer.
     */
    default PageSnapshot fetch(String sourceId, PageMetadata expected) {
        return fetch(sourceId, expected.pageId());
    }

    /** Whether a source may extract a local original during a first read, without cached metadata. */
    default boolean mayExtractLocalOriginal(String sourceId) {
        return false;
    }

    /** Ob {@link #fetch(String, PageMetadata)} die Datei dieser Kopfdaten ganz lädt; Standard: immer. */
    default boolean loadsWhole(PageMetadata metadata) {
        return true;
    }

    /**
     * Ob {@link #fetch(String, PageMetadata)} für eine zu große Datei dieser Kopfdaten einen Textauszug versucht
     * ({@code LargeFiles}); Standard: nie.
     */
    default boolean triesText(PageMetadata metadata) {
        return false;
    }
}
