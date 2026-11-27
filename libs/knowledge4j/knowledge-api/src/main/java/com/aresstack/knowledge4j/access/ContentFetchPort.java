package com.aresstack.knowledge4j.access;

import com.aresstack.knowledge4j.run.CancellationSignal;
import com.aresstack.knowledge4j.source.SourceRef;

/**
 * Reads the content of a source: the browser for web pages, the host's document service for internal documents.
 * A router may pick the fetcher per {@link SourceRef#kind()}.
 */
public interface ContentFetchPort {

    /** Whether this port can read the source at all. */
    boolean supports(SourceRef ref);

    FetchedContent fetch(SourceRef ref, CancellationSignal cancel) throws SourceAccessException;
}
