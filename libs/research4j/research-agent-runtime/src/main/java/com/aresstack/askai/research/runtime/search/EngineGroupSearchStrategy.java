package com.aresstack.askai.research.runtime.search;

import java.util.List;

/**
 * The research browser's engine search when it can be narrowed to ONE priority group of Search → Engines: the
 * returned strategy searches exactly the named engines, all of them, and merges what they found. The web search
 * tool ({@link BrowserEnginesSearchStrategy}) uses it to run the groups one after another.
 */
public interface EngineGroupSearchStrategy extends SearchStrategy {

    /** The same search, restricted to these engine ids (in this order, each once). */
    SearchStrategy forEngines(List<String> engineIds);
}
