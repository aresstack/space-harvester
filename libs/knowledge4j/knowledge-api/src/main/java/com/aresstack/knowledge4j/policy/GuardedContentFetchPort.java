package com.aresstack.knowledge4j.policy;

import com.aresstack.knowledge4j.access.ContentFetchPort;
import com.aresstack.knowledge4j.access.FetchedContent;
import com.aresstack.knowledge4j.access.SourceAccessException;
import com.aresstack.knowledge4j.run.CancellationSignal;
import com.aresstack.knowledge4j.source.SourceRef;

import java.util.Objects;

/**
 * A fetcher behind the run's transfer guard. Requesting a source reveals its id to the target, so the request is
 * judged with the run's mark; the fetched content raises the mark.
 */
public final class GuardedContentFetchPort implements ContentFetchPort {

    private final ContentFetchPort delegate;
    private final TransferTarget target;
    private final RunGuard guard;

    public GuardedContentFetchPort(ContentFetchPort delegate, TransferTarget target, RunGuard guard) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.target = Objects.requireNonNull(target, "target");
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    @Override
    public boolean supports(SourceRef ref) {
        return delegate.supports(ref);
    }

    @Override
    public FetchedContent fetch(SourceRef ref, CancellationSignal cancel) throws SourceAccessException {
        TransferDecision decision = guard.check(target, PayloadKind.CONTENT_FETCH, null, ref.id().length());
        if (!decision.allowed()) {
            throw Denials.toAccess(target, PayloadKind.CONTENT_FETCH, decision);
        }
        FetchedContent content = delegate.fetch(ref, cancel);
        guard.sawContent(content.provenance());
        return content;
    }
}
