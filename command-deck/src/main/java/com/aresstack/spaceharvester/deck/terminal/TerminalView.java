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

    /**
     * Meldet jedes neue Bild vom Host (und jede eigene Änderung an der Anzeige). Der Aufruf kommt vom Terminal selbst,
     * nicht aus einer Abfrage, und kann auf einem beliebigen Faden stattfinden.
     */
    void addScreenListener(Runnable listener);

    void removeScreenListener(Runnable listener);

    /**
     * Hebt Bereiche des Bildes hervor, etwa die Felder, die der KI-Maskenassistent ausgefüllt hat (#1151), damit der
     * Mensch sie vor dem Absenden prüft. Eine leere Liste nimmt die Hervorhebung weg.
     */
    default void highlight(java.util.List<Span> spans) {
    }

    /**
     * Markiert Fundstellen der Lupe im Bild: alle hell, {@code current} (Index in {@code found} oder -1) kräftig. Eine
     * leere Liste nimmt die Markierung weg.
     */
    default void markFound(java.util.List<Span> found, int current) {
    }

    /**
     * Meldet, was der Mensch an der Anzeige selbst eingibt (Tastatur, Einfügen, Klick auf einen Menüpunkt), für die
     * Makro-Aufzeichnung. Eingaben in verdeckte Felder (Passwörter) meldet die Anzeige nie. Abläufe ohne Menschen
     * (Anmeldung, Abspielen) laufen über {@link TerminalKeyboard} und werden nicht gemeldet.
     */
    default void addInputListener(InputListener listener) {
    }

    default void removeInputListener(InputListener listener) {
    }

    /** Empfänger der Eingaben des Menschen. */
    interface InputListener {

        /** Getippter oder eingefügter Text in ein sichtbares Feld. */
        void typed(String text);

        /** Eine AID-Taste, die an den Host ging. */
        void aid(AidKey key);

        /** Tab: die Schreibmarke ging ins nächste Eingabefeld. */
        default void tab() {
        }
    }

    /** Ein Bereich ab Pufferposition {@code start}, {@code length} Zellen lang. */
    record Span(int start, int length) {
    }
}
