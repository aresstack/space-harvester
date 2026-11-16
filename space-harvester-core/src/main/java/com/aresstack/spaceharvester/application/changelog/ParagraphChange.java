package com.aresstack.spaceharvester.application.changelog;

import java.util.Objects;

/**
 * Ein geänderter Absatz im Lesetext einer Seite (ADR 0030, Abschnitt 4).
 *
 * @param kind   hinzugekommen, entfallen oder geändert
 * @param before der alte Absatz, {@code null} bei {@link Kind#ADDED}; in der Länge begrenzt
 * @param after  der neue Absatz, {@code null} bei {@link Kind#REMOVED}; in der Länge begrenzt
 */
public record ParagraphChange(Kind kind, String before, String after) {

    public enum Kind { ADDED, REMOVED, CHANGED }

    public ParagraphChange {
        Objects.requireNonNull(kind, "kind");
        if ((kind == Kind.ADDED) != (before == null) || (kind == Kind.REMOVED) != (after == null)) {
            throw new IllegalArgumentException("Absatz passt nicht zur Art " + kind);
        }
    }
}
