package com.aresstack.spaceharvester.app.ui.markdown;

import com.aresstack.enterpriseai.ui.comic.bubble.WidthAwareHeight;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

/** The action row left-aligned at the start of the answer text, the source links beside it in the same row. */
final class AssistantMessageFooter extends JPanel implements WidthAwareHeight {

    private static final int ROW_GAP = 4;
    /** Abstand zwischen den Knöpfen und den Quellen daneben. */
    private static final int GAP = 12;
    private static final int MIN_SOURCE_WIDTH = 120;
    /** Abstände der Reihe und Innenrand des ersten Knopfs: so viel rückt die Reihe nach links, bündig zum Text. */
    private static final int LEAD = 5;
    /** Abstand zwischen dem allein stehenden Kopierknopf und den übrigen Knöpfen (zusätzlich zum Reihenabstand). */
    static final int COPY_GAP = 8;

    private final JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final MessageTimestamp timestamp;
    private final javax.swing.JLabel failure = new javax.swing.JLabel("Fehler");
    private JComponent qr;
    private JComponent copy;
    private final java.awt.Component copyGap = javax.swing.Box.createHorizontalStrut(COPY_GAP);
    private JComponent sources;

    AssistantMessageFooter(java.awt.Color foreground) {
        super(null);
        setOpaque(false);
        controls.setOpaque(false);
        controls.setName("transcript.messageControls");
        actions.setOpaque(false);
        timestamp = new MessageTimestamp(foreground);
        failure.setVisible(false);
        // Reihenfolge (Angelo, 26.11.2026): Kopieren abgesetzt links, dann Uhrzeit, QR-Knopf und zuletzt der Export.
        controls.add(failure);
        controls.add(timestamp);
        controls.add(actions);
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

    /**
     * Der Kopierknopf steht für sich an der äußeren Kante, hier links am Textanfang, mit Abstand zum Rest (Angelo,
     * 26.11.2026): Er ist die häufigste Aktion und soll nicht neben dem QR-Knopf liegen, wo man sich verklickt.
     */
    void setCopyAction(JComponent accessory) {
        if (copy != null) {
            controls.remove(copy);
            controls.remove(copyGap);
        }
        copy = accessory;
        if (copy != null) {
            controls.add(copy, 0);
            controls.add(copyGap, 1);
        }
        refresh();
    }

    private void order() {
        if (qr != null) {
            controls.add(qr, controls.getComponentZOrder(actions));
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
        if (stackSources(width, controlSize.width)) {
            int sourceHeight = MarkdownHeights.forWidth(links, Math.max(1, width));
            return sourceHeight <= 0 ? controlSize.height : controlSize.height + ROW_GAP + sourceHeight;
        }
        return Math.max(controlSize.height, MarkdownHeights.forWidth(links, sourceWidth(width, controlSize.width)));
    }

    /**
     * Die Knöpfe mit der Uhrzeit linksbündig am Textanfang (Angelo, 26.11.2026). Die Quellen beginnen rechts
     * daneben in derselben Zeile und laufen, wenn sie umbrechen, bündig darunter weiter; die Zeile wächst erst mit
     * einer aufgeklappten langen Liste. Nur in einer sehr schmalen Leiste stehen sie unter den Knöpfen.
     */
    @Override
    public void doLayout() {
        Dimension controlSize = controls.getPreferredSize();
        int width = getWidth();
        controls.setBounds(-LEAD, 0, Math.min(width + LEAD, controlSize.width), controlSize.height);
        controls.doLayout();
        JComponent links = sources();
        if (links != null && links.isVisible()) {
            boolean stacked = stackSources(width, controlSize.width);
            int sourceWidth = stacked ? Math.max(1, width) : sourceWidth(width, controlSize.width);
            int x = stacked ? 0 : width - sourceWidth;
            int y = stacked ? controlSize.height + ROW_GAP : 0;
            links.setBounds(x, y, sourceWidth, MarkdownHeights.forWidth(links, sourceWidth));
            links.doLayout();
        }
    }

    private int sourceWidth(int width, int controlsWidth) {
        return Math.max(1, width - (controlsWidth - LEAD) - GAP);
    }

    private boolean stackSources(int width, int controlsWidth) {
        return width < controlsWidth - LEAD + GAP + MIN_SOURCE_WIDTH;
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
