package com.aresstack.spaceharvester.application.sources;

/** Der Rumpf eines Webhook-Aufrufs ist keine lesbare Meldung der Quelle; die Meldung enthält keine Inhalte daraus. */
public class UnreadableChangeEventException extends RuntimeException {

    public UnreadableChangeEventException(String message) {
        super(message);
    }
}
