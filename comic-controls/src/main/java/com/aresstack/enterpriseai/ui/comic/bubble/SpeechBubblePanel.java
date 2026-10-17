package com.aresstack.enterpriseai.ui.comic.bubble;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Render one selectable, streamable chat message inside a painted speech bubble.
 *
 * <p>Keep the text as a real Swing text component so copy and selection continue to work. Paint
 * only the bubble chrome with {@link Graphics2D}.</p>
 *
 * <p>Implements {@link WidthAwareHeight} so the transcript
 * rows can ask for the exact wrapped height at the final bubble width — before that, long
 * unbroken texts were measured unwrapped once and rendered as a single clipped line.</p>
 *
 * <p>A bubble may carry optional DETAILS ({@link #setDetails}): a short main text keeps the bubble
 * narrow, and a small toggle inside the bubble unfolds the longer explanation (error causes, hints)
 * within the same geometry — never a full-width banner.</p>
 */
public final class SpeechBubblePanel extends JPanel
        implements WidthAwareHeight, WidthBoundedBubble, TranscriptBubble {

    private static final int ARC = 22;
    private static final int TAIL_WIDTH = 16;
    private static final int HORIZONTAL_PADDING = 15;
    private static final int VERTICAL_PADDING = 11;
    /**
     * Only the fallback for a bubble used OUTSIDE a transcript row (showcases, standalone rendering). Inside
     * a row the row supplies the limit through {@link WidthBoundedBubble}, because only it knows how much
     * width the chat has. A fixed pixel cap must never be what decides the width: at 580 every message
     * stayed in a narrow column, and any other constant would just move the same problem to a wider window.
     */
    private static final int DEFAULT_MAXIMUM_WIDTH = 1200;
    private static final int MINIMUM_WIDTH = 104;
    /** Unfolded details never ask for more than this; longer lines wrap inside the bubble. */
    private static final int DETAILS_READING_WIDTH = 520;

    private final BubbleSide side;
    private final Color bubbleColor;
    private final Color textColor;
    private final JLabel headerLabel;
    private static final int ACCESSORY_GAP = 4;
    private JComponent headerAccessory;
    private final JPanel headerRow;
    private JLabel timestampLabel; // small stacked date/time next to the name, or null
    private final JTextArea textArea;
    private final JPanel detailsBlock;
    private final JButton detailsToggle;
    private final JTextArea detailsArea;
    private boolean detailsExpanded;
    private String showDetailsLabel = "Details";
    private String hideDetailsLabel = "Details";
    private int maximumBubbleWidth;

    // Fortgeschriebene Messung des Textes (Breite, Umbruch): Streaming hängt Deltas an, und die Messung je
    // Layout darf dann nicht mit der Textlänge wachsen.
    private final StreamingTextMeasure measure;

    public SpeechBubblePanel(BubbleSide side,
                             Color bubbleColor,
                             Color textColor,
                             String header,
                             String text) {
        if (side == null) {
            throw new IllegalArgumentException("side must not be null");
        }
        if (bubbleColor == null) {
            throw new IllegalArgumentException("bubbleColor must not be null");
        }
        if (textColor == null) {
            throw new IllegalArgumentException("textColor must not be null");
        }
        this.side = side;
        this.bubbleColor = bubbleColor;
        this.textColor = textColor;
        this.maximumBubbleWidth = DEFAULT_MAXIMUM_WIDTH;
        this.headerLabel = createHeaderLabel(header);
        this.headerRow = new JPanel();
        this.textArea = createTextArea(text);
        this.detailsArea = createDetailsArea();
        this.detailsToggle = createDetailsToggle();
        this.detailsBlock = createDetailsBlock();
        this.measure = new StreamingTextMeasure(textArea.getFontMetrics(textArea.getFont()));
        measure.set(normalize(text));
        buildUi();
    }

    /** Die Flächenfarbe der Blase. */
    public Color getBubbleColor() {
        return bubbleColor;
    }

    /** Die Schriftfarbe von Text, Kopfzeile, Zeitstempel und Details. */
    public Color getTextColor() {
        return textColor;
    }

    public BubbleSide getSide() {
        return side;
    }

    public String getText() {
        return textArea.getText();
    }

    public void setText(String text) {
        String value = normalize(text);
        textArea.setText(value);
        measure.set(value);
        refreshLayout();
    }

    /**
     * Hängt ein Streaming-Delta an. Der Aufwand hängt von der Länge des Deltas und des letzten, noch
     * unvollständigen Wortes ab, nicht vom gesamten Text: das Dokument wird ergänzt statt ersetzt, und die
     * Messungen für Breite und Umbruch werden fortgeschrieben ({@link StreamingTextMeasure}).
     */
    public void appendText(String delta) {
        if (delta == null || delta.length() == 0) {
            return;
        }
        textArea.append(delta);
        measure.append(delta);
        refreshLayout();
    }

    public void setHeader(String header) {
        headerLabel.setText(normalize(header));
        headerLabel.setVisible(headerLabel.getText().length() > 0);
        headerRow.setVisible(headerLabel.isVisible());
        refreshLayout();
    }

    /**
     * Attach (or with {@code null}/blank remove) a folded explanation under the main text. The
     * bubble keeps the geometry of its SHORT main text; the details unfold inside it on demand.
     */
    public void setDetails(String details) {
        String value = details == null ? "" : details.trim();
        detailsArea.setText(value);
        detailsBlock.setVisible(value.length() > 0);
        if (value.length() == 0) {
            detailsExpanded = false;
        }
        applyDetailsState();
        refreshLayout();
    }

    /** The toggle captions, e.g. "Details anzeigen" / "Details ausblenden". */
    public void setDetailsLabels(String showLabel, String hideLabel) {
        this.showDetailsLabel = normalize(showLabel);
        this.hideDetailsLabel = normalize(hideLabel);
        applyDetailsState();
        refreshLayout();
    }

    public String getDetails() {
        return detailsArea.getText();
    }

    public boolean hasDetails() {
        return detailsBlock.isVisible();
    }

    public boolean isDetailsExpanded() {
        return hasDetails() && detailsExpanded;
    }

    public void setDetailsExpanded(boolean expanded) {
        if (!hasDetails() || detailsExpanded == expanded) {
            return;
        }
        detailsExpanded = expanded;
        applyDetailsState();
        refreshLayout();
    }

    /** The in-bubble toggle (for tests and keyboard wiring); hidden without details. */
    public AbstractButton detailsToggle() {
        return detailsToggle;
    }

    private void applyDetailsState() {
        detailsArea.setVisible(isDetailsExpanded());
        detailsToggle.setText((isDetailsExpanded() ? hideDetailsLabel : showDetailsLabel)
                + (isDetailsExpanded() ? " \u25B4" : " \u25BE"));
    }

    /**
     * Colors the header label (the sender name), used for per-participant colors in Partying
     * mode.  {@code null} restores the default muted text color.
     */
    public void setHeaderColor(Color color) {
        headerLabel.setForeground(color != null ? color : withAlpha(textColor, 220));
        if (timestampLabel != null) {
            timestampLabel.setForeground(timestampColor());
        }
        repaint();
    }

    /** The timestamp always follows the name color (first line), just dimmed. */
    private Color timestampColor() {
        Color header = headerLabel.getForeground();
        return withAlpha(header != null ? header : textColor, 165);
    }

    /**
     * Shows the message creation time next to the sender name: date over time, squeezed into two
     * half-size lines no taller than the name itself.
     */
    public void setHeaderTimestamp(long epochMillis) {
        // ONE timestamp format for every mode (see BubbleTimestamps) — Partying introduced it,
        // Questing reuses it verbatim.
        String full = BubbleTimestamps.tooltip(epochMillis);
        if (timestampLabel == null) {
            timestampLabel = new JLabel();
            Font base = headerLabel.getFont();
            timestampLabel.setFont(base.deriveFont(Font.PLAIN, Math.max(6f, base.getSize2D() * 0.5f)));
            timestampLabel.setAlignmentY(BOTTOM_ALIGNMENT);
            // A minimum gap, then glue so the time/date block is pushed to the bubble's right edge.
            headerRow.add(javax.swing.Box.createHorizontalStrut(6));
            headerRow.add(javax.swing.Box.createHorizontalGlue());
            headerRow.add(timestampLabel);
        }
        timestampLabel.setForeground(timestampColor());
        timestampLabel.setText(BubbleTimestamps.stackedHtml(epochMillis));
        // A shared, readable tooltip on the whole header so hovering the name or the stamp shows it.
        headerLabel.setToolTipText(full);
        timestampLabel.setToolTipText(full);
        setToolTipText(full);
        // Cap the two stacked lines to the username's height; pad the width so the year is never
        // clipped (HTML labels tend to under-measure their preferred width by a pixel or two).
        Dimension pref = timestampLabel.getPreferredSize();
        Dimension cap = new Dimension(pref.width + 4, headerLabel.getPreferredSize().height);
        timestampLabel.setPreferredSize(cap);
        timestampLabel.setMaximumSize(cap);
        refreshLayout();
    }

    /**
     * Places a small control (e.g. a copy button) right after the sender name in the header row; it never
     * touches the text area, so selecting text and clicking links stay as they are. Replaces a previous one.
     */
    public void setHeaderAccessory(JComponent accessory) {
        if (headerAccessory != null) {
            int index = headerRow.getComponentZOrder(headerAccessory);
            headerRow.remove(headerAccessory);
            if (index > 0) {
                headerRow.remove(index - 1); // the gap in front of it
            }
        }
        headerAccessory = accessory;
        if (accessory != null) {
            // never taller than the name: the header (and so the bubble height) stays as without it
            Dimension size = new Dimension(accessory.getPreferredSize().width,
                    Math.min(accessory.getPreferredSize().height, headerLabel.getPreferredSize().height));
            accessory.setPreferredSize(size);
            accessory.setMaximumSize(size);
            accessory.setAlignmentY(BOTTOM_ALIGNMENT);
            int index = headerRow.getComponentZOrder(headerLabel) + 1;
            headerRow.add(javax.swing.Box.createHorizontalStrut(ACCESSORY_GAP), index);
            headerRow.add(accessory, index + 1);
        }
        refreshLayout();
    }

    /** Header block size: the name plus the optional accessory and timestamp, no taller than its parts. */
    private Dimension headerBlockSize() {
        Dimension label = headerLabel.getPreferredSize();
        int width = label.width;
        int height = label.height;
        if (headerAccessory != null) {
            Dimension accessory = headerAccessory.getPreferredSize();
            width += ACCESSORY_GAP + accessory.width;
            height = Math.max(height, accessory.height);
        }
        if (timestampLabel != null) {
            width += 6 + timestampLabel.getPreferredSize().width + 4;
        }
        return new Dimension(width, height);
    }

    public void setMaximumBubbleWidth(int maximumBubbleWidth) {
        if (maximumBubbleWidth < MINIMUM_WIDTH) {
            throw new IllegalArgumentException("maximumBubbleWidth must be at least " + MINIMUM_WIDTH);
        }
        this.maximumBubbleWidth = maximumBubbleWidth;
        refreshLayout();
    }

    /**
     * The width this bubble wants within a limit the CALLER supplies — the transcript row's share of the
     * chat width. A short message still asks for its natural width; only a long one grows into the limit.
     */
    @Override
    public int preferredWidthWithin(int limit) {
        int allowed = Math.max(MINIMUM_WIDTH, limit);
        int contentMaximumWidth = allowed - TAIL_WIDTH - (HORIZONTAL_PADDING * 2);
        int naturalWidth = calculateNaturalTextWidth();
        int contentWidth = Math.max(72, Math.min(contentMaximumWidth, naturalWidth));
        int headerWidth = headerLabel.isVisible() ? headerBlockSize().width : 0;
        int width = Math.max(contentWidth, headerWidth) + (HORIZONTAL_PADDING * 2) + TAIL_WIDTH;
        int preferred = Math.max(MINIMUM_WIDTH, Math.min(allowed, width));
        // The contract is "never exceeding limit": below the comfortable minimum the caller's limit still wins.
        return limit > 0 ? Math.min(limit, preferred) : preferred;
    }

    @Override
    public Dimension getPreferredSize() {
        int contentMaximumWidth = maximumBubbleWidth
                - TAIL_WIDTH
                - (HORIZONTAL_PADDING * 2);
        int naturalWidth = calculateNaturalTextWidth();
        int contentWidth = Math.max(72, Math.min(contentMaximumWidth, naturalWidth));

        textArea.setSize(new Dimension(contentWidth, Short.MAX_VALUE));
        Dimension textSize = textArea.getPreferredSize();
        Dimension headerSize = headerLabel.isVisible()
                ? headerBlockSize()
                : new Dimension(0, 0);

        int width = Math.max(textSize.width, headerSize.width)
                + (HORIZONTAL_PADDING * 2)
                + TAIL_WIDTH;
        width = Math.max(MINIMUM_WIDTH, Math.min(maximumBubbleWidth, width));

        int height = VERTICAL_PADDING * 2 + textSize.height;
        if (headerLabel.isVisible()) {
            height += headerSize.height + 3;
        }
        height += detailsHeight(contentWidth);
        return new Dimension(width, Math.max(48, height));
    }

    /**
     * Deterministic height for a fixed bubble width: paddings + optional header + the text wrapped
     * at exactly the inner width.  Uses the larger of the text view's measurement and a
     * font-metrics greedy-wrap estimate, so a stale unwrapped view measurement can never produce
     * a one-line bubble for a long text.
     */
    @Override
    public int preferredHeightForWidth(int width) {
        Insets insets = getInsets();
        int innerWidth = Math.max(24, width - insets.left - insets.right);
        textArea.setSize(new Dimension(innerWidth, Short.MAX_VALUE));
        int viewHeight = textArea.getPreferredSize().height;
        int metricsHeight = estimateWrappedTextHeight(innerWidth);
        int height = insets.top + insets.bottom + Math.max(viewHeight, metricsHeight);
        if (headerLabel.isVisible()) {
            height += headerBlockSize().height + 3;
        }
        height += detailsHeight(innerWidth);
        return Math.max(48, height);
    }

    /** The folded/unfolded details block height at an inner width (0 without details). */
    private int detailsHeight(int innerWidth) {
        if (!hasDetails()) {
            return 0;
        }
        int height = 3 + detailsToggle.getPreferredSize().height;
        if (isDetailsExpanded()) {
            detailsArea.setSize(new Dimension(Math.max(24, innerWidth), Short.MAX_VALUE));
            height += 2 + detailsArea.getPreferredSize().height;
        }
        return height;
    }

    /** Greedy word-wrap line count from font metrics — independent of the Swing view state. */
    private int estimateWrappedTextHeight(int innerWidth) {
        FontMetrics metrics = textArea.getFontMetrics(textArea.getFont());
        return Math.max(1, measure.wrappedLines(innerWidth)) * metrics.getHeight();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D copy = (Graphics2D) graphics.create();
        try {
            applyQualityHints(copy);
            copy.setComposite(AlphaComposite.SrcOver);
            copy.setColor(bubbleColor);
            copy.fill(createBubbleShape());
        } finally {
            copy.dispose();
        }
        super.paintComponent(graphics);
    }

    private void buildUi() {
        setOpaque(false);
        setLayout(new BorderLayout(0, 3));
        setBorder(createContentBorder());
        headerRow.setLayout(new javax.swing.BoxLayout(headerRow, javax.swing.BoxLayout.X_AXIS));
        headerRow.setOpaque(false);
        headerLabel.setAlignmentY(BOTTOM_ALIGNMENT);
        headerRow.add(headerLabel);
        headerRow.setVisible(headerLabel.isVisible());
        add(headerRow, BorderLayout.NORTH);
        add(textArea, BorderLayout.CENTER);
        add(detailsBlock, BorderLayout.SOUTH);
    }

    private JTextArea createDetailsArea() {
        JTextArea area = createTextArea("");
        area.setFont(area.getFont().deriveFont(Math.max(11f, area.getFont().getSize2D() - 1.5f)));
        area.setForeground(withAlpha(textColor, 225));
        area.setVisible(false);
        return area;
    }

    private JButton createDetailsToggle() {
        JButton toggle = new JButton();
        toggle.setFont(headerLabel.getFont());
        toggle.setForeground(withAlpha(textColor, 220));
        toggle.setFocusable(true); // per Tab erreichbar (Leertaste klappt auf) …
        toggle.setRequestFocusEnabled(false); // … ein Mausklick nimmt dem Editor den Fokus aber nicht weg
        toggle.setBorderPainted(false);
        toggle.setContentAreaFilled(false);
        toggle.setOpaque(false);
        toggle.setMargin(new Insets(0, 0, 0, 0));
        toggle.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
        toggle.setHorizontalAlignment(JButton.LEFT);
        toggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        toggle.setAlignmentX(LEFT_ALIGNMENT);
        toggle.addActionListener(event -> setDetailsExpanded(!isDetailsExpanded()));
        return toggle;
    }

    private JPanel createDetailsBlock() {
        JPanel block = new JPanel();
        block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
        block.setOpaque(false);
        block.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));
        detailsArea.setAlignmentX(LEFT_ALIGNMENT);
        block.add(detailsToggle);
        block.add(detailsArea);
        block.setVisible(false);
        return block;
    }

    private JLabel createHeaderLabel(String header) {
        JLabel label = new JLabel(normalize(header));
        Font baseFont = UIManager.getFont("Label.font");
        if (baseFont == null) {
            baseFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        }
        label.setFont(baseFont.deriveFont(Font.BOLD, Math.max(10f, baseFont.getSize2D() - 1f)));
        label.setForeground(withAlpha(textColor, 220));
        label.setOpaque(false);
        label.setVisible(label.getText().length() > 0);
        return label;
    }

    private JTextArea createTextArea(String text) {
        JTextArea area = new JTextArea(normalize(text));
        Font baseFont = UIManager.getFont("TextArea.font");
        if (baseFont == null) {
            baseFont = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        }
        area.setFont(baseFont.deriveFont(Font.PLAIN, Math.max(13f, baseFont.getSize2D())));
        area.setForeground(textColor);
        area.setCaretColor(textColor);
        area.setSelectionColor(withAlpha(textColor, 90));
        area.setSelectedTextColor(textColor);
        area.setOpaque(false);
        area.setEditable(false);
        area.setFocusable(true);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(BorderFactory.createEmptyBorder());
        return area;
    }

    private EmptyBorder createContentBorder() {
        int left = HORIZONTAL_PADDING + (side.pointsLeft() ? TAIL_WIDTH : 0);
        int right = HORIZONTAL_PADDING + (side.pointsRight() ? TAIL_WIDTH : 0);
        return new EmptyBorder(VERTICAL_PADDING, left, VERTICAL_PADDING, right);
    }

    private java.awt.Shape createBubbleShape() {
        Insets insets = getInsets();
        int bodyX = side.pointsLeft() ? TAIL_WIDTH : 0;
        int bodyWidth = Math.max(1, getWidth() - TAIL_WIDTH);
        int bodyHeight = Math.max(1, getHeight());
        RoundRectangle2D body = new RoundRectangle2D.Float(
                bodyX,
                0,
                bodyWidth,
                bodyHeight,
                ARC,
                ARC);

        Path2D tail = new Path2D.Float();
        int centerY = Math.max(insets.top + 13, getHeight() - 22);
        if (side.pointsRight()) {
            int baseX = getWidth() - TAIL_WIDTH - 2;
            tail.moveTo(baseX, centerY - 8);
            tail.lineTo(getWidth() - 1, centerY);
            tail.lineTo(baseX, centerY + 8);
        } else {
            int baseX = TAIL_WIDTH + 2;
            tail.moveTo(baseX, centerY - 8);
            tail.lineTo(1, centerY);
            tail.lineTo(baseX, centerY + 8);
        }
        tail.closePath();

        java.awt.geom.Area shape = new java.awt.geom.Area(body);
        shape.add(new java.awt.geom.Area(tail));
        return shape;
    }

    private int calculateNaturalTextWidth() {
        int maximum = Math.max(72, measure.naturalWidth() + 8);
        if (headerLabel.isVisible()) {
            FontMetrics headerMetrics = headerLabel.getFontMetrics(headerLabel.getFont());
            int headerWidth = headerMetrics.stringWidth(headerLabel.getText())
                    + (timestampLabel != null ? 6 + timestampLabel.getPreferredSize().width : 0);
            maximum = Math.max(maximum, headerWidth + 8);
        }
        if (hasDetails()) {
            maximum = Math.max(maximum, detailsToggle.getPreferredSize().width + 8);
            if (isDetailsExpanded()) {
                // Unfolded details may widen the bubble a little, but stay a bubble: the widest line
                // counts only up to a comfortable reading width; longer lines wrap.
                FontMetrics metrics = detailsArea.getFontMetrics(detailsArea.getFont());
                int widest = 0;
                for (String line : detailsArea.getText().split("\n")) {
                    widest = Math.max(widest, metrics.stringWidth(line));
                }
                maximum = Math.max(maximum, Math.min(DETAILS_READING_WIDTH, widest + 8));
            }
        }
        return maximum;
    }

    private void refreshLayout() {
        revalidate();
        repaint();
    }

    private static void applyQualityHints(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }
}
