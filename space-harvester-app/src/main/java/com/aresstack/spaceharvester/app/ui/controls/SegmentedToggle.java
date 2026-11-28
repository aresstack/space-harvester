package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Ein Umschalter wie eine Wippe (Angelo 22.11.2026): mehrere Hälften in einem gemeinsamen Rahmen, genau eine ist
 * gewählt und gefüllt. Dezent und niedrig, für eine Werkzeugzeile; alle Hälften sind gleich breit, damit die Wippe
 * beim Umschalten nicht springt. Eine Hälfte kann eine Zahl als kleinen Chip hinter dem Namen tragen.
 */
public final class SegmentedToggle extends JPanel {

    private static final int SEGMENT_HEIGHT = 24;
    private static final int PAD = 12;
    private static final int CHIP_GAP = 6;
    private static final int CHIP_HEIGHT = 16;

    private final Map<String, Segment> segments = new LinkedHashMap<>();
    private final ButtonGroup group = new ButtonGroup();
    private final List<Consumer<String>> listeners = new ArrayList<>();
    private String selected;

    public SegmentedToggle() {
        super(new GridLayout(1, 0, 0, 0));
        setOpaque(false);
        RailLook.follow(this);
    }

    /** Fügt eine Hälfte an; die erste ist gewählt. */
    public SegmentedToggle segment(String name) {
        return segment(name, null);
    }

    /**
     * Fügt eine Hälfte an, die statt ihres Namens nur ein Symbol zeigt (Angelo 27.11.2026: Umschalter einheitlich als
     * Symbole); der Name steht im Tooltip und für Bildschirmleser. Gewählt wird das Symbol in der Schriftfarbe der
     * gefüllten Hälfte gezeichnet, gleich welche Farbe es selbst mitbringt.
     */
    public SegmentedToggle segment(String name, javax.swing.Icon icon) {
        put(name, icon, segments.size());
        if (selected == null) {
            selected = name;
            segments.get(name).setSelected(true);
        }
        return this;
    }

    /**
     * Fügt eine Hälfte mit Symbol an Platz {@code index} ein (Angelo 28.11.2026: Symbole lassen sich aus einer
     * Knopfleiste herausziehen und wieder hineinschieben, {@link PearlString}). Anders als beim Anfügen wird sie nicht
     * von selbst gewählt.
     */
    public SegmentedToggle segment(String name, javax.swing.Icon icon, int index) {
        put(name, icon, index);
        return this;
    }

