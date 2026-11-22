package com.aresstack.spaceharvester.deck.terminal;

import java.util.List;
import java.util.Objects;

/**
 * Port: die Maske eines Terminals, wie der KI-Maskenassistent sie braucht (#1151) – lesen, ein ungeschütztes Feld im
 * Puffer des Emulators lokal beschreiben, die Schreibmarke lokal setzen. Mehr nicht: Dieser Port hat bewusst
 * <b>keine</b> Taste, die etwas an den Host schickt (ENTER, PF, PA, CLEAR, SysReq – siehe {@link TerminalKeyboard},
 * das der Maskenassistent nie bekommt), keinen Tastensender und keine Makros. Lokale Änderungen lösen nie selbst eine
 * Übertragung aus; abgeschickt wird nur durch eine bewusste Taste des Menschen.
 *
 * <p>Wie {@link TerminalView} holt man den Port über {@link TerminalSession#view(Class)}; der Anwendungsfall
 * {@link MaskAssistant} ist sein einziger Nutzer.
 */
public interface TerminalMask {

    /** Das aktuelle Bild samt Feldern. Auf jedem Faden aufrufbar. */
    ScreenImage capture();

    /**
     * Schreibt {@code value} lokal in das ungeschützte Feld ab {@code start} und löscht den Rest des Feldes (wie
     * „Erase EOF“). Die Schreibmarke steht danach wieder, wo sie war. Schickt nichts an den Host.
     *
     * @throws MaskRefused wenn das Feld geschützt ist oder die Tastatur gesperrt (X-Wait)
     */
    void write(int start, int length, String value);

    /** Setzt die Schreibmarke lokal; schickt nichts an den Host. */
    void moveCursor(int position);

    /**
     * Ein Bild des Terminals, roh, ohne Schwärzung: alle Zellen und die Felder mit ihren Attributen.
     *
     * @param rows           Zeilen
     * @param columns        Spalten
     * @param cursor         Pufferposition der Schreibmarke
     * @param keyboardLocked ob der Host gerade keine Eingabe annimmt
     * @param cells          Zeichen je Zelle, Zeile für Zeile; Attributzellen und Nullen als Leerzeichen
     * @param fields         alle Felder in Pufferreihenfolge; leer bei unformatiertem Bild
     */
    record ScreenImage(int rows, int columns, int cursor, boolean keyboardLocked, char[] cells, List<Field> fields) {

        public ScreenImage {
            Objects.requireNonNull(cells, "cells");
            fields = List.copyOf(fields);
            if (cells.length != rows * columns) {
                throw new IllegalArgumentException("Zellen passen nicht zu " + rows + " × " + columns);
            }
        }

        /** Der Inhalt eines Feldes (über das Zeilenende hinweg, mit Umlauf am Pufferende). */
        public String text(Field field) {
            StringBuilder b = new StringBuilder(field.length());
            for (int i = 0; i < field.length(); i++) {
                b.append(cells[(field.start() + i) % cells.length]);
            }
            return b.toString();
        }
    }

    /**
     * Ein Feld des Bildes.
     *
     * @param start      Pufferposition des ersten Datenzeichens (hinter dem Attributbyte)
     * @param length     Zahl der Datenzeichen
     * @param protectedField geschützt (nur Anzeige)
     * @param numeric    nur Ziffern
     * @param hidden     nicht anzeigen (Kennwortfelder)
     * @param modified   vom Benutzer geändert (MDT gesetzt)
     */
    record Field(int start, int length, boolean protectedField, boolean numeric, boolean hidden, boolean modified) {
    }
}
