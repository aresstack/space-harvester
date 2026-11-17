package com.aresstack.spaceharvester.application.simplification;

import java.util.Objects;

/**
 * Ein einfacher Hauptsatz der Vorstufe zu einem Originalsatz (ADR 0032, FL3).
 *
 * @param sentence Index des Originalsatzes in der Seite (wie {@link SimplificationSection.Sentence#index()})
 * @param flag     Kennzeichen der Vorstufe; ein eigenes Feld mit Herkunft „Vorstufe“, nie in den Zeitbezug übersetzt
 * @param text     der vereinfachte Satz
 */
public record SimplifiedSentence(int sentence, Flag flag, String text) {

    /** Kennzeichen der Anweisung, Version 1: T, B, P, V. */
    public enum Flag {
        /** T: Tatsache. */
        FACT("T"),
        /** B: Bedingung (aus wenn, sofern, sobald, ob, falls). */
        CONDITION("B"),
        /** P: Planung oder Absicht. */
        PLANNING("P"),
        /** V: verworfen oder früher. */
        DISCARDED("V");

        private final String code;

        Flag(String code) {
            this.code = code;
        }

        /** Der Buchstabe im Antwortformat. */
        public String code() {
            return code;
        }

        /** Das Kennzeichen zum Buchstaben oder {@code null}. */
        public static Flag of(String code) {
            for (Flag flag : values()) {
                if (flag.code.equals(code)) {
                    return flag;
                }
            }
            return null;
        }
    }

    public SimplifiedSentence {
        if (sentence < 0) {
            throw new IllegalArgumentException("sentence darf nicht negativ sein");
        }
        Objects.requireNonNull(flag, "flag");
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("text darf nicht leer sein");
        }
    }
}
