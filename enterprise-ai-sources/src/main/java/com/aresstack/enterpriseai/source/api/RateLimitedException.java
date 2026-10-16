package com.aresstack.enterpriseai.source.api;

/**
 * The source keeps rejecting requests because of its rate limit (HTTP 429), also after the configured retries. Kind
 * {@link Kind#UNAVAILABLE}: a later attempt may succeed, but callers should slow down rather than retry at once.
 */
public class RateLimitedException extends KnowledgeSourceException {

    private static final long serialVersionUID = 1L;

    public RateLimitedException(String message) {
        super(Kind.UNAVAILABLE, message);
    }
}
