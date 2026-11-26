package com.aresstack.spaceharvester.app.ui.markdown;

import com.aresstack.enterpriseai.ui.comic.bubble.WidthAwareHeight;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

/** Sources across the full width, the action row left-aligned below them, at the start of the answer text. */
final class AssistantMessageFooter extends JPanel implements WidthAwareHeight {

    private static final int ROW_GAP = 4;
    /** Abstände der Reihe und Innenrand des ersten Knopfs: so viel rückt die Reihe nach links, bündig zum Text. */
    private static final int LEAD = 9;

    private final JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final MessageTimestamp timestamp;
    private final javax.swing.JLabel failure = new javax.swing.JLabel("Fehler");
    private JComponent qr;
    private JComponent copy;
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
        order();
    }

    /** Der Kopierknopf: immer ganz rechts in der Reihe, hinter Uhrzeit und QR-Knopf, wie bei der Frage. */
    void setCopyAction(JComponent accessory) {
        if (copy != null) {
            controls.remove(copy);
        }
        copy = accessory;
        order();
    }

    private void order() {
        if (qr != null) {
            controls.add(qr);
        }
        if (copy != null) {
            controls.add(copy);
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
        int controlHeight = controls.getPreferredSize().height;
        JComponent links = sources();
        if (links == null || !links.isVisible()) {
            return controlHeight;
        }
        int sourceHeight = MarkdownHeights.forWidth(links, Math.max(1, width));
        return sourceHeight <= 0 ? controlHeight : sourceHeight + ROW_GAP + controlHeight;
    }

    /**
     * Die Quellen über die ganze Breite, darunter die Knöpfe mit der Uhrzeit linksbündig am Textanfang (Angelo,
     * 26.11.2026): Die Antwort beginnt links, dort endet auch das Lesen; rechts außen hingen die Knöpfe verloren.
     */
    @Override
    public void doLayout() {
        Dimension controlSize = controls.getPreferredSize();
        int width = getWidth();
        int y = 0;
        JComponent links = sources();
        if (links != null && links.isVisible()) {
            int sourceHeight = MarkdownHeights.forWidth(links, Math.max(1, width));
            links.setBounds(0, 0, Math.max(1, width), sourceHeight);
            links.doLayout();
            y = sourceHeight <= 0 ? 0 : sourceHeight + ROW_GAP;
        }
        controls.setBounds(-LEAD, y, Math.min(width + LEAD, controlSize.width), controlSize.height);
        controls.doLayout();
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
