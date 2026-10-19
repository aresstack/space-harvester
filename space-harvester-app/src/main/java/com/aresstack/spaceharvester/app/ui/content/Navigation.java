package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.application.passages.DocumentNavigationView;
import com.aresstack.spaceharvester.application.passages.NavigationSection;
import java.util.ArrayList;
import java.util.List;

/**
 * Die Dokumentnavigation einer Seite, wie sie der Navigationsbanner über dem Reiter „Text“ zeigt: Hauptkapitel in
 * Lesereihenfolge, jedes mit seinen Unterabschnitten. Abgebildet aus der Core-Antwort
 * {@code harvester.documentNavigation().page(caller, sourceId, pageId)} ({@link #of}).
 * Inhaltlich wie {@code DocumentNavigation} im Enterprise AI Client.
 *
 * @param state    Stand der Analyse
 * @param analysis {@link Analysis#SEMANTIC}: Originalüberschriften plus automatisch erkannte Themenabschnitte;
 *                 {@link Analysis#HEADINGS}: nur Originalüberschriften (Rückfall, {@code reason} sagt warum)
 * @param reason   Grund des Rückfalls ({@code no-model}, {@code source-not-approved}, {@code not-embedded},
 *                 {@code no-topic-change}, {@code model-unavailable}) oder leer
 * @param chapters die Hauptkapitel in Lesereihenfolge
 */
public record Navigation(State state, Analysis analysis, String reason, List<Section> chapters) {

    /** Stand der Analyse zur gezeigten Fassung. */
    public enum State {
        CURRENT, STALE, FAILED, MISSING
    }

    /** Wie weit die Analyse kam. */
    public enum Analysis {
        /** Nur Originalüberschriften (Rückfall oder noch nicht analysiert). */
        HEADINGS,
        /** Originalüberschriften plus semantische Themenabschnitte. */
        SEMANTIC
    }

    /** Herkunft der Bezeichnung. */
    public enum Origin {
        /** Originalüberschrift; der Text stammt aus dem Dokument. */
        HEADING,
        /** Automatisch erkannter Themenabschnitt; die Bezeichnung ist erzeugt. */
        TOPIC
    }

    public Navigation {
        if (state == null || analysis == null) {
            throw new IllegalArgumentException("state und analysis sind Pflicht");
        }
        reason = reason == null ? "" : reason;
        chapters = chapters == null ? List.of() : List.copyOf(chapters);
    }

    /** Die Antwort des Core ({@code harvester.documentNavigation().page}) als Navigation des Banners. */
    public static Navigation of(DocumentNavigationView view) {
        return new Navigation(State.valueOf(view.state().name()), Analysis.valueOf(view.analysis().name()),
                view.reason(), view.chapters().stream().map(Navigation::section).toList());
    }

    private static Section section(NavigationSection section) {
        return new Section(section.id(), section.label(), Origin.valueOf(section.origin().name()), section.level(),
                section.start(), section.end(), section.anchor(), section.parentId(),
                section.children().stream().map(Navigation::section).toList());
    }

    public boolean isEmpty() {
        return chapters.isEmpty();
    }

    /** Alle Abschnitte in Lesereihenfolge (Tiefensuche). */
    public List<Section> flatten() {
        List<Section> all = new ArrayList<Section>();
        collect(chapters, all);
        return all;
    }

    /** Der Abschnitt zur ID oder {@code null}. */
    public Section find(String sectionId) {
        for (Section section : flatten()) {
            if (section.id().equals(sectionId)) {
                return section;
            }
        }
        return null;
    }

    private static void collect(List<Section> sections, List<Section> into) {
        for (Section section : sections) {
            into.add(section);
            collect(section.children(), into);
        }
    }

    /**
     * Ein Kapitel oder Unterabschnitt. {@code start}, {@code end} und {@code anchor} beziehen sich auf den angezeigten
     * Klartext der Seite (Zeichenpositionen ab 0); {@code anchor} sind die ersten Worte an der Startposition.
     *
     * @param level    Tiefe: 1 = Hauptkapitel
     * @param end      Endposition (exklusiv) oder {@code -1}, wenn offen
     * @param parentId ID des übergeordneten Kapitels oder {@code null} für ein Hauptkapitel
     */
    public record Section(String id, String label, Origin origin, int level, int start, int end, String anchor,
                          String parentId, List<Section> children) {

        public Section {
            if (id == null || id.isEmpty() || origin == null) {
                throw new IllegalArgumentException("id und origin sind Pflicht");
            }
            if (level < 1) {
                throw new IllegalArgumentException("level beginnt bei 1: " + level);
            }
            if (start < 0 || (end >= 0 && end < start)) {
                throw new IllegalArgumentException("ungültige Position " + start + ".." + end);
            }
            label = label == null ? "" : label.trim();
            anchor = anchor == null ? "" : anchor;
            children = children == null ? List.of() : List.copyOf(children);
        }

        /** Automatisch erkannter Themenabschnitt mit erzeugter Bezeichnung. */
        public boolean isGenerated() {
            return origin == Origin.TOPIC;
        }

        @Override
        public String toString() {
            return (origin == Origin.TOPIC ? "~" : "") + label + " [" + start + ".." + end + "]"
                    + (children.isEmpty() ? "" : " " + children);
        }
    }
}
