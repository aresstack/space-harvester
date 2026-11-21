package com.aresstack.spaceharvester.application.query.progressive;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.query.HitReason;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.structure.Connection;
import com.aresstack.spaceharvester.application.structure.ConnectionKind;
import com.aresstack.spaceharvester.application.structure.PageConnections;
import com.aresstack.spaceharvester.application.structure.PageRef;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Die Nachbarn aus dem Strukturgraphen (G1, {@link PageConnections}) für die Suchmethode „Nachbarseiten“ (R2): nur
 * ausdrückliche Kanten der Quelle, also Elternseite, Unterseiten und Verweise in beide Richtungen, einen Schritt weit.
 * Bestätigte Begriffsbeziehungen nutzt die Suche schon in der Ausweitung, berechnete Ähnlichkeit wäre eine zweite
 * Rangfolge (ADR 0013); beide bleiben draußen. {@link PageConnections} prüft die Rechte des Aufrufers an jeder Seite;
 * eine Ausgangsseite, die dort nicht (mehr) liegt oder nicht sichtbar ist, trägt nichts bei.
 */
public final class NeighborCandidates implements NeighborSource {

    private static final System.Logger LOG = System.getLogger(NeighborCandidates.class.getName());

    /** So viele der besten Treffer sind Ausgangsseiten. */
    public static final int MAX_SEEDS = 10;
    /** Höchstens so viele Kanten je Art und Ausgangsseite. */
    public static final int MAX_PER_KIND = 10;
    /** Höchstens so viele Nachbarn insgesamt. */
    public static final int MAX_CANDIDATES = 50;

    /** Die Kantenarten der Methode, in der Reihenfolge, in der sie Nachbarn beitragen. */
    static final Set<ConnectionKind> KINDS = EnumSet.of(ConnectionKind.PARENT, ConnectionKind.CHILD,
            ConnectionKind.LINKS_TO, ConnectionKind.LINKED_FROM);

    private static final PageConnections.Query QUERY = new PageConnections.Query(KINDS, MAX_PER_KIND,
            KINDS.size() * MAX_PER_KIND);

    private final PageConnections connections;

    public NeighborCandidates(PageConnections connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    @Override
    public Map<PageKey, HitReason.Neighbor> neighbors(AccessContext caller, List<KeywordSearch.Hit> seeds) {
        return find(caller, seeds).local();
    }

    @Override
    public Found find(AccessContext caller, List<KeywordSearch.Hit> seeds) {
        Map<PageKey, KeywordSearch.Hit> chosen = new LinkedHashMap<>();
        for (KeywordSearch.Hit seed : seeds) {
            if (chosen.size() >= MAX_SEEDS) {
                break;
            }
            chosen.putIfAbsent(new PageKey(seed.sourceId(), seed.pageId()), seed);
        }
        Map<PageKey, HitReason.Neighbor> found = new LinkedHashMap<>();
        Map<PageKey, HitReason.Neighbor> missing = new LinkedHashMap<>();
        Map<PageKey, PageRef> places = new LinkedHashMap<>();
        for (Map.Entry<PageKey, KeywordSearch.Hit> entry : chosen.entrySet()) {
            if (found.size() >= MAX_CANDIDATES) {
                break;
            }
            KeywordSearch.Hit seed = entry.getValue();
            PageConnections.Connections result;
            try {
                result = connections.connections(caller, seed.sourceId(), seed.pageId(), QUERY);
            } catch (RuntimeException e) {
                // Nicht im Cache, nicht (mehr) sichtbar oder Quelle weg: diese Ausgangsseite trägt nichts bei.
                LOG.log(System.Logger.Level.DEBUG, "Keine Nachbarn ({0})", e.getClass().getName());
                continue;
            }
            for (Connection edge : result.edges()) {
                if (found.size() >= MAX_CANDIDATES) {
                    break;
                }
                PageKey target = edge.target().key();
                if (!KINDS.contains(edge.kind()) || chosen.containsKey(target) || found.containsKey(target)) {
                    continue;
                }
                found.put(target, new HitReason.Neighbor(seed.sourceId(), seed.pageId(), seed.title(),
                        edge(edge.kind())));
                places.put(target, edge.target());
            }
            for (Map.Entry<PageKey, ConnectionKind> gap : result.missing().entrySet()) {
                PageKey target = gap.getKey();
                if (missing.size() >= MAX_CANDIDATES || !KINDS.contains(gap.getValue())
                        || chosen.containsKey(target) || found.containsKey(target)) {
                    continue;
                }
                if (missing.putIfAbsent(target, new HitReason.Neighbor(seed.sourceId(), seed.pageId(), seed.title(),
                        edge(gap.getValue()))) == null && gap.getValue() == ConnectionKind.PARENT) {
                    // Eine Elternseite liegt im Space ihrer Unterseite; ein Verweisziel kann überall liegen.
                    places.put(target, new PageRef(target, null, seed.spaceKey()));
                }
            }
        }
        missing.keySet().removeAll(found.keySet());
        return new Found(found, missing, places);
    }

    private static HitReason.Neighbor.Edge edge(ConnectionKind kind) {
        return switch (kind) {
            case PARENT -> HitReason.Neighbor.Edge.PARENT;
            case CHILD -> HitReason.Neighbor.Edge.CHILD;
            case LINKS_TO -> HitReason.Neighbor.Edge.LINKS_TO;
            case LINKED_FROM -> HitReason.Neighbor.Edge.LINKED_FROM;
            default -> throw new IllegalArgumentException("Keine Kante der Methode: " + kind);
        };
    }
}
