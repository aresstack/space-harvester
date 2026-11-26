package com.aresstack.spaceharvester.deck.ndv.analysis;

import com.aresstack.spaceharvester.deck.ndv.NdvObject;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Abhängigkeitsgraphen je Bibliothek wie {@code NaturalAnalysisService} aus MainframeMate: aufgebaut aus den
 * zwischengespeicherten Quelltexten ({@link NdvSourceCache}), nach dem Speichern eines Quelltexts nachgeführt, mit
 * „Cache leeren“ verworfen. Liefert Aufrufe (aktive XRefs), Aufrufer (passive XRefs) und die Aufrufhierarchie in
 * beide Richtungen.
 */
public final class NdvAnalysis {

    /** Tiefe der Aufrufhierarchie wie in MainframeMate. */
    public static final int HIERARCHY_DEPTH = 5;

    private final NdvSourceCache cache;
    private final Map<String, NaturalDependencyGraph> graphs = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final NaturalDependencyService service = new NaturalDependencyService();

    public NdvAnalysis(NdvSourceCache cache) {
        this.cache = cache;
    }

    public NdvSourceCache cache() {
        return cache;
    }

    public Optional<NaturalDependencyGraph> graph(String library) {
        return Optional.ofNullable(graphs.get(upper(library))).filter(NaturalDependencyGraph::isBuilt);
    }

    /** Baut den Graphen einer Bibliothek aus dem Zwischenspeicher (blockiert kurz; aus dem Hintergrund aufrufen). */
    public NaturalDependencyGraph build(String library) {
        String lib = upper(library);
        NaturalDependencyGraph graph = new NaturalDependencyGraph();
        graph.setLibrary(lib);
        cache.sources(lib).forEach((name, source) -> graph.addSource(lib, name, source));
        graph.build();
        graphs.put(lib, graph);
        changed();
        return graph;
    }

    /** Nach dem Speichern: Quelltext ablegen, neu analysieren, Aufrufer neu berechnen (wie {@code onSourceSaved}). */
    public void saved(NdvObject object, String source) {
        cache.put(object, source);
        if (graphs.containsKey(upper(object.library()))) {
            build(object.library());
        }
    }

    /** Abhängigkeiten eines einzelnen Quelltexts, auch ohne Graph (die „Daten“ des geöffneten Objekts). */
    public NaturalDependencyService.DependencyResult analyze(String source, String name) {
        return service.analyze(source, name);
    }

    public void remove(String library) {
        graphs.remove(upper(library));
        changed();
    }

    /** „Cache leeren“ wie in MainframeMate: Quelltexte, Suche und Graph der Bibliothek. */
    public void clear(String library) {
        cache.invalidate(library);
        remove(library);
    }

    public void observe(Runnable listener) {
        listeners.add(listener);
    }

    public void unobserve(Runnable listener) {
        listeners.remove(listener);
    }

    private void changed() {
        listeners.forEach(Runnable::run);
    }

    /**
     * Ob ein Abhängigkeitstyp ein Aufruf ist (gehört in die Aufrufhierarchie) oder Daten (Data Areas, Views, DDMs,
     * Copycodes, Maps, DB-Zugriffe), wie {@code isNaturalCallKind}.
     */
    public static boolean callKind(NaturalDependencyService.DependencyKind kind) {
        return kind == NaturalDependencyService.DependencyKind.CALLNAT
                || kind == NaturalDependencyService.DependencyKind.FETCH
                || kind == NaturalDependencyService.DependencyKind.CALL
                || kind == NaturalDependencyService.DependencyKind.PERFORM;
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
