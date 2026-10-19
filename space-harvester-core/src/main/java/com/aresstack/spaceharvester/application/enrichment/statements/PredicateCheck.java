package com.aresstack.spaceharvester.application.enrichment.statements;

import java.util.List;

/**
 * Ob die als Prädikat einer Handlung angegebenen Wörter in ihrem Satz ein Verb sind (#108). Im Enterprise AI Client
 * steht dahinter das lokale Wortartmodell ({@code PosPredicateCheck}); der Core hat keines (nur das Satzmodell von
 * OpenNLP, ADR 0020), darum gilt hier {@link #none()}: die Antwort ist unbekannt, nie geraten.
 */
public interface PredicateCheck {

    /**
     * @param predicate zuerst der Bereich des Verbs, dann eine abgetrennte Partikel
     * @return {@code TRUE}/{@code FALSE} oder {@code null}, wenn kein Modell es sagen kann
     */
    Boolean isVerbal(SentenceText sentence, List<TextSpan> predicate);

    /** Kennung des Verfahrens in der Version ({@code pos-de} oder {@code none}). */
    String id();

    /** Ohne Wortartmodell: jede Antwort unbekannt. */
    static PredicateCheck none() {
        return new PredicateCheck() {
            @Override
            public Boolean isVerbal(SentenceText sentence, List<TextSpan> predicate) {
                return null;
            }

            @Override
            public String id() {
                return "none";
            }
        };
    }
}
