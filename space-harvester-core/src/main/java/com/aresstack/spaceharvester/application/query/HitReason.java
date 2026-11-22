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
 * @param via      bei {@link Kind#NEIGHBOR} die Ausgangsseite und die Kante, über die der Treffer als Kandidat kam;
 *                 sonst {@code null}
 */
public record HitReason(Kind kind, String query, Relation relation, Variant variant, List<String> inTitle,
                        List<String> inText, Neighbor via) {

    public HitReason {
        Objects.requireNonNull(kind, "kind");
        query = query == null ? "" : query;
        inTitle = inTitle == null ? List.of() : List.copyOf(inTitle);
        inText = inText == null ? List.of() : List.copyOf(inText);
        if ((kind == Kind.NEIGHBOR) != (via != null)) {
            throw new IllegalArgumentException("via gehört genau zur Art NEIGHBOR");
        }
    }

    /** Ohne {@code via}: jede Art außer {@link Kind#NEIGHBOR}. */
    public HitReason(Kind kind, String query, Relation relation, Variant variant, List<String> inTitle,
                     List<String> inText) {
        this(kind, query, relation, variant, inTitle, inText, null);
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

    /**
     * Der Treffer kam als Nachbar einer Ausgangsseite in die Suche (R2) und traf dann die Anfrage {@code query} wie
     * jeder Treffer; die Ursachen dieses Treffers stehen daneben.
     */
    public static HitReason neighbor(String query, Neighbor via) {
        return new HitReason(Kind.NEIGHBOR, query, null, null, List.of(), List.of(), Objects.requireNonNull(via,
                "via"));
    }

    /**
     * Suchmethode „Fachbegriffe“ (W8): Die Seite belegt Aussagen des Wissensgraphen zum bestätigten Fachbegriff
     * {@code term} der Anfrage und wurde mit ihm als Suchtext auf genau diesen Seiten gefunden; die Ursachen dieser
     * Suche stehen daneben.
     */
    public static HitReason term(String term) {
        return new HitReason(Kind.TERM, Objects.requireNonNull(term, "term"), null, null, List.of(), List.of());
    }

    /** Dieselbe Ursache als Ergebnis einer Variante der progressiven Suche; {@code null} für die ursprüngliche. */
    public HitReason fromVariant(Variant variant) {
        return new HitReason(kind, query, relation, variant, inTitle, inText, via);
    }

    /** Ob der Treffer allein über den Wortlaut der eingegebenen Anfrage kam, ohne Ausweitung und ohne Variante. */
    public boolean original() {
        return relation == null && variant == null && via == null;
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

    /** Art der Beziehung eines vorgeschlagenen Synonyms eines Fachbegriffs (K5a, „Synonym (Vorschlag)“). */
    public static final String PROPOSED_SYNONYM = "synonym-proposed";

    /**
     * Ob der einzige Textgrund eines Treffers ein vorgeschlagenes Synonym ist (K5a): Alle Ursachen aus Volltext oder
     * Quellensuche kamen über {@link #PROPOSED_SYNONYM}, und es gibt mindestens eine. Solche Treffer stehen hinter den
     * übrigen; semantische und Nachbar-Ursachen zählen nicht als Textgrund.
     */
    public static boolean onlyProposedSynonym(List<HitReason> reasons) {
        boolean any = false;
        for (HitReason reason : reasons == null ? List.<HitReason>of() : reasons) {
            if (reason.kind != Kind.TEXT && reason.kind != Kind.SOURCE) {
                continue;
            }
            if (reason.relation == null || !PROPOSED_SYNONYM.equals(reason.relation.type())) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /** Die Liste stabil umsortiert: Einträge, deren einziger Textgrund ein Synonymvorschlag ist, ans Ende (K5a). */
    public static <T> List<T> proposedSynonymsLast(List<T> items,
                                                   java.util.function.Function<T, List<HitReason>> reasons) {
        List<T> first = new ArrayList<>(items.size());
        List<T> last = new ArrayList<>();
        for (T item : items) {
            (onlyProposedSynonym(reasons.apply(item)) ? last : first).add(item);
        }
        if (last.isEmpty()) {
            return items;
        }
        first.addAll(last);
        return first;
    }

    private String identity() {
        return kind + "\u0000" + query.toLowerCase(Locale.ROOT) + "\u0000" + relation + "\u0000" + variant
                + "\u0000" + via;
    }

    private HitReason withWordsOf(HitReason other) {
        return new HitReason(kind, query, relation, variant, words(inTitle, other.inTitle),
                words(inText, other.inText), via);
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
        SEMANTIC,
        /**
         * Suchmethode „Nachbarseiten“ (R2, nur mit Haken): Die Seite ist Eltern-, Unterseite oder Verweisziel bzw.
         * -quelle eines besseren Treffers und traf die Anfrage danach in derselben Bewertung wie jeder Treffer.
         */
        NEIGHBOR,
        /**
         * Suchmethode „Fachbegriffe“ (W8, nur mit Haken): Die Anfrage nennt einen bestätigten Fachbegriff, und die
         * Seite belegt Aussagen des Wissensgraphen zu ihm; {@link HitReason#query()} ist der Fachbegriff.
         */
        TERM
    }

    /**
     * Die Ausgangsseite eines Nachbar-Treffers (R2): ein ausgelieferter, für den Aufrufer lesbarer Treffer derselben
     * Suche, und die Kante des Strukturgraphen von ihr zum Nachbarn.
     *
     * @param sourceId Quelle der Ausgangsseite
     * @param pageId   Ausgangsseite
     * @param title    Titel der Ausgangsseite, wie der Treffer ihn trug
     * @param edge     Kante von der Ausgangsseite zum Nachbarn
     */
    public record Neighbor(String sourceId, String pageId, String title, Edge edge) {

        public Neighbor {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(pageId, "pageId");
            Objects.requireNonNull(edge, "edge");
            title = title == null ? "" : title;
        }

        /** Die Kante von der Ausgangsseite zum Nachbarn, wie im Strukturgraphen (G1). */
        public enum Edge {
            /** Der Nachbar ist die Elternseite der Ausgangsseite. */
            PARENT,
            /** Der Nachbar ist eine Unterseite der Ausgangsseite. */
            CHILD,
            /** Die Ausgangsseite verweist auf den Nachbarn. */
            LINKS_TO,
            /** Der Nachbar verweist auf die Ausgangsseite. */
            LINKED_FROM
        }
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
