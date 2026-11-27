package com.aresstack.askai.browser;

/** A controlled browser failure: mapped to a readable MCP tool error, never thrown at the model. */
public class BrowserException extends Exception {

    public BrowserException(String message) {
        super(message);
    }

    public BrowserException(String message, Throwable cause) {
        super(message, cause);
    }
}
