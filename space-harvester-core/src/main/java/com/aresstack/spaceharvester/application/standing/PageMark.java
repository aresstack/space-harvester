package com.aresstack.spaceharvester.application.standing;

import java.util.Objects;

/**
 * Ein Hinweis aus Labels oder Titeln, dass eine Seite überholt oder ein Entwurf ist (ADR 0032, FL6).
 *
 * @param kind   überholt oder Entwurf
 * @param where  Label, eigener Titel oder Titel eines Vorfahren
 * @param word   das gefundene Wort, klein geschrieben
 * @param pageId die Seite, an der es steht (bei {@link Where#ANCESTOR_TITLE} der Vorfahr)
 */
public record PageMark(Kind kind, Where where, String word, String pageId) {

    public PageMark {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(where, "where");
        Objects.requireNonNull(word, "word");
        Objects.requireNonNull(pageId, "pageId");
    }

    public enum Kind {
        OUTDATED,
        DRAFT
    }

    public enum Where {
        LABEL,
        TITLE,
        ANCESTOR_TITLE
    }
}
