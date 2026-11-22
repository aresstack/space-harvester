package com.aresstack.spaceharvester.deck.terminal;

/**
 * Bedienung der Terminalanzeige, unabhängig von der Emulationsbibliothek. Die Oberfläche holt sie sich über
 * {@link TerminalSession#view(Class)}; die Anzeige selbst ist eine Swing-Komponente desselben Objekts.
 */
public interface TerminalView {

    /** Wählt die größte Schrift, mit der das ganze Bild in die Fläche passt. */
    void fit(int width, int height);

    /** Kopiert die Auswahl, ohne Auswahl das ganze Bild, in die Zwischenablage. */
    void copy();

    /** Tippt den Text der Zwischenablage an der Schreibmarke ein (Zeilenumbrüche werden Leerzeichen). */
    void paste();

    /** Das ganze Bild als Text, Zeile für Zeile. */
    String text();

    /** Holt den Tastaturfokus in die Anzeige. */
    void focus();

    /** Ob der Host gerade keine Eingabe annimmt (X-Wait). */
    boolean keyboardLocked();
}
