package com.aresstack.spaceharvester.application.standing;

/**
 * Die Seite ist im Bestand gesperrt (ADR 0014, {@code HoldingState.LOCKED}): Ihre Analyse bleibt lesbar (etwa mit
 * „Veraltete zeigen“), ihre Aussagen lassen sich aber nicht prüfen (ADR 0032, W3).
 */
public class LockedPageException extends RuntimeException {

    public LockedPageException() {
        super("Seite ist gesperrt");
    }
}
