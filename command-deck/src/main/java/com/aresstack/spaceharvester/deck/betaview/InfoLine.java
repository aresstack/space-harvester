package com.aresstack.spaceharvester.deck.betaview;

/** Eine Angabe zu einem Dokument: Bezeichnung und Wert, wie der Server sie liefert. */
public record InfoLine(String label, String value) {

    public InfoLine {
        label = label == null ? "" : label.trim();
        value = value == null ? "" : value.trim();
    }
}
