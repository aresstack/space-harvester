package com.aresstack.knowledge4j.policy;

import com.aresstack.knowledge4j.access.SourceAccessException;
import com.aresstack.knowledge4j.access.SourceHit;
import com.aresstack.knowledge4j.access.SourceQuery;
import com.aresstack.knowledge4j.access.SourceSearchPort;
import com.aresstack.knowledge4j.run.CancellationSignal;

import java.util.List;
import java.util.Objects;

/** A search port behind the run's transfer guard: the query is checked before it leaves, hits raise the mark. */
public final class GuardedSourceSearchPort implements SourceSearchPort {

    private final SourceSearchPort delegate;
    private final TransferTarget target;
    private final RunGuard guard;

    public GuardedSourceSearchPort(SourceSearchPort delegate, TransferTarget target, RunGuard guard) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.target = Objects.requireNonNull(target, "target");
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    @Override
    public List<SourceHit> search(SourceQuery query, CancellationSignal cancel) throws SourceAccessException {
        TransferDecision decision = guard.check(target, PayloadKind.SEARCH_QUERY, query.provenance(),
                query.text().length());
        if (!decision.allowed()) {
            throw Denials.toAccess(target, PayloadKind.SEARCH_QUERY, decision);
        }
        List<SourceHit> hits = delegate.search(query, cancel);
        for (SourceHit hit : hits) {
            guard.sawContent(hit.provenance());
        }
        return hits;
    }
}
