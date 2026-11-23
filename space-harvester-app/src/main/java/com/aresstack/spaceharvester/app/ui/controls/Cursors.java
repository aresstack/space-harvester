package com.aresstack.spaceharvester.app.ui.controls;

import java.awt.Component;
import java.awt.Cursor;
import java.util.Objects;

/**
 * Der eine Weg, einer Fläche der App einen Mauszeiger zu geben. {@link Component#setCursor} lässt AWT bei jedem
 * Aufruf den Zeiger unter der Maus sofort neu bestimmen, auch wenn sich nichts ändert, und merkt sich den Zeitpunkt:
 * Mausbewegungen, die davor entstanden und noch in der Warteschlange stehen, bestimmen den Zeiger danach nicht mehr.
 * Setzt eine Anzeige ihren Zeiger bei jeder Meldung neu (etwa die Statuszeile, solange Aufträge laufen), bleibt der
 * Zeiger deshalb über anderen Flächen hängen, zum Beispiel ohne Doppelpfeil über den Trennern. Hier wird nur gesetzt,
 * was sich wirklich ändert.
 */
public final class Cursors {

    private Cursors() {
    }

    /** Gibt {@code component} den Zeiger {@code cursor}; {@code null} nimmt den eigenen weg (es gilt der der Umgebung). */
    public static void set(Component component, Cursor cursor) {
        Cursor current = component.isCursorSet() ? component.getCursor() : null;
        if (!Objects.equals(current, cursor)) {
            component.setCursor(cursor);
        }
    }
}
