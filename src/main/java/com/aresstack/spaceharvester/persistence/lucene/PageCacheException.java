package com.aresstack.spaceharvester.persistence.lucene;

/**
 * Der Cache ist nicht nutzbar: Index nicht lesbar oder beschädigt, falsches Schema, von einem anderen Prozess
 * gesperrt, oder eine gespeicherte Payload passt nicht mehr zu ihrem Hash. Die Meldung nennt nie Seiteninhalte.
 */
public class PageCacheException extends RuntimeException {

    public PageCacheException(String message) {
        super(message);
    }

    public PageCacheException(String message, Throwable cause) {
        super(message, cause);
    }
}
