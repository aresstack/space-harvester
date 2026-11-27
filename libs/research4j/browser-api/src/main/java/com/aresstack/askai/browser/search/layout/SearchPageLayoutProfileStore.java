package com.aresstack.askai.browser.search.layout;

/**
 * Persistence port for validated layout profiles. Implementations must store STRUCTURE only (never
 * snapshot-local container ids) and must write atomically. An in-memory implementation serves tests;
 * a file-backed implementation serves the productive research context.
 */
public interface SearchPageLayoutProfileStore {

    SearchPageLayoutProfileMatch find(SearchPageLayoutProfileQuery query);

    void saveValidated(SearchPageLayoutProfile profile);
}
