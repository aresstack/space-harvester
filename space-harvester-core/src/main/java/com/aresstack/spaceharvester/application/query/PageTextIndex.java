package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.SearchSpaces;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Port des Volltextindex über den Cache (#180, wie {@code RawKnowledgeIndexPort} im Enterprise AI Client): je Seite der
 * Lesetext ihrer letzten abgelegten Payload, BM25 über Titel und Text, Auszüge als Textfenster um die Fundstelle. Dazu
 * je Anhang einer Seite dessen Text (#183, wie {@code SourceAttachmentReader} im Enterprise AI Client: ein eigenes
 * Dokument mit der Seite als Eltern); ein Treffer darin ist ein Treffer der Seite ({@link Match#attachmentId()}) und
 * gilt mit deren Rechten. Der
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

    /**
     * Der abgelegte Lesetext der Seite (#181, für den Rerank-Dienst), wenn sie im Index liegt. Entscheidet nicht über
     * Rechte; der Aufrufer prüft {@code content} vorher.
     */
    default Optional<String> text(PageKey key) {
        return Optional.empty();
    }

    /** Entfernt eine Seite samt den Texten ihrer Anhänge; unbekannte Seiten sind kein Fehler. */
    void remove(PageKey key);

    /**
     * Legt den Text eines Anhangs der Seite ab oder ersetzt ihn (#183). Ein Index ohne Anhänge ignoriert ihn.
     */
    default void putAttachment(AttachmentEntry entry) {
    }

    /** Der abgelegte Stand ({@link AttachmentEntry#inputSha256()}) des Anhangs, wenn er im Index liegt. */
    default Optional<String> attachmentInputSha256(PageKey page, String attachmentId) {
        return Optional.empty();
    }

    /** Entfernt den Text eines Anhangs der Seite; ein unbekannter Anhang ist kein Fehler. */
    default void removeAttachment(PageKey page, String attachmentId) {
    }

    /**
     * Ersetzt alle Einträge eines Anhangs der Seite (Dokument, enthaltene Dateien, alle Abschnitte) durch diese
     * (ADR 0026, AP4b); für Leser in einem Schritt: Sie sehen den alten oder den neuen Stand, nie einen halben. Die
     * übrigen Einträge der Seite bleiben. Die Einträge werden einzeln gelesen, solange sie gebraucht werden.
     */
    default void replaceAttachment(PageKey page, String attachmentId, Iterable<AttachmentEntry> entries) {
        removeAttachment(page, attachmentId);
        entries.forEach(this::putAttachment);
    }

    /**
     * Ersetzt die Einträge eines einzelnen Dokuments eines Anhangs (das Dokument selbst mit leerer {@code partId}
     * oder eine enthaltene Datei) über alle Abschnitte, wie {@link #replaceAttachment} in einem Schritt; die übrigen
     * Dokumente des Anhangs und der Seite bleiben (Neu lesen, ADR 0026 Punkt 11).
     */
    default void replaceDocument(PageKey page, String attachmentId, String partId, Iterable<AttachmentEntry> entries) {
        throw new UnsupportedOperationException("Index ohne Dokumente je Anhang");
    }

    /** Entfernt die Anhänge der Seite, die nicht mehr zu ihr gehören; die Seite selbst bleibt. */
    default void retainAttachments(PageKey page, Set<String> attachmentIds) {
    }

    /** Entfernt alle Seiten einer Quelle (#188). */
    void removeSource(String sourceId);

    /**
     * Treffer in Rangfolge (BM25), nur aus diesen Quellen und Spaces; jede Seite höchstens einmal, mit ihrem besten
     * Dokument (Seite oder einer ihrer Anhänge).
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
     * @param spaceKey    Space bzw. Partition der Seite; ein anderer als bisher gilt auch für die Anhänge der Seite
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
     * Der Text eines Anhangs einer Seite (#183), seit ADR 0026 (AP4b) je Dokument und Abschnitt: das Dokument selbst
     * oder eine enthaltene Datei ({@code partId}), je abgelegtem Abschnitt ein Eintrag.
     *
     * @param page        die Seite, zu der der Anhang gehört; ihre Rechte gelten für ihn
     * @param spaceKey    Space der Seite
     * @param title       Dateiname des Anhangs
     * @param inputSha256 Stand des Eintrags: Inhalt, Extraktion, Space und Dateiname; gleich für alle Einträge des
     *                    Anhangs
     * @param partId      Teilkennung ({@code PartId#value()}) der enthaltenen Datei, leer für das Dokument selbst
     * @param partTitle   Anzeigename der enthaltenen Datei, leer für das Dokument selbst oder ohne Namen
     * @param section     Nummer des Abschnitts im Dokument ab 0
     */
    record AttachmentEntry(PageKey page, String spaceKey, String attachmentId, String title, String text,
                           String inputSha256, String partId, String partTitle, int section) {

        public AttachmentEntry {
            Objects.requireNonNull(page, "page");
            Objects.requireNonNull(spaceKey, "spaceKey");
            Objects.requireNonNull(attachmentId, "attachmentId");
            title = title == null ? "" : title;
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(inputSha256, "inputSha256");
            partId = partId == null ? "" : partId;
            partTitle = partTitle == null ? "" : partTitle;
            if (section < 0) {
                throw new IllegalArgumentException("Abschnitt ab 0");
            }
        }

        /** Das Dokument selbst in einem Eintrag. */
        public AttachmentEntry(PageKey page, String spaceKey, String attachmentId, String title, String text,
                               String inputSha256) {
            this(page, spaceKey, attachmentId, title, text, inputSha256, "", "", 0);
        }

        @Override
        public String toString() {
            return "AttachmentEntry[source=" + page.sourceId() + ", part=" + !partId.isEmpty() + ", section="
                    + section + ", chars=" + text.length() + "]";
        }
    }

    /**
     * Ein Treffer, immer eine Seite.
     *
     * @param title           Titel der Seite; leer, wenn der Treffer aus einem Anhang stammt und der Index ihn nicht
     *                        kennt
     * @param snippet         Textfenster um die erste Fundstelle als Klartext, leer ohne Fundstelle im Text
     * @param attachmentId    der Anhang der Seite, in dessen Text der Treffer liegt, {@code null} für den Text der Seite
     * @param attachmentTitle Dateiname dieses Anhangs oder {@code null}
     * @param passageId       die Passage-ID des Absatzes, aus dem der Auszug stammt (EAC-Lücke 5), oder {@code null}
     * @param partId          Teilkennung der enthaltenen Datei des Anhangs, in der der Treffer liegt (ADR 0026),
     *                        oder {@code null}
     * @param partTitle       Anzeigename dieser enthaltenen Datei oder {@code null}
     */
    record Match(String sourceId, String pageId, String spaceKey, String title, String snippet, String attachmentId,
                 String attachmentTitle, String passageId, String partId, String partTitle) {

        public Match(String sourceId, String pageId, String spaceKey, String title, String snippet, String attachmentId,
                     String attachmentTitle, String passageId) {
            this(sourceId, pageId, spaceKey, title, snippet, attachmentId, attachmentTitle, passageId, null, null);
        }

        public Match(String sourceId, String pageId, String spaceKey, String title, String snippet) {
            this(sourceId, pageId, spaceKey, title, snippet, null, null);
        }

        public Match(String sourceId, String pageId, String spaceKey, String title, String snippet, String attachmentId,
                     String attachmentTitle) {
            this(sourceId, pageId, spaceKey, title, snippet, attachmentId, attachmentTitle, null);
        }

        /**
         * Derselbe Treffer mit anderem Auszug; Seite, Rang und Anhang bleiben. Hüllen um den Index (etwa Auszüge aus
         * Absätzen) ersetzen den Auszug nur hierüber, damit ein Anhangstreffer nicht zum Seitentreffer wird.
         */
        public Match withSnippet(String snippet) {
            return withSnippet(snippet, null);
        }

        /** Wie {@link #withSnippet(String)}, mit der Passage-ID des Absatzes, aus dem der neue Auszug stammt. */
        public Match withSnippet(String snippet, String passageId) {
            return new Match(sourceId, pageId, spaceKey, title, snippet, attachmentId, attachmentTitle, passageId,
                    partId, partTitle);
        }

        /** Ob der Treffer im Text eines Anhangs liegt. */
        public boolean inAttachment() {
            return attachmentId != null;
        }
    }
}
