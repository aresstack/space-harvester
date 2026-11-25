package com.aresstack.spaceharvester.app.ui.markdown;

import com.aresstack.enterpriseai.ui.comic.bubble.WidthAwareHeight;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

/** Reserve the right-hand action area and wrap source links in the remaining space. */
final class AssistantMessageFooter extends JPanel implements WidthAwareHeight {

    private static final int GAP = 8;
    private static final int MIN_SOURCE_WIDTH = 120;
    private static final int ROW_GAP = 4;

    private final JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    private final MessageTimestamp timestamp;
    private final javax.swing.JLabel failure = new javax.swing.JLabel("Fehler");
    private JComponent qr;
    private JComponent sources;

    AssistantMessageFooter(java.awt.Color foreground) {
        super(null);
        setOpaque(false);
        controls.setOpaque(false);
        controls.setName("transcript.messageControls");
        actions.setOpaque(false);
        timestamp = new MessageTimestamp(foreground);
        failure.setVisible(false);
        controls.add(failure);
        controls.add(actions);
        controls.add(timestamp);
        add(controls);
    }

    void setTimestamp(long at) {
        timestamp.setTime(at);
        refresh();
    }

    void setActions(JComponent accessory) {
        actions.removeAll();
        if (accessory != null) {
            actions.add(accessory);
        }
        refresh();
    }

    void setQrAction(JComponent accessory) {
        if (qr != null) {
            controls.remove(qr);
        }
        qr = accessory;
        if (qr != null) {
            controls.add(qr);
        }
        refresh();
    }

    void markFailed(java.awt.Color color) {
        failure.setForeground(color);
        failure.setVisible(true);
        refresh();
    }

    void setSources(JComponent component) {
        if (sources != null && sources.getParent() == this) {
            remove(sources);
        }
        sources = component;
        if (sources != null) {
            add(sources);
        }
        refresh();
    }

    JComponent sources() {
        return sources != null && sources.getParent() == this ? sources : null;
    }

    @Override
    public int preferredHeightForWidth(int width) {
        Dimension controlSize = controls.getPreferredSize();
        JComponent links = sources();
        if (links == null || !links.isVisible()) {
            return controlSize.height;
        }
        boolean stacked = stackSources(width, controlSize.width);
        int sourceWidth = stacked ? width : width - controlSize.width - GAP;
        int sourceHeight = MarkdownHeights.forWidth(links, Math.max(1, sourceWidth));
        return stacked ? controlSize.height + ROW_GAP + sourceHeight : Math.max(controlSize.height, sourceHeight);
    }

    @Override
    public void doLayout() {
        Dimension controlSize = controls.getPreferredSize();
        int width = getWidth();
        int controlWidth = Math.min(width, controlSize.width);
        controls.setBounds(width - controlWidth, 0, controlWidth, controlSize.height);
        controls.doLayout();
        JComponent links = sources();
        if (links != null && links.isVisible()) {
            boolean stacked = stackSources(width, controlSize.width);
            int sourceWidth = Math.max(1, stacked ? width : width - controlSize.width - GAP);
            int y = stacked ? controlSize.height + ROW_GAP : 0;
            links.setBounds(0, y, sourceWidth, MarkdownHeights.forWidth(links, sourceWidth));
            links.doLayout();
        }
    }

    private boolean stackSources(int width, int controlsWidth) {
        return width < controlsWidth + GAP + MIN_SOURCE_WIDTH;
    }

    @Override
    public Dimension getPreferredSize() {
        Container parent = getParent();
        int width = getWidth();
        if (parent != null && parent.getWidth() > 0) {
            Insets insets = parent.getInsets();
            width = parent.getWidth() - insets.left - insets.right;
        }
        if (width <= 0) {
            width = controls.getPreferredSize().width;
        }
        return new Dimension(width, preferredHeightForWidth(width));
    }

    private void refresh() {
        revalidate();
        repaint();
    }
}
