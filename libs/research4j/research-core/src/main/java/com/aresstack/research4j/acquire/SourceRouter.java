package com.aresstack.research4j.acquire;

import com.aresstack.knowledge4j.access.ContentFetchPort;
import com.aresstack.knowledge4j.access.FetchedContent;
import com.aresstack.knowledge4j.access.SourceAccessException;
import com.aresstack.knowledge4j.policy.GuardedContentFetchPort;
import com.aresstack.knowledge4j.policy.RunGuard;
import com.aresstack.knowledge4j.run.CancellationSignal;
import com.aresstack.knowledge4j.source.SourceRef;

import java.util.ArrayList;
import java.util.List;

/**
 * The router (Verteiler): it picks the reader per source, the first channel that supports it, in the order the host
 * gave them (an internal document to the document service, a web address to the browser). Every channel sits behind
 * the run's transfer guard.
 */
public final class SourceRouter implements ContentFetchPort {

    private final List<ContentFetchPort> channels;

    public SourceRouter(List<FetchChannel> channels, RunGuard guard) {
        List<ContentFetchPort> guarded = new ArrayList<>();
        for (FetchChannel channel : channels == null ? List.<FetchChannel>of() : channels) {
            guarded.add(new GuardedContentFetchPort(channel.port(), channel.target(), guard));
        }
        this.channels = List.copyOf(guarded);
    }

    @Override
    public boolean supports(SourceRef ref) {
        return channels.stream().anyMatch(channel -> channel.supports(ref));
    }

    @Override
    public FetchedContent fetch(SourceRef ref, CancellationSignal cancel) throws SourceAccessException {
        for (ContentFetchPort channel : channels) {
            if (channel.supports(ref)) {
                return channel.fetch(ref, cancel);
            }
        }
        throw new SourceAccessException(SourceAccessException.Reason.NOT_FOUND,
                "no reader for sources of kind " + ref.kind());
    }
}
