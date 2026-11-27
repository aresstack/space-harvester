package com.aresstack.askai.browser.search.engine;

/**
 * How the enabled search engines are worked through. This is a USER decision about how much searching is
 * wanted — not something the browser code should settle on its own by whichever consumer happens to stop
 * the loop.
 */
public enum EngineAcquisitionMode {

    /**
     * Engines in the user's order until one delivers usable organic results; the rest are never visited.
     * A navigation failure, a challenge, a technical extraction failure or simply nothing usable moves on
     * to the next engine.
     */
    FIRST_USABLE,

    /**
     * Every enabled engine is visited and its results are kept with their provenance, so selection and
     * reranking see the union rather than whatever the first engine happened to know.
     */
    ALL_ENABLED
}
