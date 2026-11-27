package com.aresstack.askai.research.runtime.browser;

/** No user context could be handed out: the browser capacity stayed exhausted, or no browser is available. */
public final class BrowserCapacityException extends Exception {

    private static final long serialVersionUID = 1L;

    public BrowserCapacityException(String message) {
        super(message);
    }

    public BrowserCapacityException(String message, Throwable cause) {
        super(message, cause);
    }
}
