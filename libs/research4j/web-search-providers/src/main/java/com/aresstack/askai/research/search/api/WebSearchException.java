package com.aresstack.askai.research.search.api;

public final class WebSearchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WebSearchException(String message) {
        super(message);
    }

    public WebSearchException(
            String message,
            Throwable cause) {

        super(message, cause);
    }
}
