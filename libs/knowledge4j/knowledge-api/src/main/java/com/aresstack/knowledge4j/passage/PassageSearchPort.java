package com.aresstack.knowledge4j.passage;

import com.aresstack.knowledge4j.run.CancellationSignal;

import java.util.List;

/**
 * Searches the passages of the host's knowledge state. The host binds each instance to one run's scope, the
 * user's rights and a fixed knowledge state, so the libraries never pass a scope or a user around and never read
 * the whole corpus.
 */
public interface PassageSearchPort {

    /** @return hits best first, at most {@code query.maxHits()} */
    List<PassageHit> search(PassageQuery query, CancellationSignal cancel);
}
