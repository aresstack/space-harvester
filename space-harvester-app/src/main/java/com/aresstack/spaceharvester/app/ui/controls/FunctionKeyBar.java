package com.aresstack.spaceharvester.app.ui.controls;

import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntConsumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;

/**
 * PF-Tastenleiste am unteren Rand des Terminals, übernommen aus MainframeMate (TerminalConnectionTab.createFkeyPanel
 * und refreshFkeyLegend): F1–F12 immer sichtbar in drei Gruppen (F1–F4 links gelb, F5–F8 Mitte orange, F9–F12 rechts
 * rot); belegte Tasten zeigen „F3-Exit“ in voller Gruppenfarbe, unbelegte nur „F3“ blass, funktionieren aber ebenso.
 * F13–F24 (grün, türkis, blau) erscheinen als zweite Zeile nur, wenn das Bild eine davon belegt. Der Hintergrund ist
 * halb durchsichtig, damit die Leiste über dem Sternenhimmel schwebt.
 *
 * <p>Die Leiste fragt nichts ab: Wer sie benutzt, ruft {@link #show(Map)} mit der Belegung des neuen Bildes auf;
 * Klicks gehen mit der Tastennummer an den {@code press}-Empfänger.
 */
public final class FunctionKeyBar extends JPanel {

    /** Deckkraft des Hintergrunds in Prozent; Vorgabe 50 wie tn3270FkeyOverlayOpacity in MainframeMate. */
    public static final int DEFAULT_OPACITY = 50;

    private final IntConsumer press;
    private final Map<Integer, KeyCap> keys = new HashMap<>();
    private Map<Integer, String> legend;
    private int opacity = DEFAULT_OPACITY;

    public FunctionKeyBar(IntConsumer press) {
        super(new GridLayout(0, 1, 0, 0));
        this.press = press;
        setOpaque(false);
        setBackground(new Color(240, 240, 240));
        setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 8));
        getAccessibleContext().setAccessibleName("PF-Tasten");
        show(Map.of());
    }

    /** Zeigt die Belegung PF-Nummer → Bezeichnung; baut nur neu, wenn sie sich geändert hat. */
    public void show(Map<Integer, String> assigned) {
        if (assigned.equals(legend)) {
            return;
        }
        legend = Map.copyOf(assigned);
        keys.clear();
        removeAll();
        add(row(1, assigned));
        boolean extended = assigned.keySet().stream().anyMatch(number -> number >= 13 && number <= 24);
        if (extended) {
            add(row(13, assigned));
        }
        revalidate();
        repaint();
        if (getParent() != null) {
            getParent().revalidate();
            getParent().repaint();
        }
    }

    /** Zeigt die Taste als gedrückt, solange die echte F-Taste unten ist (ohne sie ein zweites Mal zu senden). */
    public void showPressed(int number, boolean pressed) {
        KeyCap key = keys.get(number);
        if (key != null) {
            // Erst entschärfen, dann loslassen: sonst löst DefaultButtonModel die Aktion aus (wie in MainframeMate).
            key.getModel().setArmed(pressed);
            key.getModel().setPressed(pressed);
        }
    }

    public void setOpacity(int percent) {
        opacity = Math.max(0, Math.min(100, percent));
        repaint();
    }

    /** Die Knöpfe nach Nummer (für Tests). */
    public JButton key(int number) {
        return keys.get(number);
    }

    public Map<Integer, String> legend() {
        return legend;
    }

    private JPanel row(int first, Map<Integer, String> assigned) {
        JPanel left = group(FlowLayout.LEFT);
        JPanel center = group(FlowLayout.CENTER);
        JPanel right = group(FlowLayout.RIGHT);
        for (int number = first; number < first + 12; number++) {
            KeyCap key = new KeyCap(number, assigned.get(number));
            keys.put(number, key);
            int position = number - first;
            (position < 4 ? left : position < 8 ? center : right).add(key);
        }
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.add(left, BorderLayout.WEST);
        row.add(center, BorderLayout.CENTER);
        row.add(right, BorderLayout.EAST);
        return row;
    }

    private static JPanel group(int align) {
        JPanel panel = new JPanel(new FlowLayout(align, 2, 1));
        panel.setOpaque(false);
        return panel;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity / 100f));
        g2.setColor(getBackground());
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.dispose();
    }

    /** Gruppenfarbe aus MainframeMate (getFkeyColor). */
    static Color color(int key) {
        Color yellow = new Color(255, 230, 80);
        Color orange = new Color(255, 165, 50);
        Color red = new Color(220, 70, 60);
        Color green = new Color(90, 200, 90);
        Color teal = new Color(60, 190, 190);
        Color blue = new Color(80, 120, 210);
        if (key >= 1 && key <= 4) {
            return blend(yellow, orange, (key - 1) / 3f * 0.25f);
        } else if (key >= 5 && key <= 8) {
            return blend(orange, red, (key - 5) / 3f * 0.25f);
        } else if (key >= 9 && key <= 12) {
            return blend(red, new Color(180, 50, 50), (key - 9) / 3f * 0.15f);
        } else if (key >= 13 && key <= 16) {
            return blend(green, teal, (key - 13) / 3f * 0.25f);
        } else if (key >= 17 && key <= 20) {
            return blend(teal, blue, (key - 17) / 3f * 0.25f);
        } else if (key >= 21 && key <= 24) {
            return blend(blue, new Color(60, 80, 180), (key - 21) / 3f * 0.15f);
        }
        return new Color(200, 200, 200);
    }

    /** Blasse Gruppenfarbe für unbelegte Tasten: 75 % Richtung Weiß. */
    static Color pale(int key) {
        return blend(color(key), Color.WHITE, 0.75f);
    }

    private static Color blend(Color a, Color b, float t) {
        float u = 1f - t;
        return new Color(Math.round(a.getRed() * u + b.getRed() * t), Math.round(a.getGreen() * u + b.getGreen() * t),
                Math.round(a.getBlue() * u + b.getBlue() * t));
    }

    /** Eine Taste: selbst gezeichnet, damit die Gruppenfarbe in jedem Look-and-Feel stimmt. */
    private final class KeyCap extends JButton {

        private final Color fill;
        private final Color ink;

        KeyCap(int number, String label) {
            super(label == null ? "F" + number : "F" + number + "-" + label);
            boolean assigned = label != null;
            fill = assigned ? color(number) : pale(number);
            float brightness = (fill.getRed() * 299 + fill.getGreen() * 587 + fill.getBlue() * 114) / 255000f;
            ink = !assigned ? new Color(160, 160, 160) : brightness > 0.55f ? Color.BLACK : Color.WHITE;
            setToolTipText(assigned ? "F" + number + " – " + label : "F" + number + " (nicht belegt)");
            setFont(getFont().deriveFont(Font.PLAIN, 11f));
            setMargin(new Insets(1, 4, 1, 4));
            setFocusable(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            addActionListener(e -> press.accept(number));
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics metrics = getFontMetrics(getFont());
            return new Dimension(metrics.stringWidth(getText()) + 12, metrics.getHeight() + 6);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            boolean down = getModel().isPressed() && getModel().isArmed();
            g2.setColor(down ? fill.darker() : getModel().isRollover() ? fill.brighter() : fill);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
            g2.setColor(fill.darker());
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
            g2.setColor(ink);
            g2.setFont(getFont());
            FontMetrics metrics = g2.getFontMetrics();
            int x = (getWidth() - metrics.stringWidth(getText())) / 2 + (down ? 1 : 0);
            int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent() + (down ? 1 : 0);
            g2.drawString(getText(), x, y);
            g2.dispose();
        }
    }
}
