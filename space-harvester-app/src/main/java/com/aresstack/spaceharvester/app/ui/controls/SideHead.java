package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.text.JTextComponent;

/**
 * Der Kopf jeder Seitenleiste, 1:1 der des Explorers im Space Harvester (Angelo 24.11.2026): oben die kleine
 * Großbuchstaben-Überschrift („WISSENSQUELLEN“) mit den Symbolknöpfen der Leiste rechtsbündig, darunter das Filterfeld
 * mit Lupe und ✕ ({@link InputFrame#search}). Keine eigenen Suchfelder je Leiste. Nur auf dem EDT.
 */
public final class SideHead extends JPanel {

    private final JLabel heading;
    /** Die Kopfleiste der Seitenleiste: dieselbe wie über Mitte und rechter Leiste ({@link HeadBar}). */
    private final JPanel title = new HeadBar(new BorderLayout(4, 0), 4, 2);
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));

    /**
     * @param caption Überschrift; wird in Großbuchstaben gezeigt
     * @param filter  das Filterfeld der Leiste oder {@code null} für keins
     */
    public SideHead(String caption, JTextComponent filter) {
        super(new BorderLayout(0, 6));
        setOpaque(false);
        // Oben ohne Abstand: Die Kopfleiste beginnt an der Oberkante wie die der anderen Bereiche.
        setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        heading = Comic.caption(caption);
        title.add(heading, BorderLayout.CENTER);
        actions.setOpaque(false);
        title.add(actions, BorderLayout.EAST);
        add(title, BorderLayout.NORTH);
        if (filter != null) {
            JPanel field = new JPanel(new BorderLayout());
            field.setOpaque(false);
            field.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 2));
            field.add(InputFrame.search(filter), BorderLayout.CENTER);
            add(field, BorderLayout.CENTER);
        }
    }

    /** Symbolknöpfe rechts neben der Überschrift, in dieser Reihenfolge. */
    public SideHead actions(Component... parts) {
        for (Component part : parts) {
            actions.add(part);
        }
        return this;
    }

    /** Die Überschrift (etwa für einen Klick, der alles wählt). */
    public JLabel heading() {
        return heading;
    }

    /** Die Zeile mit Überschrift und Knöpfen. */
    public JPanel title() {
        return title;
    }
}
