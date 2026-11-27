package com.aresstack.askai.research.domain;

/**
 * The lifecycle of every domain object under the NO-DELETE policy: nothing is ever physically removed by
 * the workflow — objects move through these states instead, so every decision stays traceable. A physical
 * purge would be a separate maintenance function, never part of research operations.
 */
public enum Lifecycle {
    PROPOSED,
    ACCEPTED,
    REJECTED,
    EXCLUDED,
    SUPERSEDED,
    STALE,
    TOMBSTONED
}
