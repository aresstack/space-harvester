package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Reiter als Pillen wie die Kategorien der Client-Einstellungen; der aktive Reiter ist dunkel gefüllt. */
public final class PillTabs extends JPanel {

    /** Umbrechend: In einer schmalen Reiterzeile (Aktionen rechts, Explorer offen) bleiben alle Pillen sichtbar. */
    private final JPanel bar = new JPanel(new com.aresstack.spaceharvester.app.ui.controls.WrapLayout(FlowLayout.LEFT, 4, 0));
    private final JPanel row = new JPanel(new BorderLayout(8, 0));
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);
    private final ButtonGroup group = new ButtonGroup();
    private final Map<String, ComposerToggleButton> buttons = new LinkedHashMap<>();
    private Consumer<String> listener = name -> { };
    /** Der gewählte Reiter, auch während die Knöpfe ihre Auswahl nicht zeigen ({@link #showSelection}). */
    private String current;

    public PillTabs() {
        super(new BorderLayout(0, 8));
        setOpaque(false);
        bar.setOpaque(false);
        deck.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        row.setOpaque(false);
        actions.setOpaque(false);
        row.add(bar, BorderLayout.CENTER);
        row.add(actions, BorderLayout.EAST);
        add(row, BorderLayout.NORTH);
        add(deck, BorderLayout.CENTER);
    }

    public PillTabs tab(String name, JComponent content) {
        ComposerToggleButton button = new ComposerToggleButton(null, name, null);
        button.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        button.addActionListener(e -> select(name));
        group.add(button);
        buttons.put(name, button);
        bar.add(button);
        deck.add(content, name);
        if (buttons.size() == 1) {
            button.setSelected(true);
            current = name;
        }
        return this;
    }

    /** Wie {@link #tab}, aber als erster Reiter vorn in der Leiste; er wird gewählt. */
    public PillTabs first(String name, JComponent content) {
        tab(name, content);
        bar.setComponentZOrder(buttons.get(name), 0);
        Map<String, ComposerToggleButton> ordered = new LinkedHashMap<>();
        ordered.put(name, buttons.remove(name));
        ordered.putAll(buttons);
        buttons.clear();
        buttons.putAll(ordered);
        select(name);
        return this;
    }

    /**
     * Nimmt die Pillen aus der Reiterzeile über dem Inhalt und gibt sie zum Einbau an anderer Stelle zurück, etwa
     * mittig in die untere Leiste mit der Lupe (Angelo 23.11.2026); einzeilig, mittig. Trägt die Reiterzeile keine
     * Aktionen, entfällt sie ganz, der Inhalt rückt nach oben. Mehrfach aufgerufen: dieselbe Leiste.
     */
    public JComponent detachBar() {
        if (bar.getParent() == row) {
            row.remove(bar);
            bar.setLayout(new FlowLayout(FlowLayout.CENTER, 4, 0));
            if (actions.getComponentCount() == 0) {
                remove(row);
            }
            revalidate();
        }
        return bar;
    }

    /** Aktionen rechts in der Reiterzeile, auf derselben Höhe wie die Pillen; sie gelten für alle Reiter. */
    public PillTabs actions(JComponent... buttons) {
        for (JComponent button : buttons) {
            actions.add(button);
        }
        return this;
    }

    /**
     * Eine Zahl am Reiter (UX 21.11.2026): ein kleiner Chip in {@code tone} direkt hinter dem Namen, etwa „Hintergrund
     * 1“; bei 0 kein Chip. Der Tooltip des Reiters sagt, was die Zahl zählt.
     *
     * @param tip Tooltip, solange die Zahl über 0 liegt; sonst bleibt {@code idleTip}
     */
    public void count(String name, int count, java.awt.Color tone, String tip, String idleTip) {
        ComposerToggleButton button = buttons.get(name);
        if (button == null) {
            return;
        }
        button.setHorizontalTextPosition(javax.swing.SwingConstants.LEADING);
        button.setIconTextGap(6);
        button.setIcon(count > 0 ? new CountIcon(count, tone, button.getFont()) : null);
        button.setToolTipText(count > 0 ? tip : idleTip);
        button.revalidate();
        button.repaint();
    }

    /** Die Zahl am Reiter, für Tests; 0 ohne Chip. */
    public int count(String name) {
        ComposerToggleButton button = buttons.get(name);
        return button != null && button.getIcon() instanceof CountIcon icon ? icon.count : 0;
    }

    /** Der Zahlenchip am Reiter: eine gefüllte Pille mit der Zahl in Weiß. */
    private static final class CountIcon implements javax.swing.Icon {
        private final int count;
        private final java.awt.Color tone;
        private final java.awt.Font font;
        private final String text;

        CountIcon(int count, java.awt.Color tone, java.awt.Font font) {
            this.count = count;
            this.tone = tone;
            this.font = font.deriveFont(java.awt.Font.BOLD, Math.max(10f, font.getSize2D() - 1f));
            this.text = String.valueOf(count);
        }

        private int textWidth() {
            java.awt.image.BufferedImage scratch = new java.awt.image.BufferedImage(1, 1,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics g = scratch.getGraphics();
            try {
                return g.getFontMetrics(font).stringWidth(text);
            } finally {
                g.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return Math.max(getIconHeight(), textWidth() + 10);
        }

        @Override
        public int getIconHeight() {
            return 16;
        }

        @Override
        public void paintIcon(java.awt.Component c, java.awt.Graphics graphics, int x, int y) {
            java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int width = getIconWidth();
                int height = getIconHeight();
                g.setColor(tone);
                g.fillRoundRect(x, y, width, height, height, height);
                g.setFont(font);
                java.awt.FontMetrics metrics = g.getFontMetrics();
                g.setColor(java.awt.Color.WHITE);
                g.drawString(text, x + (width - metrics.stringWidth(text)) / 2,
                        y + (height - metrics.getHeight()) / 2 + metrics.getAscent());
            } finally {
                g.dispose();
            }
        }
    }

    public void select(String name) {
        ComposerToggleButton button = buttons.get(name);
        if (button != null && button.isVisible()) {
            current = name;
            button.setSelected(true);
            cards.show(deck, name);
            listener.accept(name);
        }
    }

    public String selected() {
        return current;
    }

    /**
     * Ob die Knöpfe den gewählten Reiter dunkel zeigen. Stehen die Reiter als Umschalter in der Icon-Leiste des
     * Hauptfensters, zeigen sie ihre Auswahl nur, solange die Mitte auch ihren Inhalt zeigt; der gewählte Reiter
     * ({@link #selected()}) bleibt dabei erhalten und kommt mit {@code true} wieder.
     */
    public void showSelection(boolean shown) {
        if (!shown) {
            group.clearSelection();
            return;
        }
        ComposerToggleButton button = current == null ? null : buttons.get(current);
        if (button != null) {
            button.setSelected(true);
        }
    }

    public void onSelect(Consumer<String> listener) {
        this.listener = listener;
    }

    /** Namen der Reiter in der Reihenfolge der Leiste. */
    public java.util.List<String> names() {
        return buttons.entrySet().stream().filter(entry -> entry.getValue().isVisible())
                .map(Map.Entry::getKey).toList();
    }

    /** Show only the requested tabs, keeping the selection if it remains available. */
    public void showOnly(java.util.List<String> names) {
        if (names.isEmpty() || !buttons.keySet().containsAll(names)) {
            throw new IllegalArgumentException("Expected a non-empty set of registered tabs");
        }
        buttons.forEach((name, button) -> button.setVisible(names.contains(name)));
        if (!names.contains(current)) select(names.get(0));
        bar.revalidate();
        bar.repaint();
    }

    public ComposerToggleButton button(String name) {
        return buttons.get(name);
    }

    /**
     * Zeigt die Reiter an Ort und Stelle als Symbolknöpfe statt als Pillen mit Text (Angelo 27.11.2026): Der Name
     * steht im Tooltip und bleibt der zugängliche Name, Reihenfolge, Auswahl und Füllung bleiben. Reiter ohne Symbol
     * in {@code icons} behalten ihren Text. Eine Zahl am Reiter setzt danach {@link #mark}.
     */
    public void icons(Map<String, javax.swing.Icon> icons) {
        icons.forEach((name, icon) -> {
            ComposerToggleButton button = buttons.get(name);
            if (button == null) {
                return;
            }
            button.setIcon(icon);
            button.setText(null);
            button.setToolTipText(name);
            button.getAccessibleContext().setAccessibleName(name);
            square(button);
        });
        bar.revalidate();
        bar.repaint();
    }

    /**
     * Eine kurze Marke hinter dem Symbol eines Reiters, etwa die Zahl der Widersprüche; {@code null} nimmt sie weg.
     * Der Tooltip nennt dann Name und Marke. Nur für Reiter, die {@link #icons} auf Symbole umgestellt hat.
     */
    public void mark(String name, String mark) {
        ComposerToggleButton button = buttons.get(name);
        if (button == null) {
            return;
        }
        button.setText(mark);
        button.setToolTipText(mark == null ? name : name + " · " + mark);
        button.setPreferredSize(null);
        if (mark == null) {
            square(button);
        }
        button.revalidate();
        button.repaint();
    }

    private static void square(ComposerToggleButton button) {
        button.setPreferredSize(null);
        java.awt.Dimension natural = button.getPreferredSize();
        int side = Math.max(natural.width, natural.height);
        button.setPreferredSize(new java.awt.Dimension(side, side));
    }

    /**
     * Macht aus den Reitern quadratische Symbolknöpfe (der Name bleibt Tooltip und zugänglicher Name) und gibt ihre
     * Leiste zum Einbau an anderer Stelle zurück, etwa in die Icon-Leiste des Hauptfensters. Umschalten, Auswahl und
     * die dunkle Füllung des aktiven Knopfs bleiben wie bei den Pillen; die Reiterzeile oben entfällt, solange sie
     * keine Aktionen trägt. Reiter ohne Symbol in {@code icons} behalten ihre Pille.
     */
    public JComponent iconBar(Map<String, javax.swing.Icon> icons) {
        JPanel strip = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        strip.setOpaque(false);
        String selected = selected();
        for (Map.Entry<String, ComposerToggleButton> entry : new LinkedHashMap<>(buttons).entrySet()) {
            javax.swing.Icon icon = icons.get(entry.getKey());
            if (icon == null) {
                continue;
            }
            String name = entry.getKey();
            ComposerToggleButton old = entry.getValue();
            group.remove(old);
            bar.remove(old);
            ComposerToggleButton button = new ComposerToggleButton(icon, null, name);
            button.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
            button.getAccessibleContext().setAccessibleName(name);
            java.awt.Dimension natural = button.getPreferredSize();
            int side = Math.max(natural.width, natural.height);
            button.setPreferredSize(new java.awt.Dimension(side, side));
            button.addActionListener(e -> select(name));
            group.add(button);
            buttons.put(name, button);
            strip.add(button);
            button.setSelected(name.equals(selected));
        }
        if (bar.getComponentCount() == 0 && actions.getComponentCount() == 0) {
            remove(row);
        }
        revalidate();
        return strip;
    }
}