    private void put(String name, javax.swing.Icon icon, int index) {
        Segment segment = new Segment(name);
        segment.icon = icon;
        if (icon != null) {
            segment.setIcon(icon);
            segment.setToolTipText(name);
        }
        int at = Math.max(0, Math.min(segments.size(), index));
        // Die Reihenfolge der Hälften bestimmt, welche Enden des gemeinsamen Rahmens rund sind.
        Map<String, Segment> ordered = new LinkedHashMap<>();
        int position = 0;
        for (Map.Entry<String, Segment> entry : segments.entrySet()) {
            if (position++ == at) {
                ordered.put(name, segment);
            }
            ordered.put(entry.getKey(), entry.getValue());
        }
        ordered.putIfAbsent(name, segment);
        segments.clear();
        segments.putAll(ordered);
        group.add(segment);
        add(segment, at);
        segment.addActionListener(event -> clicked(name));
        segment.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent event) {
                hovered(true);
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent event) {
                hovered(false);
            }
        });
        revalidate();
        repaint();
    }

    /** Nimmt eine Hälfte heraus; war sie gewählt, ist danach keine gewählt. Meldet nichts. */
    public void remove(String name) {
        Segment segment = segments.remove(name);
        if (segment == null) {
            return;
        }
        group.remove(segment);
        remove(segment);
        if (name.equals(selected)) {
            selected = null;
            group.clearSelection();
        }
        hovered = false;
        revalidate();
        repaint();
    }

    /** Die Namen der Hälften in ihrer Reihenfolge. */
    public List<String> names() {
        return List.copyOf(segments.keySet());
    }

    /**
     * Ein Klick des Benutzers. Der zweite Klick auf die gewählte Hälfte geht an {@link #onAgain}, wenn jemand ihn haben
     * will (etwa: die Seitenleiste klappt zu); sonst bleibt die Hälfte gewählt wie bisher.
     */
    private void clicked(String name) {
        if (again != null && name.equals(selected)) {
            again.accept(name);
            return;
        }
        select(name);
    }

    /** Der zweite Klick auf die gewählte Hälfte; ohne Empfänger geschieht nichts. */
    public void onAgain(Consumer<String> again) {
        this.again = again;
    }

    private Consumer<String> again;

    /**
     * Stellt die Hälften untereinander statt nebeneinander (Angelo 27.11.2026): So schmal steht der Umschalter einer
     * Seitenleiste als Knopfleiste auf ihrem Trenner oder, zugeklappt, am Fensterrand. Farben nach {@link RailLook};
     * die gewählte Hälfte und die unter der Maus sind hervorgehoben. Nur für Hälften mit Symbol gedacht; eine Zahl
     * zeigt sich dann als Punkt an der Ecke des Symbols.
     */
    public void vertical(boolean vertical) {
        if (this.vertical == vertical) {
            return;
        }
        this.vertical = vertical;
        hovered = false;
        setLayout(vertical ? new GridLayout(0, 1, 0, 0) : new GridLayout(1, 0, 0, 0));
        revalidate();
        repaint();
    }

    public boolean vertical() {
        return vertical;
    }

    private boolean vertical;
    /**
     * Deckkraft des senkrechten Umschalters, solange die Maus nicht über ihm steht (Angelo 27.11.2026): Am Fensterrand
     * wirkt er wie Glas über dem Inhalt; unter der Maus ist er voll deckend.
     */
    public static final float GLASS_OPACITY = 0.8f;
    private boolean hovered;

    /** Ob der Umschalter gerade durchscheinend gezeichnet wird (für Tests). */
    public boolean glassy() {
        return vertical && !hovered;
    }

    private void hovered(boolean hovered) {
        if (this.hovered != hovered) {
            this.hovered = hovered;
            repaint();
        }
    }

    @Override
    public void paint(Graphics graphics) {
        if (!glassy()) {
            super.paint(graphics);
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setComposite(java.awt.AlphaComposite.SrcOver.derive(GLASS_OPACITY));
            super.paint(g);
        } finally {
            g.dispose();
        }
    }
    /** Rand um das Symbol einer senkrechten Hälfte: Sie ist so breit, wie eine waagerechte hoch ist. */
    private static final int VERTICAL_PAD = 3;
    /** Um so viel ist eine senkrechte Hälfte höher als breit, solange sie nicht allein steht. */
    private static final int SEGMENT_STRETCH = 4;
    private static final int DOT = 6;

    /** Hebt die Wahl auf, ohne etwas zu melden: Keine Hälfte ist gefüllt (etwa solange die Leiste zugeklappt ist). */
    public void clear() {
        selected = null;
        group.clearSelection();
        repaint();
    }

    /** Zeigt eine Hälfte als gewählt, ohne etwas zu melden (der Aufrufer hat den Zustand schon hergestellt). */
    public void show(String name) {
        Segment segment = segments.get(name);
        if (segment != null) {
            selected = name;
            segment.setSelected(true);
            repaint();
        }
    }

    /** Tauscht das Symbol einer Hälfte (etwa „ungelesen“). */
    public void icon(String name, javax.swing.Icon icon) {
        Segment segment = segments.get(name);
        if (segment != null && segment.icon != icon) {
            segment.icon = icon;
            segment.setIcon(icon);
            revalidate();
            repaint();
        }
    }

    /** Wählt eine Hälfte; meldet nur einen echten Wechsel. */
    public void select(String name) {
        Segment segment = segments.get(name);
        if (segment == null) {
            return;
        }
        segment.setSelected(true);
        if (!name.equals(selected)) {
            selected = name;
            repaint();
            for (Consumer<String> listener : List.copyOf(listeners)) {
                listener.accept(name);
            }
        }
    }

    public String selected() {
        return selected;
    }

    public void onSelect(Consumer<String> listener) {
        listeners.add(listener);
    }

    public JToggleButton button(String name) {
        return segments.get(name);
    }

    /**
     * Eine Zahl an einer Hälfte: ein kleiner Chip in {@code tone} hinter dem Namen; bei 0 kein Chip.
     *
     * @param tip Tooltip, solange die Zahl über 0 liegt; sonst gilt {@code idleTip}
     */
    public void count(String name, int count, Color tone, String tip, String idleTip) {
        Segment segment = segments.get(name);
        if (segment == null) {
            return;
        }
        segment.count = Math.max(0, count);
        segment.tone = tone;
        segment.setToolTipText(count > 0 ? tip : idleTip);
        revalidate();
        repaint();
    }

    /** Die Zahl an einer Hälfte (für Tests); 0 ohne Chip. */
    public int count(String name) {
        Segment segment = segments.get(name);
        return segment == null ? 0 : segment.count;
    }

    /** Eine Hälfte: zeichnet ihren Teil des gemeinsamen Rahmens, gewählt gefüllt. */
    private final class Segment extends JToggleButton {
        private int count;
        private Color tone = Comic.PETROL;
        /** Das Symbol an Stelle des Namens, oder {@code null}. */
        private javax.swing.Icon icon;

        /** Breite dessen, was die Hälfte zeigt: Symbol oder Name. */
        private int labelWidth(FontMetrics metrics) {
            return icon != null ? icon.getIconWidth() : metrics.stringWidth(getText());
        }

        /** Zeichnet Symbol oder Name; ein Symbol auf der gefüllten Hälfte ganz in {@code color}. */
        private void paintLabel(Graphics2D g, int x, int height, Color color, boolean on, FontMetrics metrics) {
            if (icon == null) {
                g.setColor(color);
                g.drawString(getText(), x, (height - metrics.getHeight()) / 2 + metrics.getAscent());
                return;
            }
            int y = (height - icon.getIconHeight()) / 2;
            setForeground(color);
            if (!on) {
                icon.paintIcon(this, g, x, y);
                return;
            }
            // Doppelte Auflösung, damit das eingefärbte Symbol auf dichten Bildschirmen scharf bleibt.
            int scale = 2;
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(icon.getIconWidth() * scale,
                    icon.getIconHeight() * scale, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D ig = image.createGraphics();
            try {
                ig.scale(scale, scale);
                icon.paintIcon(this, ig, 0, 0);
                ig.setComposite(java.awt.AlphaComposite.SrcIn);
                ig.setColor(color);
                ig.fillRect(0, 0, icon.getIconWidth(), icon.getIconHeight());
            } finally {
                ig.dispose();
            }
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, x, y, icon.getIconWidth(), icon.getIconHeight(), null);
        }

        Segment(String name) {
            super(name);
            setFont(Comic.semiBold(12f));
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setRolloverEnabled(true);
            getAccessibleContext().setAccessibleName(name);
        }

        private Font chipFont() {
            return getFont().deriveFont(Font.BOLD, Math.max(10f, getFont().getSize2D() - 1f));
        }

        private int chipWidth() {
            return count <= 0 ? 0 : Math.max(CHIP_HEIGHT,
                    getFontMetrics(chipFont()).stringWidth(String.valueOf(count)) + 10);
        }

        @Override
        public Dimension getPreferredSize() {
            if (vertical) {
                int label = labelWidth(getFontMetrics(getFont()));
                // Eine einzelne Hälfte ist eine Kugel: so hoch wie breit.
                return new Dimension(label + 2 * VERTICAL_PAD,
                        label + 2 * VERTICAL_PAD + (segments.size() == 1 ? 0 : SEGMENT_STRETCH));
            }
            int chip = chipWidth();
            return new Dimension(2 * PAD + labelWidth(getFontMetrics(getFont()))
                    + (chip > 0 ? CHIP_GAP + chip : 0), SEGMENT_HEIGHT);
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
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int width = getWidth();
                int height = getHeight();
                int index = new ArrayList<>(segments.values()).indexOf(this);
                boolean first = index == 0;
                boolean last = index == segments.size() - 1;
                int arc = (vertical ? width : height) - 1;
                // Der gemeinsame Rahmen: nur die äußeren Enden sind rund, dazwischen gerade Kanten.
                int before = first ? 0 : arc;
                int after = last ? 0 : arc;
                int x = vertical ? 0 : -before;
                int y = vertical ? -before : 0;
                int w = vertical ? width - 1 : width - 1 + before + after;
                int h = vertical ? height - 1 + before + after : height - 1;
                boolean on = isSelected();
                if (vertical) {
                    // Die Knopfleiste in der gewählten Fassung (Angelo 28.11.2026); hervorgehoben sind die gewählte
                    // Hälfte und die unter der Maus.
                    RailLook look = RailLook.current();
                    boolean chosen = on || getModel().isRollover() && isEnabled();
                    g.setColor(chosen ? look.chosenGround() : look.ground());
                    g.fillRoundRect(x, y, w, h, arc, arc);
                    int inset = look.edgeWidth() - 1;
                    g.setColor(look.edge());
                    g.setStroke(new java.awt.BasicStroke(look.edgeWidth()));
                    g.drawRoundRect(x + inset, y + inset, w - inset, h - inset, arc, arc);
                    g.setStroke(new java.awt.BasicStroke(1f));
                    FontMetrics metrics = g.getFontMetrics(getFont());
                    g.setFont(getFont());
                    paintLabel(g, (width - labelWidth(metrics)) / 2, height,
                            chosen ? look.chosenMark() : look.mark(), true, metrics);
                    if (count > 0) {
                        g.setColor(tone);
                        g.fillOval(width - DOT - 3, 3, DOT, DOT);
                    }
                    return;
                }
                if (on) {
                    g.setColor(Comic.INK);
                    g.fillRoundRect(x, y, w, h, arc, arc);
                } else if (getModel().isRollover() && isEnabled()) {
                    g.setColor(new Color(Comic.INK.getRed(), Comic.INK.getGreen(), Comic.INK.getBlue(), 20));
                    g.fillRoundRect(x, y, w, h, arc, arc);
                }
                g.setColor(Comic.INK);
                g.drawRoundRect(x, y, w, h, arc, arc);
                if (isFocusOwner()) {
                    g.setColor(on ? Color.WHITE : Comic.INK);
                    g.setStroke(new java.awt.BasicStroke(1f, java.awt.BasicStroke.CAP_BUTT,
                            java.awt.BasicStroke.JOIN_MITER, 1f, new float[] {2f, 2f}, 0f));
                    g.drawRoundRect(4, 3, width - 9, height - 7, height - 7, height - 7);
                    g.setStroke(new java.awt.BasicStroke(1f));
                }
                FontMetrics metrics = g.getFontMetrics(getFont());
                int chip = chipWidth();
                int content = labelWidth(metrics) + (chip > 0 ? CHIP_GAP + chip : 0);
                int tx = (width - content) / 2;
                g.setFont(getFont());
                paintLabel(g, tx, height, on ? Color.WHITE : Comic.INK, on, metrics);
                if (chip > 0) {
                    int cx = tx + labelWidth(metrics) + CHIP_GAP;
                    int cy = (height - CHIP_HEIGHT) / 2;
                    g.setColor(tone);
                    g.fillRoundRect(cx, cy, chip, CHIP_HEIGHT, CHIP_HEIGHT, CHIP_HEIGHT);
                    if (on) {
                        g.setColor(Color.WHITE);
                        g.drawRoundRect(cx, cy, chip - 1, CHIP_HEIGHT - 1, CHIP_HEIGHT, CHIP_HEIGHT);
                    }
                    Font small = chipFont();
                    g.setFont(small);
                    FontMetrics chipMetrics = g.getFontMetrics(small);
                    String text = String.valueOf(count);
                    g.setColor(Color.WHITE);
                    g.drawString(text, cx + (chip - chipMetrics.stringWidth(text)) / 2,
                            cy + (CHIP_HEIGHT - chipMetrics.getHeight()) / 2 + chipMetrics.getAscent());
                }
            } finally {
                g.dispose();
            }
        }
    }
}
