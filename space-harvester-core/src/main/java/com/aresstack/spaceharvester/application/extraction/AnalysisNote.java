package com.aresstack.spaceharvester.application.extraction;

import java.util.Objects;

/**
 * Ein Prüfhinweis zum ganzen Ergebnis, etwa zur Unvollständigkeit. Macht etwa ein Ergebnis ohne KI als regelbasierten
 * Teilbefund erkennbar.
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
        AI_PARTIAL,
        /** Keine Rechtsquellen verfügbar. */
        KNOWLEDGE_EMPTY,
        /** Eine erwartete Information fehlt im Dokument. */
        MISSING
    }

    public AnalysisNote {
        Objects.requireNonNull(code, "code");
        text = text == null ? "" : text;
    }
}
