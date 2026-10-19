package com.aresstack.spaceharvester.application.terms;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
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
     * Auswahl im Index.
     *
     * @param sourceIds        nur diese Quellen; leer ergibt nichts
     * @param kind             nur diese Art oder {@code null} für alle
     * @param normalized       genau dieser Suchwert oder {@code null}
     * @param prefix           Suchwert oder eines seiner Wörter ({@link TermText#words}) beginnt so, oder {@code null}
     * @param processorVersion nur Ergebnisse dieser Version des Prozessors {@code terms}
     */
    record Filter(Set<String> sourceIds, TermKind kind, String normalized, String prefix, String processorVersion) {

        public Filter {
            sourceIds = Set.copyOf(sourceIds);
            Objects.requireNonNull(processorVersion, "processorVersion");
        }

        /** Ob ein Eintrag die Auswahl erfüllt; für Ablagen ohne eigenen Index. */
        public boolean matches(TermPosting posting) {
            if (!sourceIds.contains(posting.page().sourceId()) || kind != null && posting.kind() != kind
                    || normalized != null && !posting.normalized().equals(normalized)
                    || !posting.processorVersion().equals(processorVersion)) {
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

    /** Art und Suchwert; alle Schlüssel einer Gruppe beginnen mit ihm und {@code U+0000}. */
    static String groupKey(String normalized, TermKind kind) {
        return normalized + '\u0000' + kind.id();
    }

    /** Untere Grenze (ausschließlich) hinter allen Schlüsseln einer Gruppe. */
    static String afterGroup(String groupKey) {
        return groupKey + '\u0001';
    }
}
