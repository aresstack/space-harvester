package com.aresstack.spaceharvester.application.query;

/** Der Seitencache ist nicht eingerichtet oder gerade nicht lesbar. */
public class CacheUnavailableException extends RuntimeException {

    public CacheUnavailableException(String message) {
        super(message);
    }

    public CacheUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
