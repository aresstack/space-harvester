package com.aresstack.spaceharvester.deck.terminal;

import java.time.Duration;

/**
 * Port: die Tastatur eines Terminals für Abläufe ohne Benutzer (automatische Anmeldung). Wie {@link TerminalView}
 * holt man sie über {@link TerminalSession#view(Class)}.
 */
public interface TerminalKeyboard {

    /**
     * Wartet, bis der Host Eingaben annimmt (kein X-Wait), höchstens {@code timeout}. Gemeldet vom Terminal selbst,
     * nicht abgefragt.
     *
     * @return ob die Tastatur rechtzeitig frei wurde
     */
    boolean awaitUnlocked(Duration timeout) throws InterruptedException;

    /**
     * Zeitpunkt ({@link System#nanoTime()}) der letzten Taste, die der Mensch selbst am Terminal gedrückt hat, oder
     * {@link Long#MIN_VALUE}. Tippt er selbst, bricht die automatische Anmeldung ab, statt ihm dazwischenzutippen.
     */
    default long lastUserInput() {
        return Long.MIN_VALUE;
    }

    /** Drückt eine Taste, die eine Eingabe an den Host schickt (ENTER, CLEAR, PA, SysReq, PF). */
    void press(AidKey key);

    /** Taste CLEAR. */
    default void clear() {
        press(AidKey.CLEAR);
    }

    /** Taste ENTER (Daten senden). */
    default void enter() {
        press(AidKey.ENTER);
    }

    /** Setzt die Schreibmarke in das erste Eingabefeld; {@code false}, wenn das Bild keins hat. */
    boolean firstInputField();

    /** Setzt die Schreibmarke ins nächste Eingabefeld; {@code false}, wenn es kein weiteres gibt. */
    boolean nextInputField();

    /** Tippt die Zeichen an der Schreibmarke ein. */
    void type(char[] text);
}
