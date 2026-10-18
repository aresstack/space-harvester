package com.aresstack.enterpriseai.model.api;

/** The reranker is not available or answered unreadably; the message never contains secrets or document text. */
public class RerankException extends Exception {

    private static final long serialVersionUID = 1L;

    public RerankException(String message) {
        super(message);
    }

    public RerankException(String message, Throwable cause) {
        super(message, cause);
    }
}
