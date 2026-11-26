package com.aresstack.spaceharvester.application.sync;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Die eine Stelle, die entscheidet, ob eine Seite ausgeschlossen ist ({@link ExclusionMarks}, Angelo 26.11.2026):
 * Baum, Suche, Abgleich und automatische Beschaffung fragen hier, keiner prüft die Markierungen selbst. Ausgeschlossen
 * ist eine Seite, wenn sie selbst, ein Vorfahr oder ihr ganzer Bereich ausgeschlossen ist. Die Vorfahren kommen aus
 * dem Bestand ({@link Parents}) oder, für einen Lauf, aus dessen Inventur; eine Kette, die abreißt, zählt nicht als
 * ausgeschlossen.
 *
 * <p>Der Ausschluss verkleinert nur den Umfang. Er ist nie ein Löschbeleg und ändert keine Rechte (ADR 0037, Punkt 3,
 * 4 und 12); die Rechteprüfung gilt zusätzlich und unverändert.
 */
public final class Exclusions {

    /** Elternseite einer Seite laut Bestand, {@code null} für eine Wurzelseite oder wenn unbekannt. */
    @FunctionalInterface
    public interface Parents {

        String parentOf(String sourceId, String pageId);
    }

    /** Anzeigename einer Seite laut Bestand, {@code null} wenn unbekannt. */
    @FunctionalInterface
    public interface Titles {

        String titleOf(String sourceId, String pageId);
    }

    /**
     * Ein Ausschluss für die Übersicht in den Einstellungen.
     *
     * @param sourceId Quelle
     * @param spaceKey Bereich (Space, Partition)
     * @param pageId   Wurzel samt Unterseiten oder {@code null} für den ganzen Bereich
     * @param title    Anzeigename laut Bestand oder leer, wenn unbekannt
     */
    public record Exclusion(String sourceId, String spaceKey, String pageId, String title) {

        public Exclusion {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(spaceKey, "spaceKey");
            pageId = pageId == null || pageId.isEmpty() ? null : pageId;
            title = title == null ? "" : title.strip();
        }

        /** Ob der ganze Bereich ausgeschlossen ist. */
        public boolean wholeSpace() {
            return pageId == null;
        }
    }

    /** Ohne Ausschlüsse; für Anwendungsfälle ohne Ablage. */
    public static final Exclusions NONE = new Exclusions(ExclusionMarks.NONE, (sourceId, pageId) -> null,
            (sourceId, pageId) -> null);

    private final ExclusionMarks marks;
    private final Parents parents;
    private final Titles titles;

    public Exclusions(ExclusionMarks marks, Parents parents, Titles titles) {
        this.marks = Objects.requireNonNull(marks, "marks");
        this.parents = Objects.requireNonNull(parents, "parents");
        this.titles = Objects.requireNonNull(titles, "titles");
    }

    /** Schließt die Seite samt allem darunter aus, mit {@code pageId == null} den ganzen Bereich. Löscht nichts. */
    public void exclude(String sourceId, String spaceKey, String pageId) {
        marks.mark(sourceId, spaceKey, pageId);
    }

    /** Blendet genau diesen Ausschluss wieder ein; Ausschlüsse darüber oder darunter bleiben. */
    public void include(String sourceId, String spaceKey, String pageId) {
        marks.unmark(sourceId, spaceKey, pageId);
    }

    /** Alle Ausschlüsse der Quelle für die Übersicht in den Einstellungen, nach Bereich und Titel. */
    public List<Exclusion> of(String sourceId) {
        List<Exclusion> all = new ArrayList<>();
        for (String spaceKey : marks.spaces(sourceId)) {
            for (String root : marks.roots(sourceId, spaceKey)) {
                String pageId = ExclusionMarks.WHOLE_SPACE.equals(root) ? null : root;
                all.add(new Exclusion(sourceId, spaceKey, pageId, pageId == null ? null : title(sourceId, pageId)));
            }
        }
        all.sort(java.util.Comparator.comparing(Exclusion::spaceKey)
                .thenComparing(exclusion -> exclusion.pageId() != null)
                .thenComparing(exclusion -> exclusion.title().toLowerCase(java.util.Locale.ROOT))
                .thenComparing(exclusion -> String.valueOf(exclusion.pageId())));
        return List.copyOf(all);
    }

    private String title(String sourceId, String pageId) {
        try {
            return titles.titleOf(sourceId, pageId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Ob genau diese Wurzel ausgeschlossen ist, mit {@code pageId == null} der ganze Bereich. */
    public boolean marked(String sourceId, String spaceKey, String pageId) {
        return spaceKey != null && marks.marked(sourceId, spaceKey, pageId);
    }

    /** Ob der ganze Bereich ausgeschlossen ist. */
    public boolean spaceExcluded(String sourceId, String spaceKey) {
        return spaceKey != null && marks.roots(sourceId, spaceKey).contains(ExclusionMarks.WHOLE_SPACE);
    }

    /**
     * Ob die Seite ausgeschlossen ist: sie selbst, ein Vorfahr laut Bestand oder ihr ganzer Bereich.
     *
     * @param spaceKey Bereich der Seite oder {@code null}, wenn unbekannt; dann zählen die Seitenwurzeln aller Bereiche
     * @param pageId   die Seite oder {@code null} für den Bereich selbst
     */
    public boolean excluded(String sourceId, String spaceKey, String pageId) {
        return within(sourceId, spaceKey, null).test(pageId);
    }

    /**
     * Das Prädikat für einen Lauf über viele Seiten eines Bereichs, mit gemerkten Zwischenergebnissen; nicht
     * threadsicher.
     *
     * @param parentOf Vorfahren aus der Inventur des Laufs oder {@code null}; was sie nicht kennt, kommt aus dem Bestand
     * @return {@code pageId -> true}, wenn die Seite ausgeschlossen ist; {@code null} steht für den Bereich selbst
     */
    public Predicate<String> within(String sourceId, String spaceKey, Function<String, String> parentOf) {
        Objects.requireNonNull(sourceId, "sourceId");
        Set<String> roots = roots(sourceId, spaceKey);
        if (roots.isEmpty()) {
            return pageId -> false;
        }
        if (roots.contains(ExclusionMarks.WHOLE_SPACE)) {
            return pageId -> true;
        }
        Function<String, String> parent = pageId -> {
            String known = parentOf == null ? null : parentOf.apply(pageId);
            return known != null ? known : parent(sourceId, pageId);
        };
        Predicate<String> covered = IndexScope.of(roots, parent);
        return pageId -> pageId != null && covered.test(pageId);
    }

    /** Ob die Quelle überhaupt Ausschlüsse hat; erspart Prüfungen je Seite. */
    public boolean any(String sourceId) {
        return !marks.spaces(sourceId).isEmpty();
    }

    private String parent(String sourceId, String pageId) {
        try {
            return parents.parentOf(sourceId, pageId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Set<String> roots(String sourceId, String spaceKey) {
        if (spaceKey != null) {
            return marks.roots(sourceId, spaceKey);
        }
        Set<String> roots = new HashSet<>();
        for (String space : marks.spaces(sourceId)) {
            roots.addAll(marks.roots(sourceId, space));
        }
        roots.remove(ExclusionMarks.WHOLE_SPACE);
        return roots;
    }
}
