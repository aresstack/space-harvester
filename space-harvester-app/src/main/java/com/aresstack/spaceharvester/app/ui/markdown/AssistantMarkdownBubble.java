package com.aresstack.spaceharvester.app.ui.markdown;

import com.aresstack.enterpriseai.ui.comic.bubble.BubblePalette;
import com.aresstack.enterpriseai.ui.comic.bubble.BubbleSide;
import com.aresstack.enterpriseai.ui.comic.bubble.BubbleTimestamps;
import com.aresstack.enterpriseai.ui.comic.bubble.SpeechBubblePanel;
import com.aresstack.enterpriseai.ui.comic.bubble.TranscriptBubble;
import com.aresstack.enterpriseai.ui.comic.bubble.WidthAwareHeight;

import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Eine Assistenten-Sprechblase, deren Körper eine native {@link MarkdownMessageView} ist (Überschriften, Listen,
 * Code, Tabellen, Links, Mermaid) — aus askai-java8 {@code AssistantMarkdownBubble}. Anders als
 * {@link SpeechBubblePanel} leitet sie ihre Breite nicht aus einer Textfläche ab, sondern füllt die Zeile des
 * Verlaufs, damit der Markdown-Inhalt bei der echten verfügbaren Breite umbricht. Hier wird nur die Blasenform
 * gezeichnet.
 *
 * <p>Die Schwanzrichtung folgt dem {@link BubbleSide}-Vertrag — er zeigt immer zur Mitte des Verlaufs. Innenabstand
 * und Kopfzeile entsprechen {@link SpeechBubblePanel}, damit beide Blasenarten gleich ausgerichtet sind.</p>
 */
public final class AssistantMarkdownBubble extends JPanel implements WidthAwareHeight, TranscriptBubble {

    private static final int ARC = 22;
    private static final int TAIL_WIDTH = 16;
    private static final int HORIZONTAL_PADDING = 15;
    private static final int VERTICAL_PADDING = 11;
    private static final int BODY_GAP = 3;

    private final BubbleSide side;
    private final Color bubbleColor;
    private final Color headerForeground;
    private final MarkdownMessageView body;
    private final JLabel headerLabel;
    private final JPanel headerRow = new JPanel();
    /** Rechter Teil der Kopfzeile: optionale Knöpfe, dann der Zeitstempel. */
    private final JPanel trailing = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    private JComponent trailingAccessory;
    private final boolean headerShown;
    private JLabel timestampLabel;

    public AssistantMarkdownBubble(BubbleSide side, BubblePalette palette, String header, MarkdownMessageView body) {
        if (side == null || palette == null || body == null) {
            throw new IllegalArgumentException("side, palette and body must not be null");
        }
        this.side = side;
        this.bubbleColor = palette.getAssistantBackground();
        this.headerForeground = palette.getAssistantForeground();
        this.body = body;
        setOpaque(false);
        setLayout(new BorderLayout(0, BODY_GAP));
        // Die Schwanzbreite auf der Seite reservieren, die den Schwanz trägt (die zur Mitte zeigende Innenkante).
        int left = HORIZONTAL_PADDING + (side.pointsLeft() ? TAIL_WIDTH : 0);
        int right = HORIZONTAL_PADDING + (side.pointsRight() ? TAIL_WIDTH : 0);
        setBorder(new EmptyBorder(VERTICAL_PADDING, left, VERTICAL_PADDING, right));
        this.headerLabel = createHeaderLabel(header, headerForeground);
        this.headerShown = headerLabel.getText().length() > 0;
        if (headerShown) {
            headerRow.setOpaque(false);
            // WEST/EAST statt Box mit Glue: der Zeitstempel sitzt rechts, egal wie breit die Zeile wirklich wird.
            headerRow.setLayout(new BorderLayout(6, 0));
            headerRow.add(headerLabel, BorderLayout.WEST);
            trailing.setOpaque(false);
            headerRow.add(trailing, BorderLayout.EAST);
            add(headerRow, BorderLayout.NORTH);
        }
        add(body, BorderLayout.CENTER);
    }

    /** Die Flächenfarbe der Blase (für Tests). */
    Color bubbleColor() {
        return bubbleColor;
    }

    /** Die Schriftfarbe der Kopfzeile (für Tests). */
    Color headerForeground() {
        return headerForeground;
    }

    /** Space Harvester: die Farbe der Kopfzeile, etwa rot für eine gescheiterte Antwort. */
    public void setHeaderColor(Color color) {
        headerLabel.setForeground(color == null ? headerForeground : color);
        headerLabel.repaint();
    }

    /** Der Markdown-Körper, den der Verlauf mit Deltas füttert. */
    public MarkdownMessageView view() {
        return body;
    }

    @Override
    public BubbleSide getSide() {
        return side;
    }

    /** Der Markdown-Quelltext der Nachricht, wie ihn der Verlauf gesetzt hat. */
    @Override
    public String getText() {
        return body.getMarkdown();
    }

