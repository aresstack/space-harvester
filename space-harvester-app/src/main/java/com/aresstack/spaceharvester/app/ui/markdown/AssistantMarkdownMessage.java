package com.aresstack.spaceharvester.app.ui.markdown;

import com.aresstack.enterpriseai.ui.comic.bubble.WidthAwareHeight;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.Objects;

/** Present assistant Markdown directly on the transcript surface, with actions below the answer. */
public final class AssistantMarkdownMessage extends JPanel implements WidthAwareHeight {

    private static final int HORIZONTAL_MARGIN = 14;
    private static final int VERTICAL_MARGIN = 8;
    private static final int FOOTER_GAP = 6;

    private final MarkdownMessageView body;
    private final AssistantMessageFooter footer;

    public AssistantMarkdownMessage(MarkdownMessageView body, Color mutedForeground) {
        super(new BorderLayout(0, FOOTER_GAP));
        this.body = Objects.requireNonNull(body, "body");
        setOpaque(false);
        setAlignmentX(LEFT_ALIGNMENT);
        setBorder(new EmptyBorder(VERTICAL_MARGIN, HORIZONTAL_MARGIN, VERTICAL_MARGIN, HORIZONTAL_MARGIN));

        footer = new AssistantMessageFooter(mutedForeground);
        add(body, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
    }

    public MarkdownMessageView view() {
        return body;
    }

    public void setTimestamp(long epochMillis) {
        footer.setTimestamp(epochMillis);
    }

    public void setActions(JComponent accessory) {
        footer.setActions(accessory);
    }

    public void setQrAction(JComponent accessory) {
        footer.setQrAction(accessory);
    }

    public void setSources(JComponent sources) {
        footer.setSources(sources);
    }

    public JComponent sources() {
        return footer.sources();
    }

    public void markFailed(Color color) {
        footer.markFailed(color);
    }

    @Override
    public int preferredHeightForWidth(int width) {
        Insets insets = getInsets();
        int innerWidth = Math.max(1, width - insets.left - insets.right);
        return insets.top + insets.bottom + body.preferredHeightForWidth(innerWidth)
                + FOOTER_GAP + footer.preferredHeightForWidth(innerWidth);
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
