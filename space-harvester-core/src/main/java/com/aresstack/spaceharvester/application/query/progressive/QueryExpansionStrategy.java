package com.aresstack.spaceharvester.application.query.progressive;

import com.aresstack.spaceharvester.application.query.HitReason;
import java.util.List;
import java.util.Objects;

/**
 * Erzeugt aus der ursprünglichen Anfrage weitere Suchanfragen, nach abnehmender Genauigkeit geordnet (ADR 0017):
 * zuerst mit möglichst vielen Begriffen des Nutzers, dann schrittweise weniger. Austauschbar, weil erprobt werden muss,
 * was funktioniert: regelbasiert ({@link RuleBasedExpansion}, Standard und Rückfall ohne KI-Dienst), über ein Modell
 * des KI-Dienstes oder mit Sprachanalyse.
 *
 * <p>Der Orchestrator ruft sie gleichzeitig mit der ursprünglichen Suche auf, entfernt Dubletten (auch die der
 * ursprünglichen Anfrage) und vergibt die Prioritäten nach der gelieferten Reihenfolge neu. Eine Ausnahme heißt „keine
 * Varianten“; die Suche scheitert nie daran.
 */
@FunctionalInterface
public interface QueryExpansionStrategy {

    /**
     * @param original    die ursprüngliche Anfrage, unverändert
     * @param maxVariants höchstens so viele Varianten
     * @return die Varianten, genaueste zuerst; ohne die ursprüngliche Anfrage
     */
    List<String> expand(String original, int maxVariants);

    /**
     * Wie {@link #expand}, mit der Herkunft der Varianten (R1: Ursache am Treffer). Eine Strategie, die sie nicht
     * kennt, meldet keine; nie wird eine Herkunft geraten.
     */
    default Variants variants(String original, int maxVariants) {
        return new Variants(expand(original, maxVariants), null);
    }

    /**
     * Varianten und ihre Herkunft.
     *
     * @param origin woher alle Varianten stammen, oder {@code null}, wenn die Strategie es nicht meldet
     */
    record Variants(List<String> texts, HitReason.Variant.Origin origin) {

        public Variants {
            texts = List.copyOf(Objects.requireNonNull(texts, "texts"));
        }
    }

    /** Keine Varianten: nur die ursprüngliche Anfrage. */
    static QueryExpansionStrategy none() {
        return (original, maxVariants) -> List.of();
    }
}
