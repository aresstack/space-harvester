package com.aresstack.spaceharvester.application.holdings;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import java.util.List;
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
 */
public record PageSelection(PageSet pages, HoldingCounts counts, List<SpaceSummary> spaces, boolean complete) {

    public PageSelection {
        Objects.requireNonNull(pages, "pages");
        Objects.requireNonNull(counts, "counts");
        spaces = List.copyOf(spaces);
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

    /** Anzahl der Seiten über alle Quellen. */
    public int size() {
        return pages.size();
    }
}
