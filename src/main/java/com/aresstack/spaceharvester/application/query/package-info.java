/**
 * Abfragen auf den Seitencache (#27): flache paginierte Seitenliste, ID-Baum und native Einzelseite.
 *
 * <p>Gelesen wird ausschließlich über den Port {@link com.aresstack.spaceharvester.application.query.PageCacheReader};
 * die Lucene-Implementierung (#26) liefert ihn später, bis dahin antwortet die API mit {@code cache-unavailable}.
 * Jede Ausgabe läuft durch {@link com.aresstack.spaceharvester.application.query.PageVisibility}, die Einhängestelle
 * für die Zugriffskontrolle (#14). Ein Cache-Treffer gilt nie als frisch: jede Antwort nennt Generation und Zustand
 * des Caches.
 */
package com.aresstack.spaceharvester.application.query;
