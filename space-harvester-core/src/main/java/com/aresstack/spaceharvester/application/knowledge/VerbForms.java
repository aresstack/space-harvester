package com.aresstack.spaceharvester.application.knowledge;

import java.util.Locale;

/**
 * Die eine Stelle für die Verb-Grundform einer Aussage (Paket K1): das Verzeichnis der Verben ({@link VerbQueries})
 * und die Fachsicht des Wissensgraphen lesen sie hier, damit dasselbe Verb überall gleich heißt.
 *
 * <p>Die Grundform ist nur so gut wie {@link ParsedStatements.Statement#predicateLemma()} des Analysators. Fehlt sie,
 * dient der Prädikatstext als Rückfall; {@link #fromText} sagt das, damit eine Flexionsform nicht als eigenes Verb
 * wirkt. Trennbare Verben und Hilfsverben behandelt der Analysator, nicht diese Klasse.
 */
public final class VerbForms {

    private VerbForms() {
    }

    /** Grundform in Kleinschreibung, getrimmt; ohne Grundform der Prädikatstext. */
    public static String lemma(ParsedStatements.Statement statement) {
        String lemma = fromText(statement) ? statement.predicateText() : statement.predicateLemma();
        return lemma.strip().toLowerCase(Locale.ROOT);
    }

    /** Ob der Analysator keine Grundform geliefert hat und {@link #lemma} den Prädikatstext nimmt. */
    public static boolean fromText(ParsedStatements.Statement statement) {
        return statement.predicateLemma() == null || statement.predicateLemma().isBlank();
    }

    /**
     * Ob die Aussage als Verb zählt: eigene Stelle ({@link ParsedStatements.Derivation#ownSite}; eine Aussage der Vorstufe
     * oder eine Korrektur des Prüfschritts steht neben ihrem Original und zählte es sonst doppelt, ADR 0032), keine
     * Zustandsaussage (ihr Prädikat ist kein Verb), zählt nach {@link CountedStatements}, und das Prädikat ist nicht
     * leer.
     */
    public static boolean counts(ParsedStatements.Statement statement) {
        return original(statement) && !statement.state() && CountedStatements.level(statement).isPresent()
                && !lemma(statement).isEmpty();
    }

    private static boolean original(ParsedStatements.Statement statement) {
        return statement.derivation() == null || statement.derivation().ownSite();
    }
}
