package com.aresstack.spaceharvester.app.ui;

import javax.swing.JComponent;

/**
 * Ein Hauptbereich. Das Hauptfenster zeigt rechts {@link #component()}, links dauerhaft den {@link Explorer} und über
 * der Statusleiste die {@link #actions()} des Bereichs.
 */
public interface View {

    JComponent component();

    /** Der Bereich wird sichtbar; Gelegenheit, veraltete Daten neu zu laden. */
    default void shown() {
    }

    /** Aktionen und Fortschritt des Bereichs direkt über der Statusleiste; {@code null}, wenn er keine hat. */
    default JComponent actions() {
        return null;
    }
}
