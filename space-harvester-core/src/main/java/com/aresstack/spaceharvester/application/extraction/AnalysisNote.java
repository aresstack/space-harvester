package com.aresstack.spaceharvester.application.extraction;

import java.util.Objects;

/**
 * Ein Prüfhinweis zur Extraktion als Ganzes, etwa zur Unvollständigkeit. Macht ein Ergebnis ohne KI als
 * regelbasierten Teilbefund erkennbar. Fehlende einzelne Informationen sind Felder mit {@link FieldStatus#NOT_FOUND},
 * keine Hinweise; Zustände des Rechtswissens gehören nicht hierher, sondern zur Fristprüfung.
 *
 * @param code fester Code
 * @param text Erläuterung für Menschen
 */
public record AnalysisNote(Code code, String text) {

    public enum Code {
        /** Der Dokumenttext war abgeschnitten. */
        TEXT_TRUNCATED,
        /** Die KI war nicht erreichbar; nur deterministische Extraktion. */
        AI_UNAVAILABLE,
        /** Die KI lieferte nur einen Teil. */
        AI_PARTIAL
    }

    public AnalysisNote {
        Objects.requireNonNull(code, "code");
        text = text == null ? "" : text;
    }
}
