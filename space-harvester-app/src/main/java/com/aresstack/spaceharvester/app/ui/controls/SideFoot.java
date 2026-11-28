package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * Die Fußleiste jeder Seitenleiste, 1:1 die des Explorers im Space Harvester (Angelo 24.11.2026): links die Aktionen
 * der Leiste (etwa „Alles zuklappen“, „Aktuelles Dokument zeigen“), rechts ein Element in der Ecke (etwa der Stern), in
 * der Mitte ein Hinweis oder die Reiter der Leiste. Immer {@link Comic#FOOT_HEIGHT} hoch, damit sie auf einer Linie mit
 * der Leiste mit der Lupe unter der Arbeitsfläche steht; jedes Element sitzt senkrecht mittig. Die Lupe gehört nicht
 * hierher, sondern unter die Arbeitsfläche. Nur auf dem EDT.
 */
public final class SideFoot extends FootBar {

    private static final int GAP = 6;

    private final JComponent west;
    private final JComponent center;
    private final JComponent east;

    /**
     * @param west   Aktionen links oder {@code null}
     * @param center Hinweis oder Reiter in der Mitte (volle Restbreite, eigene Höhe) oder {@code null}
     * @param east   Element rechts in der Ecke oder {@code null}
     */
    public SideFoot(JComponent west, JComponent center, JComponent east) {
        super(null);
        this.west = west;
        this.center = center;
        this.east = east;
        setOpaque(false);
        setLayout(new Row());
        for (JComponent part : new JComponent[] {west, center, east}) {
            if (part != null) {
                add(part);
            }
        }
    }

    /** Eine Reihe Symbolknöpfe für links, wie im Explorer: dicht nebeneinander. */
    public static JPanel buttons(Component... parts) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        row.setOpaque(false);
        for (Component part : parts) {
            row.add(part);
        }
        return row;
    }

    /** Reiter oder Knöpfe mittig in der Leiste. */
    public static JPanel centered(Component... parts) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
        row.setOpaque(false);
        for (Component part : parts) {
            row.add(part);
        }
        return row;
    }

    private static boolean shown(JComponent part) {
        return part != null && part.isVisible();
    }

    /** Feste Höhe; links und rechts in Wunschbreite, die Mitte bekommt den Rest. */
    private final class Row implements LayoutManager {

        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Insets in = parent.getInsets();
            int width = in.left + in.right;
            for (JComponent part : new JComponent[] {west, center, east}) {
                if (shown(part)) {
                    width += part.getPreferredSize().width + GAP;
                }
            }
            // Die Höhe legt allein die Basisklasse fest (FootBar), nicht dieses Layout.
            return new Dimension(width, 0);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            Insets in = parent.getInsets();
            return new Dimension(in.left + in.right, 0);
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets in = parent.getInsets();
            int left = in.left;
            int right = parent.getWidth() - in.right;
            int top = in.top;
            int room = Math.max(0, parent.getHeight() - in.top - in.bottom);
            if (shown(west)) {
                Dimension size = west.getPreferredSize();
                int width = Math.min(size.width, Math.max(0, right - left));
                int own = Math.min(room, size.height);
                west.setBounds(left, top + (room - own) / 2, width, own);
                left += width + GAP;
            }
            if (shown(east)) {
                Dimension size = east.getPreferredSize();
                int width = Math.min(size.width, Math.max(0, right - left));
                int own = Math.min(room, size.height);
                east.setBounds(right - width, top + (room - own) / 2, width, own);
                right -= width + GAP;
            }
            if (shown(center)) {
                int own = Math.min(room, center.getPreferredSize().height);
                center.setBounds(left, top + (room - own) / 2, Math.max(0, right - left), own);
            }
        }
    }
}
