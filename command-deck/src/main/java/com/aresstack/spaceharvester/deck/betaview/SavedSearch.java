package com.aresstack.spaceharvester.deck.betaview;

/**
 * Eine auf dem Server gespeicherte Suche. Token und Kennung stammen aus dem Formular der Liste und werden beim
 * Ausführen mitgeschickt (wie {@code SidebarPanel.SavedItem} in MainframeMate).
 *
 * @param name        Name
 * @param description Beschreibung
 * @param action      Aktion, etwa {@code executeSavedSelection.action?ssID=…}
 * @param tokenName   Name des Struts-Tokens, leer ohne
 * @param tokenValue  Wert des Struts-Tokens, leer ohne
 * @param id          {@code ssIDForm}, leer ohne
 */
public record SavedSearch(String name, String description, String action, String tokenName, String tokenValue,
                          String id) {

    public SavedSearch {
        name = clean(name);
        description = clean(description);
        action = clean(action);
        tokenName = clean(tokenName);
        tokenValue = clean(tokenValue);
        id = clean(id);
    }

    @Override
    public String toString() {
        return description.isEmpty() ? name : name + " – " + description;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
