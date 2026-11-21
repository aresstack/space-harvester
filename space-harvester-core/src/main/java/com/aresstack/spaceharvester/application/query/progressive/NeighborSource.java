package com.aresstack.spaceharvester.application.query.progressive;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.query.HitReason;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.List;
import java.util.Map;

/**
 * Woher die Suchmethode „Nachbarseiten“ (R2) ihre Kandidaten nimmt: zu Ausgangsseiten die Seiten einen Schritt weit
 * im Strukturgraphen, mit den Rechten des Aufrufers. Kandidaten sind keine Treffer: Die Suche bewertet sie danach wie
 * jeden Treffer gegen die Anfrage, und nur was sie dabei trifft, kommt in die Liste.
 */
@FunctionalInterface
public interface NeighborSource {

    /** Keine Nachbarn; die Methode liefert dann nichts. */
    NeighborSource NONE = (caller, seeds) -> Map.of();

    /**
     * @param seeds ausgelieferte, für den Aufrufer lesbare Treffer in der Reihenfolge ihrer Bewertung
     * @return je Nachbar die erste Kante, über die er kam (Ausgangsseite und Art), in der Reihenfolge der
     *         Ausgangsseiten; ohne die Ausgangsseiten selbst, nie mit einer Seite, die der Aufrufer nicht sehen darf
     */
    Map<PageKey, HitReason.Neighbor> neighbors(AccessContext caller, List<KeywordSearch.Hit> seeds);
}
