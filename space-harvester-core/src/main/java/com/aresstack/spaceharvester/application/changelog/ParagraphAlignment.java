package com.aresstack.spaceharvester.application.changelog;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Zuordnung der Absätze zweier Fassungen eines Lesetexts (ADR 0030, Abschnitt 4), die einzige im Core: der Reiter
 * „Text“ ({@link TextContentDiffer}) und die farbige Markierung in der Lese-Ansicht nennen damit dasselbe „geändert“.
 * Reine Funktion ohne Kürzung und ohne Obergrenze der Schritte.
 *
 * <p>Regeln: gemeinsamer Anfang und gemeinsames Ende gelten als gleich; der Rest wird über die längste gemeinsame Folge
 * verglichen, entfallene vor hinzugekommenen Absätzen. In einem zusammenhängenden Lauf ohne gleiche Absätze gelten
 * entfallene und hinzugekommene Absätze der Reihe nach paarweise als geändert ({@link Kind#CHANGE}); was übrig bleibt,
 * ist entfallen ({@link Kind#REMOVE}) bzw. hinzugekommen ({@link Kind#ADD}), in dieser Reihenfolge. Braucht der Vergleich
 * mehr als {@value #MAX_CELLS} Zellen, liefert die Zuordnung {@link TooLarge} statt eines Ergebnisses.
 */
public final class ParagraphAlignment {

    /** Größte Vergleichstabelle (Zeilen mal Spalten des Mittelteils, jeweils plus eins). */
    public static final long MAX_CELLS = 1_000_000;

    private ParagraphAlignment() {
    }

    /** Art eines Schritts. */
    public enum Kind {
        /** Der Absatz steht unverändert in beiden Fassungen. */
        EQUAL,
        /** Ein entfallener und ein hinzugekommener Absatz an derselben Stelle: geändert. */
        CHANGE,
        /** Der Absatz steht nur in der alten Fassung. */
        REMOVE,
        /** Der Absatz steht nur in der neuen Fassung. */
        ADD
    }

    /**
     * Ein Schritt der Zuordnung.
     *
     * @param before Index in der alten Fassung, {@code -1} bei {@link Kind#ADD}
     * @param after  Index in der neuen Fassung, {@code -1} bei {@link Kind#REMOVE}
     */
    public record Step(Kind kind, int before, int after) {

        public Step {
            Objects.requireNonNull(kind, "kind");
            if ((kind == Kind.ADD) != (before < 0) || (kind == Kind.REMOVE) != (after < 0)) {
                throw new IllegalArgumentException("Indizes passen nicht zur Art " + kind);
            }
        }
    }

    /** Ergebnis von {@link #align}: {@link Aligned} oder {@link TooLarge}. */
    public sealed interface Alignment permits Aligned, TooLarge {
    }

    /**
     * Die Zuordnung: jeder Absatz beider Fassungen kommt in genau einem Schritt vor, beide Indizes steigen.
     */
    public record Aligned(List<Step> steps) implements Alignment {

        public Aligned {
            steps = List.copyOf(steps);
        }
    }

    /**
     * Der Mittelteil nach Abzug von gemeinsamem Anfang und Ende ist für den Vergleich zu groß.
     *
     * @param start      Länge des gemeinsamen Anfangs
     * @param endBefore  Ende des Mittelteils in der alten Fassung (exklusiv); danach folgt das gemeinsame Ende
     * @param endAfter   Ende des Mittelteils in der neuen Fassung (exklusiv)
     */
    public record TooLarge(int start, int endBefore, int endAfter) implements Alignment {
    }

    /** Ordnet die Absätze beider Fassungen einander zu; siehe Klassenbeschreibung. */
    public static Alignment align(List<String> before, List<String> after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        int start = 0;
        while (start < before.size() && start < after.size() && before.get(start).equals(after.get(start))) {
            start++;
        }
        int endBefore = before.size();
        int endAfter = after.size();
        while (endBefore > start && endAfter > start && before.get(endBefore - 1).equals(after.get(endAfter - 1))) {
            endBefore--;
            endAfter--;
        }
        List<String> x = before.subList(start, endBefore);
        List<String> y = after.subList(start, endAfter);
        if ((long) (x.size() + 1) * (y.size() + 1) > MAX_CELLS) {
            return new TooLarge(start, endBefore, endAfter);
        }
        return new Aligned(steps(start, endBefore, endAfter, before.size(), common(x, y)));
    }

    /**
     * Wie {@link #align}, aber ein zu großer Mittelteil gilt als ganz ersetzt, wie es der Reiter „Text“ seit jeher
     * zeigt. Nur für {@link TextContentDiffer}.
     */
    static List<Step> alignOrReplace(List<String> before, List<String> after) {
        return switch (align(before, after)) {
            case Aligned aligned -> aligned.steps();
            case TooLarge large -> steps(large.start(), large.endBefore(), large.endAfter(), before.size(),
                    replaced(large.endBefore() - large.start(), large.endAfter() - large.start()));
        };
    }

    private enum Op { EQUAL, REMOVE, ADD }

    /** Schritte über beide ganzen Fassungen aus den Operationen des Mittelteils. */
    private static List<Step> steps(int start, int endBefore, int endAfter, int beforeSize, List<Op> ops) {
        List<Step> steps = new ArrayList<>();
        for (int p = 0; p < start; p++) {
            steps.add(new Step(Kind.EQUAL, p, p));
        }
        int i = start;
        int j = start;
        int k = 0;
        while (k < ops.size()) {
            if (ops.get(k) == Op.EQUAL) {
                steps.add(new Step(Kind.EQUAL, i++, j++));
                k++;
                continue;
            }
            List<Integer> removed = new ArrayList<>();
            List<Integer> added = new ArrayList<>();
            while (k < ops.size() && ops.get(k) != Op.EQUAL) {
                if (ops.get(k) == Op.REMOVE) {
                    removed.add(i++);
                } else {
                    added.add(j++);
                }
                k++;
            }
            int paired = Math.min(removed.size(), added.size());
            for (int p = 0; p < paired; p++) {
                steps.add(new Step(Kind.CHANGE, removed.get(p), added.get(p)));
            }
            for (int p = paired; p < removed.size(); p++) {
                steps.add(new Step(Kind.REMOVE, removed.get(p), -1));
            }
            for (int p = paired; p < added.size(); p++) {
                steps.add(new Step(Kind.ADD, -1, added.get(p)));
            }
        }
        for (int b = endBefore, a = endAfter; b < beforeSize; b++, a++) {
            steps.add(new Step(Kind.EQUAL, b, a));
        }
        return steps;
    }

    private static List<Op> replaced(int removed, int added) {
        List<Op> ops = new ArrayList<>(removed + added);
        for (int i = 0; i < removed; i++) {
            ops.add(Op.REMOVE);
        }
        for (int j = 0; j < added; j++) {
            ops.add(Op.ADD);
        }
        return ops;
    }

    /** Längste gemeinsame Folge, entfallene vor hinzugekommenen Absätzen. */
    private static List<Op> common(List<String> x, List<String> y) {
        int n = x.size();
        int m = y.size();
        int[][] length = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                length[i][j] = x.get(i).equals(y.get(j)) ? length[i + 1][j + 1] + 1
                        : Math.max(length[i + 1][j], length[i][j + 1]);
            }
        }
        List<Op> ops = new ArrayList<>(n + m);
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (x.get(i).equals(y.get(j))) {
                ops.add(Op.EQUAL);
                i++;
                j++;
            } else if (length[i + 1][j] >= length[i][j + 1]) {
                ops.add(Op.REMOVE);
                i++;
            } else {
                ops.add(Op.ADD);
                j++;
            }
        }
        while (i++ < n) {
            ops.add(Op.REMOVE);
        }
        while (j++ < m) {
            ops.add(Op.ADD);
        }
        return ops;
    }
}
