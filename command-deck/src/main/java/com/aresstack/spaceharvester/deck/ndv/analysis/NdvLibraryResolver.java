package com.aresstack.spaceharvester.deck.ndv.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Welche Bibliothek ein Name ohne Bibliothek meint, wie {@code resolveNdvLibraryForSymbol} und die
 * STEPLIB-Zuordnung aus MainframeMate: erst die eigene Bibliothek, dann die Standard-Bibliothek, dann die
 * Suchreihenfolge; gewinnt die erste, in deren Zwischenspeicher das Objekt liegt, sonst die erste der Reihe.
 *
 * @param searchOrder Bibliotheks-Suchreihenfolge aus den Einstellungen
 * @param stepLibs    STEPLIB (JCL) → NDV-Bibliothek
 */
public record NdvLibraryResolver(List<String> searchOrder, Map<String, String> stepLibs) {

    public NdvLibraryResolver {
        searchOrder = searchOrder == null ? List.of()
                : searchOrder.stream().map(NdvLibraryResolver::upper).filter(s -> !s.isEmpty()).distinct().toList();
        Map<String, String> clean = new LinkedHashMap<>();
        if (stepLibs != null) {
            stepLibs.forEach((step, lib) -> {
                if (!upper(step).isEmpty() && !upper(lib).isEmpty()) {
                    clean.put(upper(step), upper(lib));
                }
            });
        }
        stepLibs = java.util.Collections.unmodifiableMap(clean);
    }

    /** Die Reihenfolge, in der gesucht wird: eigene, Standard-, dann konfigurierte Bibliotheken, ohne Doppelte. */
    public List<String> candidates(String currentLibrary, String defaultLibrary) {
        List<String> order = new ArrayList<>();
        for (String lib : List.of(upper(currentLibrary), upper(defaultLibrary))) {
            if (!lib.isEmpty() && !order.contains(lib)) {
                order.add(lib);
            }
        }
        searchOrder.stream().filter(lib -> !order.contains(lib)).forEach(order::add);
        return order;
    }

    /** Die Bibliothek für ein Objekt; leer, wenn es gar keine Bibliothek zum Suchen gibt. */
    public Optional<String> resolve(String objectName, String currentLibrary, String defaultLibrary,
                                    NdvSourceCache cache) {
        List<String> order = candidates(currentLibrary, defaultLibrary);
        for (String lib : order) {
            if (cache != null && cache.source(lib, objectName).isPresent()) {
                return Optional.of(lib);
            }
        }
        return order.stream().findFirst();
    }

    /** NDV-Bibliothek zu einer STEPLIB aus der JCL, falls zugeordnet. */
    public Optional<String> forStepLib(String stepLib) {
        return Optional.ofNullable(stepLibs.get(upper(stepLib)));
    }

    /** Vorschlag wie in MainframeMate: der letzte Buchstabe nach dem Bindestrich wird T (ABAK-M → ABAK-T). */
    public static String suggest(String stepLib) {
        String value = upper(stepLib);
        if (value.length() > 2 && value.charAt(value.length() - 2) == '-') {
            return value.substring(0, value.length() - 1) + "T";
        }
        return value;
    }

    /** Neue Zuordnung (wird von der App gespeichert). */
    public NdvLibraryResolver withStepLib(String stepLib, String library) {
        Map<String, String> next = new LinkedHashMap<>(stepLibs);
        next.put(upper(stepLib), upper(library));
        return new NdvLibraryResolver(searchOrder, next);
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
