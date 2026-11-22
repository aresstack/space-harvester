package com.aresstack.spaceharvester.deck.betaview;

/** Name und Beschreibung, etwa der Vorschlag des Servers für ein Lesezeichen. */
public record NameAndDescription(String name, String description) {

    public NameAndDescription {
        name = name == null ? "" : name.trim();
        description = description == null ? "" : description.trim();
    }
}
