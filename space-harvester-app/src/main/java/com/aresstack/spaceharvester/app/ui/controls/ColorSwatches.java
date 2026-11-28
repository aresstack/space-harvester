package com.aresstack.spaceharvester.app.ui.controls;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import javax.accessibility.AccessibleContext;
import javax.swing.JComponent;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Die Farbwahl einer farbigen Gruppe (UX-Strang, 27.11.2026): eine Zeile runder Farbfelder (18 px, Abstand 6), das
 * gewählte mit Ring und Haken. Tooltip und zugänglicher Name sind der Farbname; mit Pfeiltasten bedienbar.
 */
public final class ColorSwatches extends JComponent {

    static final int SWATCH = 18;
    static final int GAP = 6;
    private static final int RING = 3;

    private final GroupColors[] colors = GroupColors.values();
    private final java.util.function.Function<GroupColors, String> names;
    private GroupColors selected;
    private Consumer<GroupColors> onChange = color -> { };

    /** @param names der Farbname je Farbe, für Tooltip und Zugänglichkeit */
    public ColorSwatches(GroupColors selected, java.util.function.Function<GroupColors, String> names) {
        this.selected = selected == null ? colors[0] : selected;
        this.names = names;
        setFocusable(true);
        setOpaque(false);
        setToolTipText("");
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                GroupColors hit = at(e.getX());
                if (hit != null) {
                    select(hit);
                }
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int index = ordinal();
                if (e.getKeyCode() == KeyEvent.VK_LEFT || e.getKeyCode() == KeyEvent.VK_UP) {
                    select(colors[(index + colors.length - 1) % colors.length]);
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_RIGHT || e.getKeyCode() == KeyEvent.VK_DOWN) {
                    select(colors[(index + 1) % colors.length]);
                    e.consume();
                }
            }
        });
        getAccessibleContext().setAccessibleName(names.apply(this.selected));
    }

    /** Ein schlichtes JComponent hat keinen zugänglichen Kontext; die Farbwahl braucht ihn für ihren Namen. */
    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleJComponent() {
                @Override
                public javax.accessibility.AccessibleRole getAccessibleRole() {
                    return javax.accessibility.AccessibleRole.RADIO_BUTTON;
                }
            };
        }
        return accessibleContext;
    }

    public GroupColors selected() {
        return selected;
    }

    public void onChange(Consumer<GroupColors> listener) {
        onChange = listener == null ? color -> { } : listener;
    }

    /** Wählt eine Farbe, wie ein Klick. */
    public void select(GroupColors color) {
        if (color == null || color == selected) {
            return;
        }
        GroupColors before = selected;
        selected = color;
        AccessibleContext accessible = getAccessibleContext();
        accessible.setAccessibleName(names.apply(color));
        firePropertyChange("selected", before, color);
        repaint();
        onChange.accept(color);
    }

    private int ordinal() {
        return selected.ordinal();
    }

    private GroupColors at(int x) {
        int index = (x - RING) / (SWATCH + GAP);
        int within = (x - RING) - index * (SWATCH + GAP);
        return index >= 0 && index < colors.length && within <= SWATCH ? colors[index] : null;
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        GroupColors hit = at(event.getX());
        return hit == null ? null : names.apply(hit);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(colors.length * SWATCH + (colors.length - 1) * GAP + 2 * RING, SWATCH + 2 * RING);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color background = getParent() == null ? null : getParent().getBackground();
            for (int i = 0; i < colors.length; i++) {
                int x = RING + i * (SWATCH + GAP);
                int y = RING;
                Color fill = colors[i].on(background);
                g.setColor(fill);
                g.fillOval(x, y, SWATCH, SWATCH);
                if (colors[i] == selected) {
                    g.setStroke(new BasicStroke(2f));
                    g.drawOval(x - RING, y - RING, SWATCH + 2 * RING - 1, SWATCH + 2 * RING - 1);
                    g.setColor(AppColors.of("app.ColorSwatches.paintComponent"));
                    g.drawPolyline(new int[] {x + 5, x + 8, x + 13}, new int[] {y + 9, y + 12, y + 6}, 3);
                }
                if (colors[i] == selected && isFocusOwner()) {
                    g.setColor(fill.darker());
                    g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f,
                            new float[] {2f, 2f}, 0f));
                    g.drawRect(x - RING, y - RING, SWATCH + 2 * RING - 1, SWATCH + 2 * RING - 1);
                }
            }
        } finally {
            g.dispose();
        }
    }
}
