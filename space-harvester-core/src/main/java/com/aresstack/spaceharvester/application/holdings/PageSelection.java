package com.aresstack.spaceharvester.application.holdings;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Eine zu Seiten aufgelöste {@link Selection} (ADR 0029): die Seiten, dazu die Abdeckung.
 *
 * @param pages    je Quelle die Seiten der Auswahl, die der Aufrufer sehen darf, nach dem Katalog im Cache (auch nur
 *                 aus der Quelle bekannte und gesperrte); leer, wenn der Cache nichts davon kennt. So geht die Auswahl
 *                 an Suche, Begriffe und Graph.
 * @param counts   Zähler über diese Seiten, jede einmal: {@code pages} ist das „m“, {@link HoldingCounts#stored()} das
 *                 „n“ von „n von m Seiten lokal“
 * @param spaces   Stand jedes berührten Space, etwa für „Stand vom“
 * @param complete ob die Auflösung vollständig ist: Jeder berührte Space hat eine bestätigte Inventur und jede gewählte
 *                 Seite steht im Katalog. Ohne Inventur kennt der Cache nur einzeln geladene Seiten, deren Eltern oft
 *                 fehlen; ein Teilbaum ist dann lückenhaft, und die Ansicht muss das sagen.
 * @param locked   die gesperrten Seiten unter {@code pages} (ADR 0014, {@link HoldingState#LOCKED}): Sie haben keinen
 *                 Inhalt mehr; eine Ansicht, die Seiten einzeln nennt oder zählt (etwa „ohne Analyse“), lässt sie weg
 */
public record PageSelection(PageSet pages, HoldingCounts counts, List<SpaceSummary> spaces, boolean complete,
                            PageSet locked) {

    public PageSelection {
        Objects.requireNonNull(pages, "pages");
        Objects.requireNonNull(counts, "counts");
        Objects.requireNonNull(locked, "locked");
        spaces = List.copyOf(spaces);
    }

    /** Ohne Angabe gesperrter Seiten. */
    public PageSelection(PageSet pages, HoldingCounts counts, List<SpaceSummary> spaces, boolean complete) {
        this(pages, counts, spaces, complete, PageSet.EMPTY);
    }

    /** Ob die Seite zur Auswahl gehört; O(1). */
    public boolean contains(String sourceId, String pageId) {
        return pages.contains(sourceId, pageId);
    }

    public boolean contains(PageKey key) {
        return pages.contains(key);
    }

    /** Die Seiten einer Quelle; leer, wenn die Auswahl dort keine hat. */
    public Set<String> pagesOf(String sourceId) {
        return pages.pagesOf(sourceId);
    }

    /** Anzahl der Seiten über alle Quellen, gesperrte eingeschlossen. */
    public int size() {
        return pages.size();
    }

    /**
     * Anzahl der Seiten ohne die gesperrten ({@link #locked()}): Nur sie haben Inhalt. Sind alle gesperrt, ist eine
     * Auswahl für Ansichten über Inhalte so gut wie leer.
     */
    public int unlockedSize() {
        int locked = 0;
        for (Map.Entry<String, Set<String>> source : this.locked.asMap().entrySet()) {
            for (String pageId : source.getValue()) {
                if (pages.contains(source.getKey(), pageId)) {
                    locked++;
                }
            }
        }
        return pages.size() - locked;
    }
}
