package com.aresstack.knowledge4j.model.support;

/** What a backend's warm-up really does. A reachability check never counts as loading. */
public enum WarmUp {
    /** Loads the model with its context, so the first call does not pay the load. */
    LOAD,
    /** Only checks that the endpoint answers; the model may still load on the first call. */
    REACHABILITY_ONLY,
    /** No warm-up at all. */
    NONE
}
