package com.aresstack.spaceharvester.application.sources;

/** Signal that a navigation continuation cannot be retried and must be restarted. */
public final class InvalidNavigationCursorException extends InvalidQueryException {
    public InvalidNavigationCursorException() {
        super("Navigationscursor ungültig; Ordner neu laden.");
    }
}