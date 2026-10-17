package com.aresstack.spaceharvester.app.ui;

import javax.swing.JComponent;

/** Ein Bereich der Hauptnavigation. */
public interface View {

    JComponent component();

    /** Der Bereich wird sichtbar; Gelegenheit, veraltete Daten neu zu laden. */
    default void shown() {
    }
}
