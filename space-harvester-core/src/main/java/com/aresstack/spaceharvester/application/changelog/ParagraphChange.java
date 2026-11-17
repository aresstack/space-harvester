package com.aresstack.spaceharvester.application.changelog;

import java.util.List;
import java.util.Objects;

/**
 * Ein geänderter Absatz im Lesetext einer Seite (ADR 0030, Abschnitt 4).
 *
 * @param kind     hinzugekommen, entfallen oder geändert
 * @param before   der alte Absatz, {@code null} bei {@link Kind#ADDED}; in der Länge begrenzt
 * @param after    der neue Absatz, {@code null} bei {@link Kind#REMOVED}; in der Länge begrenzt
 * @param headings Überschriftenpfad des Abschnitts, oberste zuerst (ADR 0031): bei {@link Kind#REMOVED} aus dem alten
 *                 Text, sonst aus dem neuen; leer vor der ersten Überschrift oder ohne Gliederung; höchstens die vier
 *                 tiefsten, je in der Länge begrenzt. Inhalt wie der Absatz: dieselben Rechte und Löschregeln.
 */
public record ParagraphChange(Kind kind, String before, String after, List<String> headings) {

    public enum Kind { ADDED, REMOVED, CHANGED }

    public ParagraphChange(Kind kind, String before, String after) {
        this(kind, before, after, List.of());
    }

    public ParagraphChange {
        headings = headings == null ? List.of() : List.copyOf(headings);
        Objects.requireNonNull(kind, "kind");
        if ((kind == Kind.ADDED) != (before == null) || (kind == Kind.REMOVED) != (after == null)) {
            throw new IllegalArgumentException("Absatz passt nicht zur Art " + kind);
        }
    }
}
