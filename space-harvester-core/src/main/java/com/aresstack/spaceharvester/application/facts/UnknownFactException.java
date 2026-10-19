package com.aresstack.spaceharvester.application.facts;

/** Die Seite hat keinen Fakt mit dieser Kennung, oder er ist nicht lesbar; die API antwortet mit 404. */
public class UnknownFactException extends RuntimeException {

    public UnknownFactException() {
        super("Unbekannter Fakt");
    }
}
