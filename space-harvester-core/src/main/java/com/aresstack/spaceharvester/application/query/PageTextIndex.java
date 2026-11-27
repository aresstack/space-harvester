package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
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

    /** Atomically replace the text sections of the page itself, retaining its real attachments. */
    default void replacePageSections(PageKey page, Iterable<Entry> sections) {
        throw new UnsupportedOperationException("Index does not support page sections");
    }

    /** Der abgelegte Stand ({@link Entry#inputSha256()}) der Seite, wenn sie im Index liegt. */
    Optional<String> inputSha256(PageKey key);

    /** Read all primary page index states for a space in one index snapshot. */
    default java.util.Map<String, ContentState> contentStates(String sourceId, String spaceKey) {
        return java.util.Map.of();
    }

    record ContentState(String inputSha256, boolean hasText, boolean verified) {
        public ContentState(String inputSha256, boolean hasText) {
            this(inputSha256, hasText, true);
        }
    }

    /** Existing indexes without the marker are repaired by the normal indexing path. */
    default boolean contentStateKnown(PageKey key) {
        return true;
    }

    /**
     * Der abgelegte Medientyp ({@link Entry#mediaType()}) der Seite; leer, wenn sie im Index fehlt oder der Eintrag
     * vor dem Medientyp entstand (dann schreibt {@link PageTextIndexing} ihn beim nächsten Indizieren nach).
     */
    default Optional<String> mediaType(PageKey key) {
        return Optional.empty();
    }

    /**
     * Ob der Eintrag der Seite ihre Überschriften führt ({@link Entry#headings()}); Einträge von vorher schreibt
     * {@link PageTextIndexing} beim nächsten Indizieren der Seite einmal nach, ohne den Index neu aufzubauen.
     */
    default boolean headingsKnown(PageKey key) {
        return true;
    }

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

    /**
     * Dateiname des Anhangs und Anzeigename der enthaltenen Datei, wie sie Treffer des Volltexts tragen (#723), für
     * Treffer der semantischen Suche in einem Dokument (ADR 0026, AP9a). Leer, wenn der Index das Dokument nicht kennt.
     *
     * @param partId Teilkennung ({@code PartId#value()}) oder leer für den Anhang selbst
     */
    default Optional<DocumentTitles> documentTitles(PageKey page, String attachmentId, String partId) {
        return Optional.empty();
    }

    /**
     * Namen eines Dokuments.
     *
     * @param title     Dateiname des Anhangs, leer ohne
     * @param partTitle Anzeigename der enthaltenen Datei, leer für den Anhang selbst oder ohne Namen
     */
    record DocumentTitles(String title, String partTitle) {

        public DocumentTitles {
            title = title == null ? "" : title;
            partTitle = partTitle == null ? "" : partTitle;
        }
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

    /**
     * Wie {@link #search(String, Set, SearchSpaces, int)}, aber nur Seiten dieser Menge samt ihren Anhängen (ADR 0029);
     * mit {@code null} ohne diese Grenze. Ein Index filtert vor der Rangfolge, damit seine Grenze gelesener Dokumente
     * nicht vor dem Filter greift; diese Vorgabe für Indizes ohne Seitenfeld filtert nur nach.
     *
     * @param pages nur diese Seiten oder {@code null}
     */
    default List<Match> search(String text, Set<String> sourceIds, SearchSpaces spaces, PageSet pages, int limit) {
        if (pages == null) {
            return search(text, sourceIds, spaces, limit);
        }
        Set<String> within = new java.util.TreeSet<>(sourceIds);
        within.retainAll(pages.sourceIds());
        if (within.isEmpty()) {
            return List.of();
        }
        return search(text, within, spaces, limit).stream()
                .filter(match -> pages.contains(match.sourceId(), match.pageId())).toList();
    }

    /**
     * Wie {@link #search(String, Set, SearchSpaces, PageSet, int)}, nur Dokumente dieser Arten ({@link DocumentKind});
     * leer für alle. Ein Index entscheidet je Dokument vor der Rangfolge: Seite oder Anhang nach Dateiname und
     * Medientyp, enthaltene Datei nach ihrem Anzeigenamen. Diese Vorgabe für Indizes ohne Medientyp filtert nach, nur
     * nach den Namen der Treffer.
     *
     * @param kinds nur diese Arten oder leer für alle
     */
    default List<Match> search(String text, Set<String> sourceIds, SearchSpaces spaces, PageSet pages,
                               Set<DocumentKind> kinds, int limit) {
        List<Match> matches = search(text, sourceIds, spaces, pages, limit);
        if (kinds.isEmpty()) {
            return matches;
        }
        return matches.stream().filter(match -> kinds.contains(match.kind())).toList();
    }

    /** Macht Änderungen dauerhaft. */
    void flush();

    /**
     * Eine Seite im Index.
     *
     * @param spaceKey    Space bzw. Partition der Seite; ein anderer als bisher gilt auch für die Anhänge der Seite
     * @param inputSha256 Stand des Eintrags: Eingabe-Hash der Projektion samt Titel und Space
     * @param headings    Überschriften der Seite in Textreihenfolge ({@code PageText.Section}); sie stehen
     *                    auch im Text, zählen in der Rangfolge aber wie der Titel, und eine Überschrift, die genau
     *                    der Anfrage entspricht, stellt die Seite nach oben
     */
    record Entry(PageKey key, String spaceKey, String title, String text, String inputSha256, String mediaType,
                 int metadataPrefixChars, List<Heading> headings) {

        public Entry(PageKey key, String spaceKey, String title, String text, String inputSha256, String mediaType) {
            this(key, spaceKey, title, text, inputSha256, mediaType, 0);
        }

        public Entry(PageKey key, String spaceKey, String title, String text, String inputSha256, String mediaType,
                     int metadataPrefixChars) {
            this(key, spaceKey, title, text, inputSha256, mediaType, metadataPrefixChars, List.of());
        }

        public Entry {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(spaceKey, "spaceKey");
            title = title == null ? "" : title;
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(inputSha256, "inputSha256");
            mediaType = mediaType == null ? "" : mediaType;
            headings = headings == null ? List.of() : List.copyOf(headings);
            if (metadataPrefixChars < 0 || metadataPrefixChars > text.length()) {
                throw new IllegalArgumentException("Invalid metadata prefix length");
            }
        }

        /** Ohne Medientyp: die Art des Dokuments ({@link DocumentKind}) ergibt sich aus dem Titel. */
        public Entry(PageKey key, String spaceKey, String title, String text, String inputSha256) {
            this(key, spaceKey, title, text, inputSha256, "");
        }

        /** Die Art des Dokuments aus Titel und Medientyp. */
        public DocumentKind kind() {
            return DocumentKind.of(title, mediaType);
        }
    }

    /**
     * Eine Überschrift einer Seite.
     *
     * @param text  Text der Überschrift
     * @param start Zeichenposition der Überschrift im Text des Eintrags; dort beginnt der Auszug eines Treffers, dessen
     *              Überschrift genau der Anfrage entspricht
     */
    record Heading(String text, int start) {

        public Heading {
            Objects.requireNonNull(text, "text");
            if (start < 0) {
                throw new IllegalArgumentException("start ab 0");
            }
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
     * @param mediaType   Medientyp des Anhangs, wie die Quelle ihn meldet, leer ohne; für eine enthaltene Datei
     *                    entscheidet ihr Anzeigename
     */
    record AttachmentEntry(PageKey page, String spaceKey, String attachmentId, String title, String text,
                           String inputSha256, String partId, String partTitle, int section, String mediaType) {

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
            mediaType = mediaType == null ? "" : mediaType;
        }

        /** Ohne Medientyp: die Art des Dokuments ergibt sich aus dem Dateinamen. */
        public AttachmentEntry(PageKey page, String spaceKey, String attachmentId, String title, String text,
                               String inputSha256, String partId, String partTitle, int section) {
            this(page, spaceKey, attachmentId, title, text, inputSha256, partId, partTitle, section, "");
        }

        /** Das Dokument selbst in einem Eintrag. */
        public AttachmentEntry(PageKey page, String spaceKey, String attachmentId, String title, String text,
                               String inputSha256) {
            this(page, spaceKey, attachmentId, title, text, inputSha256, "", "", 0);
        }

        /** Das Dokument selbst in einem Eintrag, mit dem Medientyp der Quelle. */
        public AttachmentEntry(PageKey page, String spaceKey, String attachmentId, String title, String text,
                               String inputSha256, String mediaType) {
            this(page, spaceKey, attachmentId, title, text, inputSha256, "", "", 0, mediaType);
        }

        /**
         * Die Art des Dokuments: bei einer enthaltenen Datei aus ihrem Anzeigenamen, sonst aus Dateiname und
         * Medientyp des Anhangs.
         */
        public DocumentKind kind() {
            return partId.isEmpty() ? DocumentKind.of(title, mediaType) : DocumentKind.of(partTitle, null);
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
     * @param words           welche Wörter der Anfrage in diesem Dokument trafen (R1), wie der Index sie zerlegt;
     *                        {@link Words#NONE}, wenn der Index es nicht meldet
     */
    record Match(String sourceId, String pageId, String spaceKey, String title, String snippet, String attachmentId,
                 String attachmentTitle, String passageId, String partId, String partTitle, Words words) {

        public Match {
            words = words == null ? Words.NONE : words;
        }

        public Match(String sourceId, String pageId, String spaceKey, String title, String snippet, String attachmentId,
                     String attachmentTitle, String passageId, String partId, String partTitle) {
            this(sourceId, pageId, spaceKey, title, snippet, attachmentId, attachmentTitle, passageId, partId,
                    partTitle, Words.NONE);
        }

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
                    partId, partTitle, words);
        }

        /** Derselbe Treffer mit den Wörtern, die in ihm trafen. */
        public Match withWords(Words words) {
            return new Match(sourceId, pageId, spaceKey, title, snippet, attachmentId, attachmentTitle, passageId,
                    partId, partTitle, words);
        }

        /** Ob der Treffer im Text eines Anhangs liegt. */
        public boolean inAttachment() {
            return attachmentId != null;
        }

        /**
         * Die Art des gefundenen Dokuments nach seinem Namen: enthaltene Datei, Anhang oder Seite; ohne den Medientyp,
         * den nur der Index kennt.
         */
        public DocumentKind kind() {
            if (partId != null) {
                return DocumentKind.of(partTitle, null);
            }
            return DocumentKind.of(attachmentId != null ? attachmentTitle : title, null);
        }
    }

    /**
     * Die Wörter einer Anfrage, die in einem gefundenen Dokument wirklich trafen (R1): genau die Wortabfragen, aus denen
     * der Index die Rangfolge bildet, nicht aus dem Auszug erschlossen.
     *
     * @param inTitle im Titel des Dokuments (Seitentitel, Dateiname des Anhangs oder Name der enthaltenen Datei)
     * @param inText  im Text des Dokuments
     */
    record Words(List<String> inTitle, List<String> inText) {

        /** Nicht gemeldet. */
        public static final Words NONE = new Words(List.of(), List.of());

        public Words {
            inTitle = inTitle == null ? List.of() : List.copyOf(inTitle);
            inText = inText == null ? List.of() : List.copyOf(inText);
        }
    }
}
