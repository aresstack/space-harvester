package com.aresstack.spaceharvester.application.changelog.topics;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Eine Änderungseinheit eines Updates (ADR 0030, Nachtrag Abschnitt 3): ein geänderter Absatz oder ein
 * Seitenereignis ohne Text, vorverarbeitet für die thematische Gruppierung. Ohne KI, deterministisch. Dieselbe
 * Änderung auf mehreren Seiten ist eine Einheit mit mehreren Fundstellen; gewanderter Text ist eine Einheit
 * {@link Nature#RELOCATION} mit Herkunft und Ziel.
 *
 * @param id            stabile Kennung aus Quelle, Seite, Schritt und Absatzposition der ersten Fundstelle (Hash, nie
 *                      Inhalt im Klartext)
 * @param kind          was sich geändert hat
 * @param nature        Art der Änderung, regelbasiert
 * @param before        der alte Absatz, {@code null} ohne alten Text
 * @param after         der neue Absatz, {@code null} ohne neuen Text
 * @param changedBefore die tatsächlich geänderten Sätze des alten Absatzes, in Lesereihenfolge
 * @param changedAfter  die tatsächlich geänderten Sätze des neuen Absatzes, in Lesereihenfolge
 * @param occurrences   die Fundstellen, nach Space, Pfad und Seite; bei {@link Nature#RELOCATION} zuerst die Herkunft
 * @param terms         normalisierte Begriffe des geänderten Textes, sortiert
 * @param textSize      Zeichen der geänderten Sätze (alt und neu)
 * @param aiReleased    ob alle Fundstellen an einen KI-Dienst dürfen ({@code AiRelease.mayProcess}); nur dann gibt es
 *                      einen Vektor, und nur dann ist die Einheit Material für das Modell
 */
public record ChangeUnit(String id, Kind kind, Nature nature, String before, String after,
                         List<String> changedBefore, List<String> changedAfter, List<Occurrence> occurrences,
                         Set<String> terms, int textSize, boolean aiReleased) {

    /** Was sich geändert hat. */
    public enum Kind {
        PARAGRAPH_ADDED,
        PARAGRAPH_REMOVED,
        PARAGRAPH_CHANGED,
        /** Absatz entfällt an einer Stelle und kommt an einer anderen nahezu gleich hinzu. */
        PARAGRAPH_MOVED,
        PAGE_NEW,
        PAGE_REMOVED,
        RENAMED,
        MOVED,
        LABELS
    }

    /** Art der Änderung (ADR 0030, Nachtrag Abschnitt 3), regelbasiert aus dem Text. */
    public enum Nature {
        /** Wenige Wörter, Wortlaut nahezu gleich: Tippfehler, Formulierung. */
        CORRECTION,
        /** Nur Zahlen oder Daten geändert. */
        NUMBER_OR_DATE,
        /** Neuer Text. */
        NEW_SECTION,
        /** Entfallener Text. */
        REMOVED_SECTION,
        /** Wortlaut zu weniger als der Hälfte gleich. */
        MAJOR_REVISION,
        /** Übrige inhaltliche Änderung. */
        EDIT,
        /** Gewanderter Text, keine inhaltliche Änderung. */
        RELOCATION,
        /** Titel, Ort oder Labels einer Seite. */
        STRUCTURE,
        NEW_PAGE,
        REMOVED_PAGE
    }

    /** Rolle einer Fundstelle. */
    public enum Role {
        CHANGED,
        /** Herkunft gewanderten Textes. */
        FROM,
        /** Ziel gewanderten Textes. */
        TO
    }

    /**
     * Eine Fundstelle der Änderung. Ohne Bearbeiternamen: Namen stehen allein im Update-Text der Oberfläche, am Thema
     * nur ihre Zahl.
     *
     * @param path        Titel der Vorfahren, von oben
     * @param parentId    Elternseite nach der Änderung, {@code null} wenn unbekannt
     * @param headingPath Überschriften über dem Absatz, von oben; leer, solange der Unterschied sie nicht trägt
     * @param step        Schritt im Unterschied der Seite, ab 0; -1 bei Seitenereignissen
     * @param position    Position des Absatzes im Schritt, ab 0; -1 bei Seitenereignissen
     */
    public record Occurrence(PageKey page, String spaceKey, String title, List<String> path, URI url, String parentId,
                             List<String> headingPath, Instant at, Role role, int step, int position) {

        public Occurrence {
            Objects.requireNonNull(page, "page");
            Objects.requireNonNull(role, "role");
            path = List.copyOf(path);
            headingPath = List.copyOf(headingPath);
        }

        Occurrence withRole(Role other) {
            return new Occurrence(page, spaceKey, title, path, url, parentId, headingPath, at, other, step, position);
        }
    }

    public ChangeUnit {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(nature, "nature");
        changedBefore = List.copyOf(changedBefore);
        changedAfter = List.copyOf(changedAfter);
        occurrences = List.copyOf(occurrences);
        terms = Set.copyOf(terms);
        if (occurrences.isEmpty()) {
            throw new IllegalArgumentException("Eine Änderungseinheit braucht eine Fundstelle");
        }
    }

    /** Die erste Fundstelle (bei {@link Nature#RELOCATION} die Herkunft). */
    public Occurrence first() {
        return occurrences.getFirst();
    }

    /** Die betroffenen Seiten, ohne Doppelte, in der Reihenfolge der Fundstellen. */
    public List<PageKey> pages() {
        return occurrences.stream().map(Occurrence::page).distinct().toList();
    }

    /** Ob die Einheit Text trägt (geänderter Absatz), nicht nur ein Seitenereignis ist. */
    public boolean hasText() {
        return before != null || after != null;
    }
}
