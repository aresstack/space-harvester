package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.search.layout.SearchPageLayoutProfile;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutProfileMatch;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutProfileQuery;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutProfileStore;

import java.util.ArrayList;
import java.util.List;

/**
 * A thread-safe in-memory {@link SearchPageLayoutProfileStore} for tests and ephemeral runs. Later
 * validated saves supersede an equal profile; lookup returns the most recent compatible profile.
 */
public final class InMemorySearchPageLayoutProfileStore implements SearchPageLayoutProfileStore {

    private final List<SearchPageLayoutProfile> profiles = new ArrayList<SearchPageLayoutProfile>();

    public synchronized SearchPageLayoutProfileMatch find(SearchPageLayoutProfileQuery query) {
        for (int i = profiles.size() - 1; i >= 0; i--) {
            SearchPageLayoutProfile profile = profiles.get(i);
            if (query.matches(profile)) {
                return SearchPageLayoutProfileMatch.of(profile);
            }
        }
        return SearchPageLayoutProfileMatch.none("no compatible profile");
    }

    public synchronized void saveValidated(SearchPageLayoutProfile profile) {
        profiles.add(profile);
    }

    public synchronized int size() {
        return profiles.size();
    }
}
