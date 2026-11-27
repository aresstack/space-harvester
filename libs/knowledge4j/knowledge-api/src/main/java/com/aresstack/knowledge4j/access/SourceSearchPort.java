package com.aresstack.knowledge4j.access;

import com.aresstack.knowledge4j.run.CancellationSignal;

import java.util.List;

/**
 * Finds sources for a query: a web search (Serp4j, a search browser) or the host's own knowledge base. The host
 * binds each instance to a scope, the user's rights and a fixed knowledge state for one run.
 */
public interface SourceSearchPort {

    /** @return hits best first, at most {@code query.maxHits()}; empty when nothing matches */
    List<SourceHit> search(SourceQuery query, CancellationSignal cancel) throws SourceAccessException;
}
