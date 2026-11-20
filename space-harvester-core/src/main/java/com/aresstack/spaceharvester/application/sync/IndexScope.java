package com.aresstack.spaceharvester.application.sync;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Ob eine Seite unter einer markierten Wurzel liegt ({@link IndexMarks}): die Seite selbst oder ein Vorfahr ist
 * markiert, oder der ganze Space. Die Vorfahren kommen aus {@code parentOf}, etwa aus der Inventur; eine Kette, die
 * abreißt oder sich im Kreis dreht, liegt außerhalb. Nicht threadsicher, für einen Lauf gedacht.
 */
final class IndexScope {

    private IndexScope() {
    }

    static Predicate<String> of(Set<String> roots, Function<String, String> parentOf) {
        Objects.requireNonNull(parentOf, "parentOf");
        if (roots.isEmpty()) {
            return pageId -> false;
        }
        if (roots.contains(IndexMarks.WHOLE_SPACE)) {
            return pageId -> true;
        }
        Set<String> marked = Set.copyOf(roots);
        Map<String, Boolean> known = new HashMap<>();
        return pageId -> covered(pageId, marked, parentOf, known);
    }

    private static boolean covered(String pageId, Set<String> roots, Function<String, String> parentOf,
                                   Map<String, Boolean> known) {
        List<String> path = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean covered = false;
        for (String id = pageId; id != null && seen.add(id); id = parentOf.apply(id)) {
            Boolean decided = known.get(id);
            if (decided != null) {
                covered = decided;
                break;
            }
            path.add(id);
            if (roots.contains(id)) {
                covered = true;
                break;
            }
        }
        for (String id : path) {
            known.put(id, covered);
        }
        return covered;
    }
}
