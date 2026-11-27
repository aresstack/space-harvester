package com.aresstack.spaceharvester.app.ui.controls;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Point;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Eine Fläche mit zwei schwebenden Plätzen am linken und rechten Rand, auf der Linie unter ihrer Kopfleiste (Angelo
 * 27.11.2026): Dorthin wandert der Umschalter einer zugeklappten Seitenleiste, unter das Logo links und unter den
 * Schließen-Knopf rechts, und öffnet die Leiste von dort wieder. Die Plätze liegen über dem übrigen Inhalt und nehmen
 * ihm keinen Raum; ein leerer Platz ist unsichtbar und fängt keine Maus.
 */
public final class EdgeOverlay extends JPanel {

    /** So weit ragt ein Platz unter die Linie; der größere Teil steht darüber, im freien Rand der Kopfleiste. */
    public static final int BELOW_LINE = 5;

    private static final String FLOATING = "floating";

    private final JPanel left = slot();
    private final JPanel right = slot();
    private JComponent bar;

    public EdgeOverlay() {
        super(new BorderLayout() {
            @Override
            public void addLayoutComponent(Component component, Object constraints) {
                if (!FLOATING.equals(constraints)) {
                    super.addLayoutComponent(component, constraints);
                }
            }
        });
        add(left, FLOATING, 0);
        add(right, FLOATING, 0);
    }

    private static JPanel slot() {
        JPanel slot = new JPanel(new BorderLayout()) {
            @Override
            public boolean contains(int x, int y) {
                return getComponentCount() > 0 && super.contains(x, y);
            }
        };
        slot.setOpaque(false);
        return slot;
    }

    /** Die Kopfleiste, unter deren Linie die Plätze stehen; ihr Innenrand gibt den Abstand zu den Seiten. */
    public void bar(JComponent bar) {
        this.bar = bar;
    }

    /** Der Platz am linken Rand. */
    public JComponent left() {
        return left;
    }

    /** Der Platz am rechten Rand. */
    public JComponent right() {
        return right;
    }

    /** Stellt {@code part} auf einen Platz ({@code null} leert ihn) und zeichnet neu. */
    public void place(JComponent slot, JComponent part) {
        if (part == null ? slot.getComponentCount() == 0 : part.getParent() == slot) {
            return;
        }
        slot.removeAll();
        if (part != null) {
            slot.add(part, BorderLayout.CENTER);
        }
        revalidate();
        repaint();
    }

    /** Die Plätze überlappen den Inhalt: Swing muss die Geschwister in ihrer Reihenfolge zeichnen. */
    @Override
    public boolean isOptimizedDrawingEnabled() {
        return left.getComponentCount() == 0 && right.getComponentCount() == 0;
    }

    @Override
    public void doLayout() {
        super.doLayout();
        if (bar == null || !SwingUtilities.isDescendingFrom(bar, this)) {
            return;
        }
        Point origin = SwingUtilities.convertPoint(bar.getParent(), bar.getLocation(), this);
        Insets insets = bar.getInsets();
        int line = origin.y + bar.getHeight();
        Dimension leftSize = left.getComponentCount() == 0 ? new Dimension() : left.getPreferredSize();
        left.setBounds(origin.x + insets.left, line + BELOW_LINE - leftSize.height, leftSize.width, leftSize.height);
        Dimension rightSize = right.getComponentCount() == 0 ? new Dimension() : right.getPreferredSize();
        right.setBounds(origin.x + bar.getWidth() - insets.right - rightSize.width,
                line + BELOW_LINE - rightSize.height, rightSize.width, rightSize.height);
    }
}
