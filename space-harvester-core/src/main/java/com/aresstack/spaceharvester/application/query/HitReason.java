package com.aresstack.spaceharvester.application.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Warum ein Treffer gefunden wurde (R1): eine tatsächliche Ursache aus dem Lauf der Suche, nie eine nachträgliche
 * Erklärung. Jede Angabe stammt aus dem Schritt, der den Treffer lieferte: der Wortlaut, der an den Volltextindex
 * oder die Suche der Quelle ging, die bestätigte Begriffsbeziehung, die diesen Wortlaut erzeugte, die Variante der
 * progressiven Suche und die Wörter, die im Index in Titel und Text dieses Dokuments trafen. Ein Treffer kann mehrere
 * Ursachen haben; sie werden beim Zusammenführen vereinigt ({@link #union}). Die Bewertung eines Rerankers ist keine
 * Ursache, sie ordnet nur um.
 *
 * @param kind     welche Suche den Treffer lieferte
 * @param query    der Wortlaut, der tatsächlich gesucht wurde
 * @param relation die bestätigte Begriffsbeziehung, aus der {@code query} entstand, oder {@code null}, wenn
 *                 {@code query} nicht aus einer Ausweitung stammt
 * @param variant  die Variante der progressiven Suche, die {@code query} war, oder {@code null} für die ursprüngliche
 *                 Anfrage
 * @param inTitle  Wörter der Anfrage (wie der Index sie zerlegt), die im Titel des gefundenen Dokuments stehen: Titel
 *                 der Seite, bei einem Anhang sein Dateiname oder der Name der enthaltenen Datei; nur bei
 *                 {@link Kind#TEXT}
 * @param inText   Wörter der Anfrage, die im Text des gefundenen Dokuments stehen; nur bei {@link Kind#TEXT}
 */
public record HitReason(Kind kind, String query, Relation relation, Variant variant, List<String> inTitle,
                        List<String> inText) {

    public HitReason {
        Objects.requireNonNull(kind, "kind");
        query = query == null ? "" : query;
        inTitle = inTitle == null ? List.of() : List.copyOf(inTitle);
        inText = inText == null ? List.of() : List.copyOf(inText);
    }

    /** Ein Treffer des Volltextindex im Cache. */
    public static HitReason text(String query, Relation relation, List<String> inTitle, List<String> inText) {
        return new HitReason(Kind.TEXT, query, relation, null, inTitle, inText);
    }

    /** Ein Fund der Suche einer Quelle, noch nicht im Cache bestätigt. */
    public static HitReason source(String query, Relation relation) {
        return new HitReason(Kind.SOURCE, query, relation, null, List.of(), List.of());
    }

    /** Ein Treffer der semantischen Suche über die Vektoren des Caches. */
    public static HitReason semantic(String query) {
        return new HitReason(Kind.SEMANTIC, query, null, null, List.of(), List.of());
    }

    /** Dieselbe Ursache als Ergebnis einer Variante der progressiven Suche; {@code null} für die ursprüngliche. */
    public HitReason fromVariant(Variant variant) {
        return new HitReason(kind, query, relation, variant, inTitle, inText);
    }

    /** Ob der Treffer allein über den Wortlaut der eingegebenen Anfrage kam, ohne Ausweitung und ohne Variante. */
    public boolean original() {
        return relation == null && variant == null;
    }

    /**
     * Die Ursachen beider Listen ohne Dopplungen, in der Reihenfolge ihres ersten Auftretens. Gleich sind Ursachen mit
     * gleicher Art, gleichem Wortlaut (ohne Groß- und Kleinschreibung), gleicher Beziehung und gleicher Variante; ihre
     * Wörter werden vereinigt.
     */
    public static List<HitReason> union(List<HitReason> first, List<HitReason> second) {
        if (second == null || second.isEmpty()) {
            return first == null ? List.of() : List.copyOf(first);
        }
        if (first == null || first.isEmpty()) {
            return List.copyOf(second);
        }
        Map<String, HitReason> merged = new LinkedHashMap<>();
        for (List<HitReason> reasons : List.of(first, second)) {
            for (HitReason reason : reasons) {
                merged.merge(reason.identity(), reason, HitReason::withWordsOf);
            }
        }
        return List.copyOf(merged.values());
    }

    private String identity() {
        return kind + "\u0000" + query.toLowerCase(Locale.ROOT) + "\u0000" + relation + "\u0000" + variant;
    }

    private HitReason withWordsOf(HitReason other) {
        return new HitReason(kind, query, relation, variant, words(inTitle, other.inTitle),
                words(inText, other.inText));
    }

    private static List<String> words(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        second.stream().filter(word -> !all.contains(word)).forEach(all::add);
        return all;
    }

    /** Welche Suche den Treffer lieferte. */
    public enum Kind {
        /** Der Volltextindex über den Cache (BM25); der Treffer ist im Inhalt bestätigt. */
        TEXT,
        /** Die Suche der Quelle; ein vorläufiger Treffer, dessen Inhalt noch nicht im Cache liegt. */
        SOURCE,
        /** Die semantische Suche: Ähnlichkeit der Vektoren im Cache zur Anfrage (ADR 0013, nur mit Haken). */
        SEMANTIC
    }

    /**
     * Die bestätigte Begriffsbeziehung einer Ausweitung (#336, Teil 4).
     *
     * @param term        der Begriff der Anfrage, zu dem die Beziehung gehört
     * @param counterpart das Gegenstück, mit dem gesucht wurde
     * @param type        Art der Beziehung ({@code abbreviation}, {@code synonym}, {@code variant}) oder {@code null},
     *                    wenn die Quelle der Beziehungen sie nicht nennt
     */
    public record Relation(String term, String counterpart, String type) {

        public Relation {
            Objects.requireNonNull(term, "term");
            Objects.requireNonNull(counterpart, "counterpart");
        }
    }

    /**
     * Eine Variante der progressiven Suche (ADR 0017).
     *
     * @param priority 1 = genaueste Variante
     * @param origin   woher die Variante stammt, oder {@code null}, wenn die Strategie es nicht meldet
     */
    public record Variant(int priority, Origin origin) {

        public Variant {
            if (priority < 1) {
                throw new IllegalArgumentException("Variante hat Priorität ab 1");
            }
        }

        /** Woher eine Variante stammt. */
        public enum Origin {
            /** Aus Regeln ({@code RuleBasedExpansion}), auch als Rückfall ohne Modell. */
            RULES,
            /** Vom Chat-Modell des KI-Dienstes ({@code AiQueryExpansion}). */
            MODEL
        }
    }
}
