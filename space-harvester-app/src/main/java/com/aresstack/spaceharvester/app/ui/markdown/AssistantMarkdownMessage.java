package com.aresstack.spaceharvester.app.ui.markdown;

import com.aresstack.enterpriseai.ui.comic.bubble.BubbleTimestamps;
import com.aresstack.enterpriseai.ui.comic.bubble.WidthAwareHeight;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.util.Objects;

/** Present assistant Markdown directly on the transcript surface, with actions below the answer. */
public final class AssistantMarkdownMessage extends JPanel implements WidthAwareHeight {

    private static final int HORIZONTAL_MARGIN = 14;
    private static final int VERTICAL_MARGIN = 8;
    private static final int FOOTER_GAP = 6;

    private final MarkdownMessageView body;
    private final JPanel footer = new JPanel(new BorderLayout(8, 0));
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final JLabel timestamp = new JLabel();
    private final JLabel failure = new JLabel("Fehler");

    public AssistantMarkdownMessage(MarkdownMessageView body, Color mutedForeground) {
        super(new BorderLayout(0, FOOTER_GAP));
        this.body = Objects.requireNonNull(body, "body");
        setOpaque(false);
        setAlignmentX(LEFT_ALIGNMENT);
        setBorder(new EmptyBorder(VERTICAL_MARGIN, HORIZONTAL_MARGIN, VERTICAL_MARGIN, HORIZONTAL_MARGIN));

        footer.setOpaque(false);
        actions.setOpaque(false);
        timestamp.setForeground(mutedForeground);
        timestamp.setFont(timestamp.getFont().deriveFont(Math.max(10f, timestamp.getFont().getSize2D() - 2f)));
        failure.setVisible(false);
        actions.add(failure);
        footer.add(actions, BorderLayout.WEST);
        footer.add(timestamp, BorderLayout.EAST);
        add(body, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
    }

    public MarkdownMessageView view() {
        return body;
    }

    public void setTimestamp(long epochMillis) {
        timestamp.setText(BubbleTimestamps.timeText(epochMillis) + " · " + BubbleTimestamps.dateText(epochMillis));
        timestamp.setToolTipText(BubbleTimestamps.tooltip(epochMillis));
        revalidate();
    }

    public void setActions(JComponent accessory) {
        actions.removeAll();
        if (accessory != null) {
            actions.add(accessory);
        }
        actions.add(failure);
        revalidate();
        repaint();
    }

    public void markFailed(Color color) {
        failure.setForeground(color);
        failure.setVisible(true);
        revalidate();
        repaint();
    }

    @Override
    public int preferredHeightForWidth(int width) {
        Insets insets = getInsets();
        int innerWidth = Math.max(1, width - insets.left - insets.right);
        return insets.top + insets.bottom + body.preferredHeightForWidth(innerWidth)
                + FOOTER_GAP + footer.getPreferredSize().height;
    }

    @Override
    public Dimension getPreferredSize() {
        int width = getWidth();
        Container parent = getParent();
        if (parent != null && parent.getWidth() > 0) {
            Insets insets = parent.getInsets();
            width = parent.getWidth() - insets.left - insets.right;
        }
        if (width <= 0) {
            width = super.getPreferredSize().width;
        }
        width = Math.max(1, width);
        return new Dimension(width, preferredHeightForWidth(width));
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, getPreferredSize().height);
    }
}
