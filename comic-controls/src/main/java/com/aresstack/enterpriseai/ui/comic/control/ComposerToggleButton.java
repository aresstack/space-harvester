package com.aresstack.enterpriseai.ui.comic.control;

import javax.swing.Icon;
import javax.swing.JToggleButton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;

/**
 * The toggle sibling of {@link ComposerButton}: flat with a hover wash while off, the filled
 * accent plate with white text while SELECTED — a small integrated switch inside the composer
 * surface (e.g. the knowledge-base toggle) or a navigation entry that stays lit.
 */
public class ComposerToggleButton extends JToggleButton {

    private Color accent;

    public ComposerToggleButton(Icon icon, String text, String tooltip) {
        super(text, icon);
        ComposerButtonStyle.configure(this, tooltip);
    }

    /** The plate color while selected ({@code null} = the composer's accent blue). */
    public void setAccent(Color accent) {
        this.accent = accent;
        repaint();
    }

    public Color getAccent() {
        return accent;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        ComposerButtonStyle.paintBackground(this, graphics, accent, isSelected());
        super.paintComponent(graphics);
    }

    @Override
    public Dimension getPreferredSize() {
        if (isPreferredSizeSet()) {
            return super.getPreferredSize();
        }
        return ComposerButtonStyle.preferredSize(super.getPreferredSize(), false);
    }
}
