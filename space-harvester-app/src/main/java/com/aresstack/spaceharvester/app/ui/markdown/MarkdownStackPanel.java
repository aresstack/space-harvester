package com.aresstack.spaceharvester.app.ui.markdown;

import com.aresstack.enterpriseai.ui.comic.bubble.WidthAwareHeight;

import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;

/**
 * Ein vertikaler Stapel für gerenderte Markdown-Blöcke, der jedes Kind bei der tatsächlich zugewiesenen Breite
 * misst (aus askai-java8 {@code MarkdownStackPanel}).
 *
 * <p>Er ersetzt ein {@code BoxLayout.Y_AXIS}-Panel, um dessen gecachte Kindgrößen zu vermeiden: BoxLayout misst
 * einen umbrechenden Absatz einmal (oft bei Breite 0, also einzeilig) und behält diese veraltete Höhe, sodass der
 * Absatz bis zur nächsten Invalidierung abgeschnitten bleibt. Dieses Panel berechnet jede Kindhöhe in
 * {@link #doLayout()} und {@link #preferredHeightForWidth(int)} über {@link MarkdownHeights} aus der echten
 * Containerbreite; das erste Layout stimmt, kein zweiter Durchlauf nötig.
 */
final class MarkdownStackPanel extends JPanel implements WidthAwareHeight {

    MarkdownStackPanel() {
        setOpaque(false);
        setLayout(null); // Kinder werden in doLayout() manuell bei der echten Breite gestapelt
    }

    @Override
    public int preferredHeightForWidth(int width) {
        Insets insets = getInsets();
        int inner = Math.max(0, width - insets.left - insets.right);
        int height = insets.top + insets.bottom;
        for (Component child : getComponents()) {
            if (child.isVisible()) {
                height += MarkdownHeights.forWidth(child, inner);
            }
        }
        return height;
    }

    @Override
    public void doLayout() {
        Insets insets = getInsets();
        int inner = Math.max(0, getWidth() - insets.left - insets.right);
        int y = insets.top;
        for (Component child : getComponents()) {
            if (!child.isVisible()) {
                continue;
            }
            int height = MarkdownHeights.forWidth(child, inner);
            child.setBounds(insets.left, y, inner, height);
            y += height;
        }
    }

    @Override
    public Dimension getPreferredSize() {
        int width = getWidth();
        if (width <= 0 && getParent() != null) {
            width = getParent().getWidth();
        }
        if (width <= 0) {
            int widest = 1;
            for (Component child : getComponents()) {
                widest = Math.max(widest, child.getPreferredSize().width);
            }
            width = widest;
        }
        return new Dimension(width, preferredHeightForWidth(width));
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, getPreferredSize().height);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}
