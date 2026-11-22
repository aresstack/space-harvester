package com.aresstack.spaceharvester.deck.betaview;

/**
 * Ein gemerktes Dokument (Lesezeichen des Servers).
 *
 * @param name        Name
 * @param description Beschreibung
 * @param action      Aktion zum Öffnen, etwa {@code opendocumentlink.action?docid=…&favid=…}
 * @param id          Kennung des Lesezeichens, -1 ohne
 */
public record Bookmark(String name, String description, String action, int id) {

    public Bookmark {
        name = name == null ? "" : name.trim();
        description = description == null ? "" : description.trim();
        action = action == null ? "" : action.trim();
    }

    @Override
    public String toString() {
        return description.isEmpty() ? name : name + " – " + description;
    }
}
