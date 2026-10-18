package com.aresstack.spaceharvester.app.ui;

import javax.swing.JComponent;

/**
 * Ein Hauptbereich. Das Hauptfenster zeigt rechts {@link #component()}, links die kontextabhängige
 * {@link #sidebar()} und über der Statusleiste die {@link #actions()} des Bereichs.
 */
public interface View {

    JComponent component();

    /** Der Bereich wird sichtbar; Gelegenheit, veraltete Daten neu zu laden. */
    default void shown() {
    }

    /** Navigation und Filter des Bereichs in der linken Seitenleiste; {@code null} ohne Seitenleiste. */
    default JComponent sidebar() {
        return null;
    }

    /** Aktionen und Fortschritt des Bereichs direkt über der Statusleiste; {@code null}, wenn er keine hat. */
    default JComponent actions() {
        return null;
    }
}
