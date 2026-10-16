package com.aresstack.enterpriseai.source.api;

/**
 * A response of the source is larger than the configured limit. Kind {@link Kind#INVALID_RESPONSE}: retrying does not
 * help, and the response is never delivered truncated.
 */
public class ResponseTooLargeException extends KnowledgeSourceException {

    private static final long serialVersionUID = 1L;

    private final long maxBytes;

    public ResponseTooLargeException(String message, long maxBytes) {
        super(Kind.INVALID_RESPONSE, message);
        this.maxBytes = maxBytes;
    }

    /** The limit that was exceeded, in bytes. */
    public long maxBytes() {
        return maxBytes;
    }
}