    @Override
    public void setHeader(String header) {
        headerLabel.setText(header == null ? "" : header);
        headerLabel.revalidate();
        headerLabel.repaint();
    }

    /** Stempelt die Nachricht mit ihrer Erstellungszeit (gemeinsames Format und Tooltip; ohne Kopfzeile wirkungslos). */
    @Override
    public void setHeaderTimestamp(long epochMillis) {
        if (!headerShown) {
            return;
        }
        String full = BubbleTimestamps.tooltip(epochMillis);
        if (timestampLabel == null) {
            timestampLabel = new JLabel();
            Font base = headerLabel.getFont();
            timestampLabel.setFont(base.deriveFont(Font.PLAIN, Math.max(6f, base.getSize2D() * 0.5f)));
            trailing.add(timestampLabel);
        }
        timestampLabel.setForeground(withAlpha(headerForeground, 190));
        timestampLabel.setText(BubbleTimestamps.stackedHtml(epochMillis));
        timestampLabel.setToolTipText(full);
        headerLabel.setToolTipText(full);
        revalidate();
        repaint();
    }

    /**
     * Kleine Bedienelemente (QR- und Kopierknopf) am rechten Rand der Kopfzeile, direkt links vom Zeitstempel wie
     * {@code SpeechBubblePanel.setHeaderTrailingAccessory}; der Markdown-Körper bleibt unberührt. Ersetzt vorherige.
     */
    public void setHeaderTrailingAccessory(JComponent accessory) {
        if (!headerShown) {
            return;
        }
        if (trailingAccessory != null) {
            trailing.remove(trailingAccessory);
            trailing.remove(0); // die Lücke dahinter
        }
        trailingAccessory = accessory;
        if (accessory != null) {
            // nie höher als der Name: die Kopfzeile und damit die Blasenhöhe bleiben wie ohne Knopf
            java.awt.Dimension size = new java.awt.Dimension(accessory.getPreferredSize().width,
                    Math.min(accessory.getPreferredSize().height, headerLabel.getPreferredSize().height));
            accessory.setPreferredSize(size);
            accessory.setMaximumSize(size);
            trailing.add(accessory, 0);
            trailing.add(Box.createHorizontalStrut(4), 1);
        }
        revalidate();
        repaint();
    }

    /** Die richtige Höhe für eine feste Blasenbreite: Rahmen + optionale Kopfzeile + Markdown-Körper bei dieser Breite. */
    @Override
    public int preferredHeightForWidth(int width) {
        Insets insets = getInsets();
        int innerWidth = Math.max(1, width - insets.left - insets.right);
        int height = insets.top + insets.bottom + body.preferredHeightForWidth(innerWidth);
        if (headerShown) {
            height += Math.max(headerLabel.getPreferredSize().height,
                    headerRow.getPreferredSize().height) + BODY_GAP;
        }
        return height;
    }

    private static JLabel createHeaderLabel(String header, Color foreground) {
        JLabel label = new JLabel(header == null ? "" : header);
        Font baseFont = UIManager.getFont("Label.font");
        if (baseFont == null) {
            baseFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        }
        label.setFont(baseFont.deriveFont(Font.BOLD, Math.max(10f, baseFont.getSize2D() - 1f)));
        label.setForeground(withAlpha(foreground, 220));
        label.setOpaque(false);
        return label;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D copy = (Graphics2D) graphics.create();
        try {
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            copy.setComposite(AlphaComposite.SrcOver);
            copy.setColor(bubbleColor);
            copy.fill(createBubbleShape());
        } finally {
            copy.dispose();
        }
        super.paintComponent(graphics);
    }

    private Shape createBubbleShape() {
        int bodyX = side.pointsLeft() ? TAIL_WIDTH : 0;
        int bodyWidth = Math.max(1, getWidth() - TAIL_WIDTH);
        int bodyHeight = Math.max(1, getHeight());
        RoundRectangle2D bodyShape = new RoundRectangle2D.Float(bodyX, 0, bodyWidth, bodyHeight, ARC, ARC);

        Area shape = new Area(bodyShape);
        shape.add(new Area(buildTail(side, getWidth(), getHeight())));
        return shape;
    }

    /**
     * Das Schwanzdreieck für die Seite, zur Mitte des Verlaufs zeigend: eine linke Blase
     * ({@link BubbleSide#pointsRight()}) hat die Spitze an der rechten Innenkante, eine rechte Blase links.
     */
    static Path2D buildTail(BubbleSide side, int width, int height) {
        int centerY = Math.max(VERTICAL_PADDING + 13, height - 22);
        Path2D tail = new Path2D.Float();
        if (side.pointsRight()) {
            int baseX = width - TAIL_WIDTH - 2;
            tail.moveTo(baseX, centerY - 8);
            tail.lineTo(width - 1, centerY);
            tail.lineTo(baseX, centerY + 8);
        } else {
            int baseX = TAIL_WIDTH + 2;
            tail.moveTo(baseX, centerY - 8);
            tail.lineTo(1, centerY);
            tail.lineTo(baseX, centerY + 8);
        }
        tail.closePath();
        return tail;
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }
}
