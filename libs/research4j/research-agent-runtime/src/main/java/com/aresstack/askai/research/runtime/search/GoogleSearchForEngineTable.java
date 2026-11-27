package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.search.engine.BrowserSearchEngineCatalog;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection;

/**
 * Google in AskAI's search browser for the engine table as it is saved NOW (Search → Engines). Every search asks
 * {@link #forSelection} with the table it has just read. While no active row runs in the search browser there is no
 * Google search. Otherwise the strategy is built for the Google row's result pages and request delay, on first use
 * and again whenever either of them changed. So switching Google on or off, or changing its pages or delay, reaches
 * a running research session at its next search, without a restart.
 *
 * <p>Thread-safe: the session's searches and its workers may ask at the same time.</p>
 */
public final class GoogleSearchForEngineTable {

    /** Builds the Google search for one result-page count and request delay. */
    public interface Factory {
        SearchStrategy create(int resultPages, long delayMillis);
    }

    private final Factory factory;
    private SearchStrategy strategy;
    private int resultPages;
    private long delayMillis;

    public GoogleSearchForEngineTable(Factory factory) {
        if (factory == null) {
            throw new IllegalArgumentException("factory is required");
        }
        this.factory = factory;
    }

    /**
     * The Google search for {@code selection}: {@code null} while no active row of it runs in the search browser,
     * else the strategy for the Google row's result pages and delay (built now if they changed since the last one).
     */
    public synchronized SearchStrategy forSelection(BrowserSearchEngineSelection selection) {
        if (!enabled(selection)) {
            return null;
        }
        int pages = selection.resultPagesFor(BrowserSearchEngineCatalog.GOOGLE);
        long delay = selection.delayMillisFor(BrowserSearchEngineCatalog.GOOGLE);
        if (strategy == null || pages != resultPages || delay != delayMillis) {
            strategy = factory.create(pages, delay);
            resultPages = pages;
            delayMillis = delay;
        }
        return strategy;
    }

    /** Whether an active row of {@code selection} runs in the search browser (Google). */
    public static boolean enabled(BrowserSearchEngineSelection selection) {
        if (selection == null) {
            return false;
        }
        for (BrowserSearchEngineSelection.Entry entry : selection.getEntries()) {
            if (entry.isEnabled() && BrowserSearchEngineCatalog.runsInSearchBrowser(entry.getEngineId())) {
                return true;
            }
        }
        return false;
    }
}
