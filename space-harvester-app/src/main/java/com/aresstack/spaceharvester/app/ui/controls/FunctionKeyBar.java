package com.aresstack.spaceharvester.app.ui.controls;

import java.awt.AlphaComposite;
import java.awt.Component;
import java.awt.Container;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntConsumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

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
        setBackground(AppColors.of("app.FunctionKeyBar.barBackground"));
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
        JPanel row = new JPanel(new KeyRow());
        row.setOpaque(false);
        for (int number = first; number < first + 12; number++) {
            KeyCap key = new KeyCap(number, assigned.get(number));
            keys.put(number, key);
            row.add(key);
        }
        return row;
    }

    /** Die gemeinsame Breite aller Tasten: die der breitesten Beschriftung (Angelo: Tasten sind gleich groß). */
    int keyWidth() {
        int widest = 0;
        for (KeyCap key : keys.values()) {
            widest = Math.max(widest, key.naturalWidth());
        }
        return widest;
    }

    /**
     * Eine Zeile aus drei Blöcken zu je vier gleich breiten Tasten wie in MainframeMate: der erste bündig links, der
     * dritte bündig rechts, der mittlere mittig. So stehen F1 und F13, F10 und F22 genau untereinander.
     */
    private final class KeyRow implements LayoutManager {

        private static final int GAP = 2;
        private static final int VGAP = 1;

        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            int height = 0;
            for (Component key : parent.getComponents()) {
                height = Math.max(height, key.getPreferredSize().height);
            }
            // Drei Blöcke zu vier Tasten, dazwischen etwas Luft.
            return new Dimension(12 * keyWidth() + 9 * GAP + 2 * 16, height + 2 * VGAP);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return preferredLayoutSize(parent);
        }

        @Override
        public void layoutContainer(Container parent) {
            Component[] parts = parent.getComponents();
            if (parts.length == 0) {
                return;
            }
            int width = parent.getWidth();
            int height = parent.getHeight() - 2 * VGAP;
            // Zu schmal: gleichmäßig schmaler statt überlappen.
            int key = Math.min(keyWidth(), Math.max(8, (width - 9 * GAP - 2 * 8) / 12));
            int block = 4 * key + 3 * GAP;
            int[] starts = {0, (width - block) / 2, width - block};
            for (int i = 0; i < parts.length; i++) {
                int x = starts[Math.min(2, i / 4)] + (i % 4) * (key + GAP);
                parts[i].setBounds(x, VGAP, key, height);
            }
        }
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
        Color yellow = AppColors.of("app.FunctionKeyBar.yellow");
        Color orange = AppColors.of("app.FunctionKeyBar.orange");
        Color red = AppColors.of("app.FunctionKeyBar.red");
        Color green = AppColors.of("app.FunctionKeyBar.green");
        Color teal = AppColors.of("app.FunctionKeyBar.teal");
        Color blue = AppColors.of("app.FunctionKeyBar.blue");
        if (key >= 1 && key <= 4) {
            return blend(yellow, orange, (key - 1) / 3f * 0.25f);
        } else if (key >= 5 && key <= 8) {
            return blend(orange, red, (key - 5) / 3f * 0.25f);
        } else if (key >= 9 && key <= 12) {
            return blend(red, AppColors.of("app.FunctionKeyBar.redTarget"), (key - 9) / 3f * 0.15f);
        } else if (key >= 13 && key <= 16) {
            return blend(green, teal, (key - 13) / 3f * 0.25f);
        } else if (key >= 17 && key <= 20) {
            return blend(teal, blue, (key - 17) / 3f * 0.25f);
        } else if (key >= 21 && key <= 24) {
            return blend(blue, AppColors.of("app.FunctionKeyBar.blueTarget"), (key - 21) / 3f * 0.15f);
        }
        return AppColors.of("app.FunctionKeyBar.neutralKey");
    }

    /** Blasse Gruppenfarbe für unbelegte Tasten: 75 % Richtung Weiß. */
    static Color pale(int key) {
        return blend(color(key), AppColors.of("comic.buttonPlate"), 0.75f);
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
            ink = !assigned ? AppColors.of("app.FunctionKeyBar.keyCap") : brightness > 0.55f ? AppColors.of("app.FunctionKeyBar.darkInk") : AppColors.of("bubble.userForeground");
            setToolTipText(assigned ? "F" + number + " – " + label : "F" + number + " (nicht belegt)");
            setFont(getFont().deriveFont(Font.PLAIN, 11f));
            setMargin(new Insets(1, 4, 1, 4));
            setFocusable(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            addActionListener(e -> press.accept(number));
        }

        /** Breite für die eigene Beschriftung; gezeigt wird jede Taste so breit wie die breiteste. */
        int naturalWidth() {
            return getFontMetrics(getFont()).stringWidth(getText()) + 12;
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics metrics = getFontMetrics(getFont());
            return new Dimension(keyWidth(), metrics.getHeight() + 6);
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
