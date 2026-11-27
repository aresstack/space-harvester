package com.aresstack.knowledge4j.passage;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Resolves passages by id within the run's bound knowledge state; ids the caller may not see are absent. */
public interface PassageLookup {

    Optional<PassageView> find(String passageId);

    /**
     * Resolves many ids in one call; the default calls {@link #find} per id, a host with a store overrides it
     * with one query.
     *
     * @return found passages by id, in the order of {@code passageIds}; missing ids are absent
     */
    default Map<String, PassageView> findAll(Collection<String> passageIds) {
        Map<String, PassageView> found = new LinkedHashMap<>();
        if (passageIds != null) {
            for (String id : passageIds) {
                find(id).ifPresent(p -> found.put(id, p));
            }
        }
        return found;
    }
}
