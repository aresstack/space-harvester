package com.aresstack.enterpriseai.browser.api;

/** A browser operation failed; {@link #kind()} tells the runtime whether recovery makes sense. */
public class BrowserException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Failure classes; only {@link #CONNECTION_LOST} and {@link #BROWSER_CLOSED} trigger recovery. */
    public enum Kind {
        /** The automation connection broke while the browser may still run: reconnect first. */
        CONNECTION_LOST,
        /** The browser process is gone (closed by the user or crashed): start it again with the same profile. */
        BROWSER_CLOSED,
        /** The browser could not be started or did not become ready in time. */
        START_FAILED,
        /** An operation did not finish in time. */
        TIMEOUT,
        /** The request itself is invalid (unknown tab, forbidden url); retrying does not help. */
        INVALID_REQUEST,
        /** Anything else reported by the browser. */
        FAILED,
        /** The browser access policy (or the user) refused the address; retrying does not help. */
        BLOCKED
    }

    private final Kind kind;

    public BrowserException(Kind kind, String message) {
        super(message);
        this.kind = kind == null ? Kind.FAILED : kind;
    }

    public BrowserException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind == null ? Kind.FAILED : kind;
    }

    public Kind kind() {
        return kind;
    }

    /** Does this failure mean the browser or its connection is gone, so a reconnect or restart may help? */
    public boolean isRecoverable() {
        return kind == Kind.CONNECTION_LOST || kind == Kind.BROWSER_CLOSED;
    }
}
