package com.aresstack.research4j.acquire;

import com.aresstack.knowledge4j.access.ContentFetchPort;
import com.aresstack.knowledge4j.policy.TransferTarget;

import java.util.Objects;

/**
 * One way to read sources: the host's document service for internal documents, the browser for web pages. The
 * research wraps it behind the run's transfer guard.
 *
 * @param target the service the request goes to, with its exposure
 * @param port   the reader
 */
public record FetchChannel(TransferTarget target, ContentFetchPort port) {
    public FetchChannel {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(port, "port");
    }
}
