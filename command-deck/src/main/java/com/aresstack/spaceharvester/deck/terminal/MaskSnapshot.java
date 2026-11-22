package com.aresstack.spaceharvester.deck.terminal;

import java.util.List;

/**
 * Die aktuelle Maske, wie der Maskenassistent sie an die KI gibt (#1151): sichtbare Zeilen, die Eingabefelder mit
 * Position, Länge, Attributen, Wert und vermuteter Beschriftung. Verdeckte und als sensibel erkannte Felder sind
 * geschwärzt – in den Zeilen wie in der Feldliste.
 *
 * @param sessionId      Kennung der Sitzung
 * @param revision       Kennung der Maske (Aufbau und geschützte Texte); ändert sich mit jedem neuen Bild vom Host
 * @param rows           Zeilen
 * @param columns        Spalten
 * @param cursorRow      Zeile der Schreibmarke, ab 1
 * @param cursorColumn   Spalte der Schreibmarke, ab 1
 * @param keyboardLocked ob der Host gerade keine Eingabe annimmt (dann ist Ausfüllen gesperrt)
 * @param lines          die Zeilen des Bildes, geschwärzt
 * @param fields         die Eingabefelder (ungeschützt), nummeriert ab 1
 */
public record MaskSnapshot(String sessionId, String revision, int rows, int columns, int cursorRow, int cursorColumn,
                           boolean keyboardLocked, List<String> lines, List<InputField> fields) {

    public MaskSnapshot {
        lines = List.copyOf(lines);
        fields = List.copyOf(fields);
    }

    /** Text für eine geschwärzte Stelle. */
    public static final String MASKED = "(verdeckt)";

    /**
     * Ein Eingabefeld.
     *
     * @param number     Nummer, ab 1, in Pufferreihenfolge
     * @param row        Zeile, ab 1
     * @param column     Spalte des ersten Zeichens, ab 1
     * @param length     Länge
     * @param numeric    nur Ziffern
     * @param sensitive  verdeckt oder als Kennwort erkannt: Wert geschwärzt, für die KI nicht beschreibbar
     * @param modified   seit dem letzten Bild geändert (von Mensch oder KI)
     * @param byAssistant zuletzt von der KI gesetzt
     * @param value      aktueller Wert ohne Füllzeichen am Ende, bei sensiblen Feldern {@link #MASKED}
     * @param label      vermutete Beschriftung aus dem geschützten Text links davor (oder darüber); leer, wenn keine
     */
    public record InputField(int number, int row, int column, int length, boolean numeric, boolean sensitive,
                             boolean modified, boolean byAssistant, String value, String label) {
    }
}
