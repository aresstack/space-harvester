package com.aresstack.spaceharvester.ai;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Strenge JSON-Antworten eines Modells (ADR 0035; aus {@code ChatStatementExtractor} herausgelöst, Verhalten
 * unverändert): das JSON-Objekt aus der Antwort lesen und eine unlesbare Antwort genau einmal mit einer
 * Reparaturanfrage nachfordern. Meldungen nennen nur die Form der Antwort, nie ihren Text.
 */
public final class StrictJsonAnswer {

    /** Die Reparaturanfrage nach einer unlesbaren Antwort. */
    public static final String REPAIR = "Deine Antwort war kein lesbares JSON-Objekt. Antworte jetzt ausschließlich mit "
            + "dem JSON-Objekt in der beschriebenen Form, ohne weiteren Text.";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private StrictJsonAnswer() {
    }

    /** Eine unbrauchbare Antwort; die Meldung beschreibt nur ihre Form. */
    public static class Unreadable extends RuntimeException {

        public Unreadable(String reason) {
            super(reason);
        }

        public Unreadable(String reason, Throwable cause) {
            super(reason, cause);
        }
    }

    /** Wie das Modell gefragt wird. */
    public interface Asker {

        /** Die erste Anfrage. */
        String ask();

        /**
         * Die Reparaturanfrage.
         *
         * @param previous    die unbrauchbare Antwort (für ein Gespräch als Antwort des Assistenten)
         * @param instruction {@link #REPAIR}
         */
        String repair(String previous, String instruction);
    }

    /**
     * Fragt, liest und repariert einmal. Eine Ausnahme aus der ersten Anfrage geht unverändert weiter.
     *
     * @param parse   liest eine Antwort; wirft {@link Unreadable}, wenn sie unbrauchbar ist
     * @param combine baut den Fehler, wenn auch die Reparatur unbrauchbar war (erster und zweiter Grund)
     */
    public static <T> T read(Asker asker, Function<String, T> parse,
                             BiFunction<Unreadable, Unreadable, ? extends RuntimeException> combine) {
        Objects.requireNonNull(asker, "asker");
        String answer = asker.ask();
        try {
            return parse.apply(answer);
        } catch (Unreadable unreadable) {
            try {
                return parse.apply(asker.repair(answer == null ? "" : answer, REPAIR));
            } catch (Unreadable again) {
                throw combine.apply(unreadable, again);
            }
        }
    }

    /** Wie {@link #read(Asker, Function, BiFunction)} mit „erster Grund; after repair: zweiter Grund“. */
    public static <T> T read(Asker asker, Function<String, T> parse) {
        return read(asker, parse, (first, second) -> new Unreadable(first.getMessage() + "; after repair: "
                + second.getMessage()));
    }

    /**
     * Das JSON-Objekt einer Antwort: vom ersten „{“ bis zum letzten „}“, Text davor und danach zählt nicht.
     *
     * @throws Unreadable leer, ohne Objekt oder unlesbar
     */
    public static ObjectNode object(String answer) {
        if (answer == null || answer.trim().isEmpty()) {
            throw new Unreadable("empty answer");
        }
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new Unreadable("no JSON object in " + answer.length() + " characters");
        }
        JsonNode root;
        try {
            root = JSON.readTree(answer.substring(start, end + 1));
        } catch (JacksonException e) {
            // Nur die Art: die Meldung von Jackson zitiert die Antwort.
            throw new Unreadable("unreadable JSON: " + e.getClass().getSimpleName());
        }
        if (!(root instanceof ObjectNode object)) {
            throw new Unreadable("unreadable JSON: JSON value is no object");
        }
        return object;
    }
}
