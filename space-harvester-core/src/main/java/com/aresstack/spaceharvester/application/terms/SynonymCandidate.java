package com.aresstack.spaceharvester.application.terms;

import java.util.List;
import java.util.Objects;

/**
 * Ein Kandidatenpaar der Synonymerkennung (#336, Stufen A und B): zwei Begriffe, der Grund, aus dem sie zusammen
 * geraten sind, und je Begriff kurze Fundstellen als Beleg und Kontext. Ein Kandidat ist noch keine Beziehung: Erst
 * die KI-Prüfung (Stufe C) oder eine der Regeln, die ohne Modell auskommen ({@link Reason#ruleType()}), macht einen
 * Vorschlag daraus.
 *
 * @param fromTerm    erster Begriff im Wortlaut
 * @param toTerm      zweiter Begriff im Wortlaut
 * @param reason      woher der Kandidat stammt
 * @param fromContext Fundstellen des ersten Begriffs, markiert mit {@code [[} und {@code ]]}
 * @param toContext   Fundstellen des zweiten Begriffs
 * @param similarity  Kosinusähnlichkeit der Kontexte bei {@link Reason#EMBEDDING}, sonst {@code 0}
 */
public record SynonymCandidate(String fromTerm, String toTerm, Reason reason, List<Context> fromContext,
                               List<Context> toContext, double similarity) {

    public SynonymCandidate {
        Objects.requireNonNull(fromTerm, "fromTerm");
        Objects.requireNonNull(toTerm, "toTerm");
        Objects.requireNonNull(reason, "reason");
        fromContext = List.copyOf(fromContext);
        toContext = List.copyOf(toContext);
    }

    /** Woher ein Kandidat stammt. */
    public enum Reason {
        /** Gleicher Wortlaut bis auf Bindestrich, Leerzeichen, Umlautschreibung oder ß ({@code E-Mail}, {@code Email}). */
        SPELLING(RelationType.VARIANT),
        /** Ein Text nennt die Gleichsetzung ausdrücklich („auch bezeichnet als“, „gleichbedeutend mit“). */
        PHRASE(RelationType.SYNONYM),
        /** Zwei bestätigte Ausschreibungen derselben Abkürzung. */
        ACRONYM_SIBLINGS(null),
        /** In einem anderen Geltungsbereich bestätigt; hier kommen beide Begriffe ebenfalls vor. */
        CONFIRMED_ELSEWHERE(null),
        /**
         * Ähnliche Vektoren nach dem Embedding-Modell; ohne Chat-Prüfung direkt ein Synonymvorschlag mit der
         * Ähnlichkeit als Grund, mit Chat-Prüfung erst nach deren Urteil.
         */
        EMBEDDING(null);

        private final RelationType ruleType;

        Reason(RelationType ruleType) {
            this.ruleType = ruleType;
        }

        /** Die Art, die die Regel ohne Modell vorschlägt, oder {@code null}, wenn erst die KI-Prüfung entscheidet. */
        public RelationType ruleType() {
            return ruleType;
        }
    }

    /**
     * Eine Fundstelle als Kontext.
     *
     * @param id       Nummer in der Anfrage an das Modell, ab 1 je Kandidat
     * @param text     kurzer Ausschnitt, der Begriff mit {@code [[} und {@code ]]} markiert
     * @param evidence die Stelle im Begriffsindex
     */
    public record Context(int id, String text, RelationEvidence evidence) {

        public Context {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    /** Schlüssel ohne Reihenfolge der Begriffe, zum Zusammenführen gleicher Paare. */
    public String pairKey() {
        String from = TermText.normalize(fromTerm);
        String to = TermText.normalize(toTerm);
        return from.compareTo(to) <= 0 ? from + '\u0000' + to : to + '\u0000' + from;
    }
}
