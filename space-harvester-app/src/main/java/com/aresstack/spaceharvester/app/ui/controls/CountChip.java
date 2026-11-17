package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.BorderFactory;
import javax.swing.JToggleButton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Objects;

/**
 * Ein Zählchip mit Filter: Wort und Zahl in einer Pille, etwa „gilt 12“. Ein Klick filtert auf das Wort, ein zweiter
 * hebt den Filter auf. Die Farbe begleitet nur; das Wort steht immer dabei. Gewählt ist die Pille gefüllt.
 */
public final class CountChip extends JToggleButton {

    private static final int HEIGHT = 24;
    private static final int PAD = 10;
    private final String word;
    private final Color tone;
    private int count;
    /** Ohne Zahl: eine Auswahlpille mit Wort (etwa „Stand setzen“ im Overlay). */
    private boolean numbered = true;
    private String tipOff;
    private String tipOn;
    private final KeyboardFocusRing focusRing;

    public CountChip(String word, Color tone) {
        this.word = Objects.requireNonNull(word, "word");
        this.tone = Objects.requireNonNull(tone, "tone");
        setFont(Comic.semiBold(12f));
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setBorder(BorderFactory.createEmptyBorder());
        focusRing = KeyboardFocusRing.install(this);
        addItemListener(e -> describe());
        count(0);
    }

    /**
     * Eine Auswahlpille nur mit Wort, ohne Zahl; der Tooltip sagt, dass ein Klick das Wort von Hand setzt.
     */
    public static CountChip choice(String word, Color tone) {
        CountChip chip = new CountChip(word, tone);
        chip.numbered = false;
        chip.count(0);
        return chip;
    }

    /** Die Zahl hinter dem Wort. */
    public void count(int count) {
        this.count = count;
        setText(numbered ? word + " " + count : word);
        describe();
        revalidate();
        repaint();
    }

    public int count() {
        return count;
    }

    public String word() {
        return word;
    }

    /**
     * Eigene Tooltips statt „Nur … zeigen“, etwa für einen Chip, der etwas zusätzlich einblendet: {@code off} gilt,
     * solange er nicht gedrückt ist, {@code on} danach.
     */
    public CountChip tips(String off, String on) {
        this.tipOff = Objects.requireNonNull(off, "off");
        this.tipOn = Objects.requireNonNull(on, "on");
        describe();
        return this;
    }

    private void describe() {
        String tip = tipOff != null ? isSelected() ? tipOn : tipOff : !numbered ? isSelected() ? "Von Hand gesetzt: „" + word + "“" : "„" + word + "“ von Hand setzen"
                : isSelected() ? "Filter „" + word + "“ aufheben" : "Nur „" + word + "“ zeigen";
        setToolTipText(tip);
        getAccessibleContext().setAccessibleName((numbered ? word + " " + count : word) + ", " + tip);
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics metrics = getFontMetrics(getFont());
        return new Dimension(metrics.stringWidth(getText()) + 2 * PAD, HEIGHT);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth() - 1;
            int h = getHeight() - 1;
            boolean hot = isEnabled() && (getModel().isRollover() || focusRing.shown());
            if (isSelected()) {
                g.setColor(tone);
                g.fillRoundRect(0, 0, w, h, h, h);
            } else {
                g.setColor(hot ? Comic.NOTICE : Comic.SURFACE);
                g.fillRoundRect(0, 0, w, h, h, h);
            }
            g.setColor(hot && !isSelected() ? Comic.YELLOW : tone);
            g.drawRoundRect(0, 0, w, h, h, h);
            g.setFont(getFont());
            FontMetrics metrics = g.getFontMetrics();
            g.setColor(isSelected() ? Comic.SURFACE : tone);
            int x = (getWidth() - metrics.stringWidth(getText())) / 2;
            int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            g.drawString(getText(), x, y);
        } finally {
            g.dispose();
        }
    }
}
