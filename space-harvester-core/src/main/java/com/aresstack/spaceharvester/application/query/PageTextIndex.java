package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.SearchSpaces;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Port des Volltextindex über den Cache (#180, wie {@code RawKnowledgeIndexPort} im Enterprise AI Client): je Seite der
 * Lesetext ihrer letzten abgelegten Payload, BM25 über Titel und Text, Auszüge als Textfenster um die Fundstelle. Der
 * Index ist abgeleitet und jederzeit neu aufbaubar; er ist nie Voraussetzung einer Suche und entscheidet nie über
 * Rechte: jeder Treffer wird vor der Ausgabe live und gegen die Freigabe geprüft ({@link KeywordSearch}).
 */
public interface PageTextIndex {

    /** Längster Suchtext. */
    int MAX_QUERY_LENGTH = KeywordSearch.MAX_TEXT_LENGTH;

    /** Legt den Text einer Seite ab oder ersetzt ihn. */
    void put(Entry entry);

    /** Der abgelegte Stand ({@link Entry#inputSha256()}) der Seite, wenn sie im Index liegt. */
    Optional<String> inputSha256(PageKey key);

    /** Entfernt eine Seite; unbekannte Seiten sind kein Fehler. */
    void remove(PageKey key);

    /** Entfernt alle Seiten einer Quelle (#188). */
    void removeSource(String sourceId);

    /**
     * Treffer in Rangfolge (BM25), nur aus diesen Quellen und Spaces.
     *
     * @param text  Suchtext; Wörter werden wie im Index zerlegt, eine Seite braucht mindestens eines
     * @param limit höchstens so viele
     */
    List<Match> search(String text, Set<String> sourceIds, SearchSpaces spaces, int limit);

    /** Macht Änderungen dauerhaft. */
    void flush();

    /**
     * Eine Seite im Index.
     *
     * @param spaceKey    Space bzw. Partition der Seite
     * @param inputSha256 Stand des Eintrags: Eingabe-Hash der Projektion samt Titel und Space
     */
    record Entry(PageKey key, String spaceKey, String title, String text, String inputSha256) {

        public Entry {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(spaceKey, "spaceKey");
            title = title == null ? "" : title;
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(inputSha256, "inputSha256");
        }
    }

    /**
     * Ein Treffer.
     *
     * @param snippet Textfenster um die erste Fundstelle als Klartext, leer ohne Fundstelle im Text
     */
    record Match(String sourceId, String pageId, String spaceKey, String title, String snippet) {
    }
}
