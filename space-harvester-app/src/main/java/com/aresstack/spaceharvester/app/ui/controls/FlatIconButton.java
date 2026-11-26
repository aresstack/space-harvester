package com.aresstack.spaceharvester.app.ui.controls;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Insets;

/**
 * Der flache kleine Symbolknopf aus Angelos Proxy-Programm ({@code ProxyControlFrame.createSmallIconButton}), 1:1
 * übernommen (Angelo, 26.11.2026): kein Rahmen, keine Fläche, kein Fokus, keine Abstände, fest {@value #SIZE} ×
 * {@value #SIZE}. Nur das Symbol ist zu sehen, auch beim Darüberfahren. Statt des Unicode-Zeichens dort trägt er ein
 * gezeichnetes Symbol, weil die Schrift der App solche Zeichen auf Windows als Kästchen zeigt.
 *
 * <p>{@link #style} gibt denselben Stil einem vorhandenen Knopf, damit eine Reihe einheitlich aussieht.
 */
public final class FlatIconButton extends JButton {

    /** Kantenlänge wie im Proxy-Programm. */
    public static final int SIZE = 20;

    public FlatIconButton(Icon icon, String tooltip) {
        super(icon);
        setToolTipText(tooltip);
        getAccessibleContext().setAccessibleName(tooltip);
        style(this);
    }

    /** Der flache Stil für einen beliebigen Knopf: wie {@code createSmallIconButton}, Zeile für Zeile. */
    public static void style(JButton button) {
        button.setFocusable(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setRolloverEnabled(false);
        button.setOpaque(false);
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        Dimension size = new Dimension(SIZE, SIZE);
        button.setPreferredSize(size);
        button.setMinimumSize(size);
        button.setMaximumSize(size);
        Cursors.set(button, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(SIZE, SIZE);
    }
}
