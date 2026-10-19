package com.aresstack.spaceharvester.application.passages;

import java.util.List;
import java.util.Objects;

/**
 * Ein Abschnitt der Dokumentnavigation (#181, ADR 0020), wie {@code NavigationSection} im Enterprise AI Client: eine
 * Originalüberschrift oder ein erkannter Themenabschnitt mit seiner Stelle im Text (FA-05).
 *
 * @param id       in der Seite eindeutig: {@code h<n>} für die n-te Überschrift, {@code p<start>} für einen
 *                 Themenabschnitt
 * @param label    Überschrift oder Bezeichnung des Themenabschnitts
 * @param origin   Originalüberschrift oder erkannt (FA-04: in der Ansicht unterscheidbar)
 * @param level    Ebene in der Navigation, ab 1
 * @param start    erstes Zeichen im Text (UTF-16)
 * @param end      Ende, exklusiv
 * @param anchor   Textanfang an {@code start}, höchstens 60 Zeichen bis zum ersten Zeilenumbruch
 * @param parentId das übergeordnete Kapitel oder {@code null}
 * @param children Unterabschnitte in Lesereihenfolge
 */
public record NavigationSection(String id, String label, Origin origin, int level, int start, int end, String anchor,
                                String parentId, List<NavigationSection> children) {

    public NavigationSection {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(origin, "origin");
        if (level < 1 || start < 0 || end < start) {
            throw new IllegalArgumentException("Ungültiger Abschnitt");
        }
        anchor = anchor == null ? "" : anchor;
        children = children == null ? List.of() : List.copyOf(children);
    }

    /** Woher ein Abschnitt stammt. */
    public enum Origin {
        /** Eine Überschrift der Seite. */
        HEADING,
        /** Ein erkannter Themenabschnitt. */
        TOPIC
    }

    /** Ob der Abschnitt erkannt statt überschrieben ist. */
    public boolean generated() {
        return origin == Origin.TOPIC;
    }

    NavigationSection placed(int level, String parentId) {
        return new NavigationSection(id, label, origin, level, start, end, anchor, parentId, children);
    }
}
