package com.aresstack.spaceharvester.app.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;

/**
 * Kopfzeile des Hauptfensters: links Titel und Icon-Gruppe, rechts Seitenleisten und Verwaltung, dazwischen
 * rechtsbündig das Suchfeld (#515). Reicht der Platz dem Suchfeld nicht für seine Mindestbreite
 * ({@link SearchBarLayout#MIN_WIDTH}), gibt die linke Gruppe Breite ab, nie das Suchfeld: der Titel wird dort als
 * einziger Teil gekürzt („Space Har…“), die Knöpfe bleiben ganz. So hängt die Kopfzeile nicht an den Schriftmaßen des
 * Rechners.
 *
 * <p>Die Icon-Gruppe am rechten Ende der linken Gruppe kann einem Anker folgen ({@link #anchor}, Angelo 27.11.2026):
 * Die Reiter der Mitte stehen über dem mittleren Fenster. Dazu wächst die linke Gruppe in den freien Raum vor dem
 * Suchfeld, soweit dessen Mindestbreite es erlaubt.
 */
final class HeaderLayout extends BorderLayout {

    /** Wo die Icon-Gruppe enden soll (x ihrer rechten Kante in der Kopfzeile); negativ: direkt hinter dem Titel. */
    private java.util.function.IntSupplier anchor = () -> -1;

    HeaderLayout(int hgap) {
        super(hgap, 0);
    }

    void anchor(java.util.function.IntSupplier anchor) {
        this.anchor = java.util.Objects.requireNonNull(anchor, "anchor");
    }

    @Override
    public void layoutContainer(Container parent) {
        super.layoutContainer(parent);
        Component west = getLayoutComponent(WEST);
        Component center = getLayoutComponent(CENTER);
        if (west == null || center == null || !west.isVisible() || !center.isVisible()) {
            return;
        }
        int lack = center.getMinimumSize().width - center.getWidth();
        if (lack > 0) {
            int shrink = Math.min(lack, west.getWidth());
            west.setSize(west.getWidth() - shrink, west.getHeight());
            center.setBounds(center.getX() - shrink, center.getY(), center.getWidth() + shrink, center.getHeight());
            return;
        }
        int wanted = anchor.getAsInt();
        int grow = wanted < 0 ? 0 : Math.min(wanted - (west.getX() + west.getWidth()), -lack);
        if (grow > 0) {
            west.setSize(west.getWidth() + grow, west.getHeight());
            center.setBounds(center.getX() + grow, center.getY(), center.getWidth() - grow, center.getHeight());
        }
    }

    /**
     * Hält {@code child} links in seiner Wunschbreite, auch wenn die linke Gruppe für den Anker wächst: Der Titel samt
     * Dreieck bleibt beisammen. Wird es eng, bekommt er nur die vorhandene Breite und kürzt sich wie bisher.
     */
    static javax.swing.JPanel leading(javax.swing.JComponent child) {
        javax.swing.JPanel holder = new javax.swing.JPanel(new java.awt.LayoutManager() {
            @Override
            public void layoutContainer(Container parent) {
                child.setBounds(0, 0, Math.min(child.getPreferredSize().width, parent.getWidth()),
                        parent.getHeight());
            }

            @Override
            public java.awt.Dimension preferredLayoutSize(Container parent) {
                return child.getPreferredSize();
            }

            @Override
            public java.awt.Dimension minimumLayoutSize(Container parent) {
                return child.getMinimumSize();
            }

            @Override
            public void addLayoutComponent(String name, Component comp) {
            }

            @Override
            public void removeLayoutComponent(Component comp) {
            }
        });
        holder.setOpaque(false);
        holder.add(child);
        return holder;
    }
}
