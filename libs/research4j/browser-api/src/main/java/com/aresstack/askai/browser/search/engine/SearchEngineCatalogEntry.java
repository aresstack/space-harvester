package com.aresstack.askai.browser.search.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One engine of the {@link SearchEngineCatalog}: what the engine IS (stable id, name, origin, start page, where its
 * index comes from, its starting languages) and how far AskAI can search with it. It is deliberately NOT a
 * configuration: how a user searches with an engine (active, priority, delay, languages) is a row of Search →
 * Engines ({@link BrowserSearchEngineSelection.Entry}) that points at an entry by its id, and the same engine may have
 * several rows.
 */
public final class SearchEngineCatalogEntry {

    private final String id;
    private final String displayName;
    private final String origin;
    private final String homeUrl;
    private final String indexSource;
    private final List<String> defaultLanguages;
    private final SearchEngineImplementationStatus status;
    private final String statusNote;
    private final boolean languageFilter;
    private final int defaultDelayMillis;

    /**
     * @param id               stable technical id, persisted in the settings (lower case, digits, {@code -})
     * @param displayName      the name the user knows
     * @param origin           the country the provider comes from (English)
     * @param homeUrl          the engine's public start page
     * @param indexSource      where its results come from ("own index", "mostly Bing", …)
     * @param defaultLanguages starting languages of a new configuration row; a default, not a verified filter
     * @param status           how far AskAI can search with it
     * @param statusNote       why it is not (yet) usable, what it needs, or "" when nothing is to be said
     * @param languageFilter   true only when AskAI sets a real language filter at the engine; false means the search
     *                         language is carried by the wording of the query alone
     */
    public SearchEngineCatalogEntry(String id, String displayName, String origin, String homeUrl, String indexSource,
                                    List<String> defaultLanguages, SearchEngineImplementationStatus status,
                                    String statusNote, boolean languageFilter, int defaultDelayMillis) {
        if (id == null || !id.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("catalog id must be lower case letters, digits or '-': " + id);
        }
        if (displayName == null || displayName.trim().isEmpty()) {
            throw new IllegalArgumentException("catalog entry " + id + " has no name");
        }
        if (status == null) {
            throw new IllegalArgumentException("catalog entry " + id + " has no implementation status");
        }
        SearchLanguages.Parsed languages = SearchLanguages.parse(defaultLanguages);
        if (!languages.getRejected().isEmpty() || languages.getCodes().isEmpty()) {
            throw new IllegalArgumentException("catalog entry " + id + " needs valid default languages: "
                    + defaultLanguages);
        }
        this.id = id;
        this.displayName = displayName.trim();
        this.origin = origin == null ? "" : origin.trim();
        this.homeUrl = homeUrl == null ? "" : homeUrl.trim();
        this.indexSource = indexSource == null ? "" : indexSource.trim();
        this.defaultLanguages = Collections.unmodifiableList(new ArrayList<String>(languages.getCodes()));
        this.status = status;
        this.statusNote = statusNote == null ? "" : statusNote.trim();
        this.languageFilter = languageFilter;
        this.defaultDelayMillis = Math.max(0, defaultDelayMillis);
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getOrigin() {
        return origin;
    }

    public String getHomeUrl() {
        return homeUrl;
    }

    public String getIndexSource() {
        return indexSource;
    }

    /** Normalised, never empty: the languages a new configuration row of this engine starts with. */
    public List<String> getDefaultLanguages() {
        return defaultLanguages;
    }

    public SearchEngineImplementationStatus getStatus() {
        return status;
    }

    public String getStatusNote() {
        return statusNote;
    }

    /** Whether AskAI sets a real language filter at this engine (else: language only through the query words). */
    public boolean hasLanguageFilter() {
        return languageFilter;
    }

    /**
     * The pause before each further request to this engine that a new row starts with, in milliseconds — the
     * "Delay (s)" a row gets when its configuration names none (a new row, or a row written before delays existed).
     * 0 for engines that take requests as they come; Google names one because unpaced request bursts end in a
     * challenge that blocks the search browser.
     */
    public int getDefaultDelayMillis() {
        return defaultDelayMillis;
    }

    /** Whether AskAI can search with this engine now ({@link SearchEngineImplementationStatus#isSearchable()}). */
    public boolean isSearchable() {
        return status.isSearchable();
    }

    @Override
    public String toString() {
        return displayName + " [" + id + ", " + status + "]";
    }
}
