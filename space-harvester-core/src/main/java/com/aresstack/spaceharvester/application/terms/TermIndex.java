package com.aresstack.spaceharvester.application.terms;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Port des Begriffsindex (#119, ADR 0019): abgeleitete, indexierte Begriffsdokumente in der Anreicherungsablage, eines
 * je Seite, Art und Suchwert. Sie entstehen und vergehen mit dem Ergebnis des Prozessors {@code terms} derselben
 * Seite; ein Neuaufbau verliert nichts, was sich nicht aus den gecachten Seiten neu berechnen ließe.
 *
 * <p>Die Reihenfolge ist {@link #sortKey}: Suchwert, Art, Quelle, Seite, je durch {@code U+0000} getrennt, verglichen
 * als UTF-8-Bytes ({@link #ORDER}). So liegen alle Seiten eines Begriffs beieinander, und Weiterlesen nach einem
 * Schlüssel ist eindeutig.
 */
public interface TermIndex {

    /** Höchstens so viele Einträge je Aufruf. */
    int MAX_BATCH = 2_000;

    /** Reihenfolge der Schlüssel wie im Lucene-Index: UTF-8-Bytes ohne Vorzeichen. */
    Comparator<String> ORDER = (a, b) -> Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8),
            b.getBytes(StandardCharsets.UTF_8));

    /**
     * Einträge in {@link #sortKey}-Reihenfolge.
     *
     * @param filter    Auswahl
     * @param from      nur Schlüssel ab hier oder {@code null} für den Anfang
     * @param inclusive ob {@code from} selbst dazugehört
     * @param max       1 bis {@link #MAX_BATCH}
     */
    List<TermPosting> postings(Filter filter, String from, boolean inclusive, int max);

    /**
     * Meldet, wenn sich die Einträge einer Seite oder Quelle geändert haben können (Observer, kein Polling). Die
     * Meldung kommt im schreibenden Thread. Ablagen ohne Meldungen liefern {@code null}: Wer mitzählt, darf sich
     * dann auf nichts Gemerktes verlassen.
     *
     * @return Abmelden oder {@code null}, wenn die Ablage nichts meldet
     */
    default Runnable subscribe(java.util.function.Consumer<Change> listener) {
        return null;
    }

    /**
     * Was sich geändert haben kann.
     *
     * @param pageId die Seite samt ihren Dokumenten oder {@code null} für die ganze Quelle
     */
    record Change(String sourceId, String pageId) {
    }

    /** Höchstens so viele Schlüssel je Aufruf von {@link #keys}; sie sind klein, der Stapel darf groß sein. */
    int MAX_KEY_BATCH = 50_000;

    /**
     * Wie {@link #postings}, aber nur das, was Zählen braucht: keine Fundstellen, kein Wortlaut. Eine Ablage mit
     * eigenem Index liest dafür keine ganzen Einträge.
     *
     * @param from nur Schlüssel hinter diesem oder {@code null} für den Anfang
     * @param max  1 bis {@link #MAX_KEY_BATCH}
     */
    default List<TermKey> keys(Filter filter, String from, int max) {
        if (max < 1 || max > MAX_KEY_BATCH) {
            throw new IllegalArgumentException("max muss zwischen 1 und " + MAX_KEY_BATCH + " liegen");
        }
        List<TermKey> keys = new java.util.ArrayList<>();
        String after = from;
        while (keys.size() < max) {
            int want = Math.min(MAX_BATCH, max - keys.size());
            List<TermPosting> batch = postings(filter, after, false, want);
            batch.forEach(posting -> keys.add(TermKey.of(posting)));
            if (batch.size() < want) {
                break;
            }
            after = batch.get(batch.size() - 1).sortKey();
        }
        return keys;
    }

    /**
     * Ein Eintrag ohne Inhalt: wozu er gehört und auf welchem Stand er ist.
     *
     * @param revision    wie {@link TermPosting#revision()}
     * @param inputSha256 wie {@link TermPosting#inputSha256()}
     * @param ofDocument  ob er aus einem Anhang oder einer enthaltenen Datei stammt
     * @param extraction  wie {@link TermPosting#extraction()}
     */
    record TermKey(PageKey page, TermKind kind, String normalized, String revision, String inputSha256,
                   boolean ofDocument, String extraction, String sortKey, boolean localOriginal) {

        public TermKey(PageKey page, TermKind kind, String normalized, String revision, String inputSha256,
                       boolean ofDocument, String extraction, String sortKey) {
            this(page, kind, normalized, revision, inputSha256, ofDocument, extraction, sortKey, false);
        }

        public static TermKey of(TermPosting posting) {
            return new TermKey(posting.page(), posting.kind(), posting.normalized(), posting.revision(),
                    posting.inputSha256(), posting.ofDocument(), posting.extraction(), posting.sortKey(),
                    posting.document().localOriginal());
        }

        public String groupKey() {
            return TermIndex.groupKey(normalized, kind);
        }
    }

    /**
     * Auswahl im Index.
     *
     * @param sourceIds        nur diese Quellen; leer ergibt nichts
     * @param kind             nur diese Art oder {@code null} für alle
     * @param normalized       genau dieser Suchwert oder {@code null}
     * @param prefix           Suchwert oder eines seiner Wörter ({@link TermText#words}) beginnt so, oder {@code null}
     * @param processorVersion nur Ergebnisse dieser Version des Prozessors {@code terms}
     * @param startsWith       nur der ganze Suchwert beginnt so (etwa ein Buchstabe der A–Z-Leiste), oder {@code null}
     * @param origin           Seiten, Dokumente oder beides (ADR 0026, AP9b)
     * @param pages            nur diese Seiten samt ihren Dokumenten (ADR 0029) oder {@code null} ohne diese Grenze
     * @param anyOf            nur diese Suchwerte oder {@code null} ohne diese Grenze; leer ergibt nichts
     */
    record Filter(Set<String> sourceIds, TermKind kind, String normalized, String prefix, String processorVersion,
                  String startsWith, TermOrigin origin, PageSet pages, Set<String> anyOf) {

        public Filter {
            sourceIds = Set.copyOf(sourceIds);
            Objects.requireNonNull(processorVersion, "processorVersion");
            Objects.requireNonNull(origin, "origin");
            anyOf = anyOf == null ? null : Set.copyOf(anyOf);
        }

        public Filter(Set<String> sourceIds, TermKind kind, String normalized, String prefix,
                      String processorVersion, String startsWith, TermOrigin origin, PageSet pages) {
            this(sourceIds, kind, normalized, prefix, processorVersion, startsWith, origin, pages, null);
        }

        /** Dieselbe Auswahl, begrenzt auf diese Suchwerte: eine Abfrage für viele Begriffe statt einer je Begriff. */
        public Filter anyOf(Set<String> normalized) {
            return new Filter(sourceIds, kind, this.normalized, prefix, processorVersion, startsWith, origin, pages,
                    normalized);
        }

        public Filter(Set<String> sourceIds, TermKind kind, String normalized, String prefix,
                      String processorVersion, String startsWith, TermOrigin origin) {
            this(sourceIds, kind, normalized, prefix, processorVersion, startsWith, origin, null);
        }

        /** Nur Seiten, wie vor AP9b. */
        public Filter(Set<String> sourceIds, TermKind kind, String normalized, String prefix,
                      String processorVersion, String startsWith) {
            this(sourceIds, kind, normalized, prefix, processorVersion, startsWith, TermOrigin.PAGES);
        }

        /** Nur Seiten, wie vor AP9b. */
        public Filter(Set<String> sourceIds, TermKind kind, String normalized, String prefix,
                      String processorVersion) {
            this(sourceIds, kind, normalized, prefix, processorVersion, null, TermOrigin.PAGES);
        }

        /** Ob ein Eintrag die Auswahl erfüllt; für Ablagen ohne eigenen Index. */
        public boolean matches(TermPosting posting) {
            if (!sourceIds.contains(posting.page().sourceId()) || !origin.includes(posting.ofDocument())
                    || pages != null && !pages.contains(posting.page())
                    || kind != null && posting.kind() != kind
                    || normalized != null && !posting.normalized().equals(normalized)
                    || anyOf != null && !anyOf.contains(posting.normalized())
                    || !posting.processorVersion().equals(processorVersion)
                    || startsWith != null && !posting.normalized().startsWith(startsWith)) {
                return false;
            }
            return prefix == null || posting.normalized().startsWith(prefix)
                    || TermText.words(posting.normalized()).stream().anyMatch(word -> word.startsWith(prefix));
        }
    }

    /** Eindeutiger Schlüssel eines Eintrags in Index-Reihenfolge. */
    static String sortKey(String normalized, TermKind kind, PageKey page) {
        return groupKey(normalized, kind) + '\u0000' + page.sourceId() + '\u0000' + page.pageId();
    }

    /**
     * Schlüssel eines Eintrags aus einem Abschnitt eines Anhangs oder einer enthaltenen Datei: der Schlüssel der Seite,
     * dahinter {@link DocumentKey#value()} und der Abschnitt. Für die Seite selbst {@link #sortKey(String, TermKind,
     * PageKey)}; so liegen die Dokumente einer Seite direkt hinter ihr, und Schlüssel von Seiten ändern sich nicht.
     */
    static String sortKey(String normalized, TermKind kind, DocumentKey document, int section) {
        String page = sortKey(normalized, kind, document.page());
        if (document.isPage()) {
            return page;
        }
        return page + '\u0000' + document.value() + '\u0000' + String.format("%06d", section);
    }

    /** Art und Suchwert; alle Schlüssel einer Gruppe beginnen mit ihm und {@code U+0000}. */
    static String groupKey(String normalized, TermKind kind) {
        return normalized + '\u0000' + kind.id();
    }

    /** Untere Grenze (ausschließlich) hinter allen Schlüsseln einer Gruppe. */
    static String afterGroup(String groupKey) {
        return groupKey + '\u0001';
    }
}
