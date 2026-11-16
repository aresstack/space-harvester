package com.aresstack.spaceharvester.application.changelog;

/** Kein Update mit dieser Kennung in der Quelle. */
public final class UnknownUpdateException extends RuntimeException {

    public UnknownUpdateException(String sourceId) {
        super("Kein solches Update in " + sourceId);
    }
}
