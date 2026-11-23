package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.deck.ndv.NdvBookmark;
import com.aresstack.spaceharvester.deck.ndv.NdvNameFilter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Was der NDV-Bereich sich merkt, wie {@code saveNavigatorState}/{@code restoreNavigatorState} in MainframeMate
 * ({@code ndv.nav.*}): Gruppieren nach Art, Sortierung, Details, ausgeblendete Arten, aufgeklappte Gruppen, „Mit Editor
 * verknüpfen“, der Namensfilter je Bibliothek (je Server und Benutzer) und die Lesezeichen je Server.
 */
final class NdvNavigatorState {

    static final String PREFIX = "deck.ndv.nav.";
    /** Gültigkeitsbereich des Namensfilters für die Bibliotheksliste selbst (wie {@code __LIBRARIES__}). */
    static final String SCOPE_LIBRARIES = "__LIBRARIES__";

    private final AppState state = AppState.shared();

    boolean groupByType() {
        return state.getBoolean(PREFIX + "groupByType", false);
    }

    void groupByType(boolean value) {
        state.put(PREFIX + "groupByType", value);
    }

    boolean showDetails() {
        return state.getBoolean(PREFIX + "showDetails", false);
    }

    void showDetails(boolean value) {
        state.put(PREFIX + "showDetails", value);
    }

    boolean linkWithEditor() {
        return state.getBoolean(PREFIX + "linkWithEditor", false);
    }

    void linkWithEditor(boolean value) {
        state.put(PREFIX + "linkWithEditor", value);
    }

    /** Sortierung als {@code Spalte:ASCENDING}, leer = Name aufsteigend. */
    String sort() {
        String value = state.get(PREFIX + "sort");
        return value == null ? "" : value;
    }

    void sort(String value) {
        state.put(PREFIX + "sort", value == null ? "" : value);
    }

    Set<Integer> hiddenTypes() {
        return ints(state.get(PREFIX + "hiddenTypes"));
    }

    void hiddenTypes(Set<Integer> types) {
        state.put(PREFIX + "hiddenTypes", join(types));
    }

    /** Aufgeklappte Gruppen; {@code null} heißt alle (Vorgabe). */
    Set<Integer> expandedTypes() {
        String value = state.get(PREFIX + "expandedTypeIds");
        return value == null || value.equals("*") ? null : ints(value);
    }

    void expandedTypes(Set<Integer> types) {
        state.put(PREFIX + "expandedTypeIds", types == null ? "*" : join(types));
    }

    /** Lädt den Namensfilter für einen Bereich (Bibliothek oder {@link #SCOPE_LIBRARIES}), ohne zu melden. */
    void restoreFilter(NdvNameFilter filter, String host, String user, String scope) {
        filter.restore(state.get(filterKey("regexFilter", host, user, scope)),
                NdvNameFilter.split(state.get(filterKey("regexPrefixes", host, user, scope))));
    }

    void saveFilter(NdvNameFilter filter, String host, String user, String scope) {
        state.put(filterKey("regexFilter", host, user, scope), filter.prefixes().isEmpty() ? filter.regex() : "");
        state.put(filterKey("regexPrefixes", host, user, scope), NdvNameFilter.join(filter.prefixes()));
    }

    List<NdvBookmark> bookmarks(String host) {
        List<NdvBookmark> result = new ArrayList<>();
        String value = state.get(PREFIX + "bookmarks." + sanitize(host));
        if (value != null) {
            for (String line : value.split("\n")) {
                NdvBookmark.parse(line).filter(b -> !result.contains(b)).ifPresent(result::add);
            }
        }
        return result;
    }

    void bookmarks(String host, List<NdvBookmark> bookmarks) {
        state.put(PREFIX + "bookmarks." + sanitize(host),
                String.join("\n", bookmarks.stream().map(NdvBookmark::path).toList()));
    }

    private static String filterKey(String kind, String host, String user, String scope) {
        return PREFIX + kind + "." + sanitize(host) + "." + sanitize(user) + "." + sanitize(scope);
    }

    static String sanitize(String value) {
        return value == null || value.isEmpty() ? "_" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static Set<Integer> ints(String csv) {
        Set<Integer> result = new LinkedHashSet<>();
        if (csv != null) {
            for (String part : csv.split(",")) {
                try {
                    if (!part.isBlank()) {
                        result.add(Integer.parseInt(part.trim()));
                    }
                } catch (NumberFormatException ignored) {
                    // Unbrauchbarer Eintrag: überspringen wie MainframeMate.
                }
            }
        }
        return result;
    }

    private static String join(Set<Integer> values) {
        return String.join(",", values.stream().map(String::valueOf).toList());
    }
}
