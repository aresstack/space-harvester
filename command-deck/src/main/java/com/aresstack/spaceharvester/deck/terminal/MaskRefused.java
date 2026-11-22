package com.aresstack.spaceharvester.deck.terminal;

/** Der Maskenassistent hat eine Änderung abgelehnt; die Meldung ist lesbar und geht so an KI und Benutzer. */
public final class MaskRefused extends RuntimeException {

    public MaskRefused(String message) {
        super(message);
    }
}
