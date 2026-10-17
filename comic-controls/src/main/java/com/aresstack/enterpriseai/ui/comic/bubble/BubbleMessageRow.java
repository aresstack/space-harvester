package com.aresstack.enterpriseai.ui.comic.bubble;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Dimension;
import java.awt.Insets;

/**
 * Aligns ONE bubble at its participant's side. It holds no geometry of its own — every width, height and
 * position comes from {@link BubbleRowGeometry}, so a speech bubble and a streamed Markdown answer are laid
 * out by exactly the same rules.
 * <p>
 * The row fills the transcript width (see {@link #getMaximumSize()}): a capped-width row would, inside a
 * BoxLayout.Y_AXIS mixed with the 0.5-aligned struts between rows, be positioned by alignmentX relative to
 * its neighbours — which is what once pushed the assistant bubble to the right.
 */
public final class BubbleMessageRow extends JPanel {

    private final JComponent bubble;
    private final BubbleSide side;

    public BubbleMessageRow(JComponent bubble, BubbleSide side) {
        if (bubble == null) {
            throw new IllegalArgumentException("bubble must not be null");
        }
        if (side == null) {
            throw new IllegalArgumentException("side must not be null");
        }
        this.bubble = bubble;
        this.side = side;
        setOpaque(false);
        setLayout(null);
        add(bubble);
    }

    public JComponent getBubble() {
        return bubble;
    }

    @Override
    public Dimension getPreferredSize() {
        int rowWidth = BubbleRowGeometry.rowWidth(this);
        int bubbleWidth = BubbleRowGeometry.bubbleWidth(bubble, rowWidth);
        return new Dimension(Math.max(1, rowWidth),
                BubbleRowGeometry.bubbleHeight(bubble, bubbleWidth) + (BubbleRowGeometry.VERTICAL_GAP * 2));
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    public void doLayout() {
        Insets insets = getInsets();
        int rowWidth = Math.max(1, getWidth() - insets.left - insets.right);
        int bubbleWidth = BubbleRowGeometry.bubbleWidth(bubble, rowWidth);
        int height = BubbleRowGeometry.bubbleHeight(bubble, bubbleWidth);
        bubble.setBounds(insets.left + BubbleRowGeometry.bubbleX(side, rowWidth, bubbleWidth),
                insets.top + BubbleRowGeometry.VERTICAL_GAP, bubbleWidth, height);
    }
}
