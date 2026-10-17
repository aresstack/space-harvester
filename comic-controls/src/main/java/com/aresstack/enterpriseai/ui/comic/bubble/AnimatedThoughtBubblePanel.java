package com.aresstack.enterpriseai.ui.comic.bubble;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A reusable comic thought bubble with a rising-circle trail, a burst and a floating result summary. It
 * is theme-driven ({@link ThoughtBubbleTheme}) so the same drawing/animation renders as an amber
 * tool-activity bubble ({@link AgentActivityBubblePanel}) or a green assistant-thinking bubble
 * ({@link AssistantThinkingBubblePanel}) without duplicating any logic.
 *
 * <p>Direction rule: the rising circles always point toward the centre of the transcript — a left-side
 * bubble's circles rise to the right, a right-side bubble's to the left — and grow as they rise.</p>
 */
public class AnimatedThoughtBubblePanel extends JPanel {

    public enum VisualState {
        RUNNING,
        BURSTING,
        FLOATING_RESULT,
        FINISHED
    }

    public enum ResultKind {
        SUCCESS,
        FAILURE,
        CANCELLED
    }

    /**
     * Optional sink for the finished summary. When set, the burst hands the decorated summary off to be
     * animated elsewhere (e.g. a transcript-wide overlay that rises to the top edge) instead of the
     * in-row float. The bubble then finishes immediately so its row can be removed.
     */
    public interface SummaryFloatHandler {
        void floatSummary(AnimatedThoughtBubblePanel source, String text, Color accent, Font font);
    }

    private static final int CONNECTOR_SPACE = 58;
    private static final int ARC = 24;
    private static final int CONTENT_PADDING = 17;
    private static final int MINIMUM_HEIGHT = 104;
    private static final int DEFAULT_MAXIMUM_WIDTH = 600;
    private static final int TIMER_DELAY_MILLIS = 33;
    private static final long BURST_DURATION_MILLIS = 320L;
    private static final long FLOAT_DURATION_MILLIS = 1250L;
    private static final int FLOAT_DISTANCE = 54;

    private final BubbleSide side;
    private final ThoughtBubbleTheme theme;
    private final JLabel titleLabel;
    private final JTextArea explanationArea;
    private final Timer animationTimer;
    private final List<BurstParticle> burstParticles;

    private VisualState visualState;
    private long animationStartedAt;
    private long phaseStartedAt;
    private String resultSummary;
    private ResultKind resultKind;
    private Runnable completionListener;
    private int maximumBubbleWidth;
    private SummaryFloatHandler summaryFloatHandler;

    protected AnimatedThoughtBubblePanel(BubbleSide side, ThoughtBubbleTheme theme, String title,
                                         String explanation) {
        if (side == null) {
            throw new IllegalArgumentException("side must not be null");
        }
        if (theme == null) {
            throw new IllegalArgumentException("theme must not be null");
        }
        this.side = side;
        this.theme = theme;
        this.maximumBubbleWidth = DEFAULT_MAXIMUM_WIDTH;
        this.titleLabel = createTitleLabel(title);
        this.explanationArea = createExplanationArea(explanation);
        this.burstParticles = createBurstParticles();
        this.visualState = VisualState.RUNNING;
        this.animationStartedAt = System.currentTimeMillis();
        this.phaseStartedAt = animationStartedAt;
        this.animationTimer = createAnimationTimer();
        buildUi();
        animationTimer.start();
    }

    public BubbleSide getSide() {
        return side;
    }

    public VisualState getVisualState() {
        return visualState;
    }

    public String getTitle() {
        return titleLabel.getText();
    }

    public String getExplanation() {
        return explanationArea.getText();
    }

    public String getResultSummary() {
        return resultSummary;
    }

    public boolean isAnimationRunning() {
        return animationTimer.isRunning();
    }

    public void updateActivity(String title, String explanation) {
        if (visualState != VisualState.RUNNING) {
            return;
        }
        titleLabel.setText(normalize(title));
        applyExplanation(explanation);
        refreshLayout();
    }

    /**
     * The machine-readable progress marker an activity update may carry at its end:
     * {@code [[bar:current/total]]} — stripped from the visible text and rendered as the comic
     * progress bar below it (e.g. visited pages vs. relevant links of a web search).
     */
    private static final java.util.regex.Pattern BAR_MARKER =
            java.util.regex.Pattern.compile("\\s*\\[\\[bar:(\\d+)/(\\d+)\\]\\]\\s*");

    private void applyExplanation(String explanation) {
        String text = normalize(explanation);
        int current = -1;
        int total = -1;
        java.util.regex.Matcher marker = BAR_MARKER.matcher(text);
        if (marker.find()) {
            try {
                current = Integer.parseInt(marker.group(1));
                total = Integer.parseInt(marker.group(2));
            } catch (NumberFormatException impossibleByPattern) {
                current = -1;
                total = -1;
            }
            text = marker.replaceAll(" ").trim();
        }
        explanationArea.setText(text);
        progressStrip.update(current, total);
    }

    /** Appends a streamed delta to the bubble body (used to stream thinking text live). */
    public void appendBodyText(String delta) {
        if (visualState != VisualState.RUNNING || delta == null || delta.isEmpty()) {
            return;
        }
        explanationArea.setText(explanationArea.getText() + delta);
        refreshLayout();
    }

    public void completeSuccessfully(String summary, Runnable afterAnimation) {
        complete(ResultKind.SUCCESS, summary, afterAnimation);
    }

    public void completeWithFailure(String summary, Runnable afterAnimation) {
        complete(ResultKind.FAILURE, summary, afterAnimation);
    }

    public void cancel(String summary, Runnable afterAnimation) {
        complete(ResultKind.CANCELLED, summary, afterAnimation);
    }

    public void stopAnimation() {
        animationTimer.stop();
    }

    /** Route the finished summary to an external animator (e.g. a transcript overlay). */
    public void setSummaryFloatHandler(SummaryFloatHandler handler) {
        this.summaryFloatHandler = handler;
    }

    public void setMaximumBubbleWidth(int maximumBubbleWidth) {
        if (maximumBubbleWidth < 180) {
            throw new IllegalArgumentException("maximumBubbleWidth must be at least 180");
        }
        this.maximumBubbleWidth = maximumBubbleWidth;
        refreshLayout();
    }

    @Override
    public Dimension getPreferredSize() {
        // The REPORTED width comes from the natural text width against the configured maximum —
        // NEVER from the current layout width: feeding getWidth() into the reported width once made
        // an initially narrow layout measure narrow, report narrow, and stay narrow forever.
        int contentMaximumWidth = maximumBubbleWidth - CONNECTOR_SPACE - (CONTENT_PADDING * 2);
        int naturalWidth = calculateNaturalTextWidth();
        int reportedContentWidth = Math.max(180, Math.min(contentMaximumWidth, naturalWidth));
        // The HEIGHT is measured at the width the transcript really laid us out at (when known):
        // that is what keeps the bar/link rows BELOW a wrapped second line instead of on top of it.
        int measureWidth = reportedContentWidth;
        if (getWidth() > 0) {
            measureWidth = Math.max(180,
                    Math.min(reportedContentWidth, getWidth() - CONNECTOR_SPACE - (CONTENT_PADDING * 2)));
        }
        explanationArea.setSize(new Dimension(measureWidth, Short.MAX_VALUE));
        Dimension explanationSize = explanationArea.getPreferredSize();
        Dimension titleSize = headerRow.getPreferredSize();
        int south = southStack.isVisible() && southStack.getComponentCount() > 0
                ? southStack.getPreferredSize().height : 0;
        int width = Math.max(reportedContentWidth, titleSize.width)
                + (CONTENT_PADDING * 2)
                + CONNECTOR_SPACE;
        int height = CONTENT_PADDING * 2 + titleSize.height + 5 + explanationSize.height
                + (south > 0 ? south + 5 : 0);
        return new Dimension(Math.min(maximumBubbleWidth, width), Math.max(MINIMUM_HEIGHT, height));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D copy = (Graphics2D) graphics.create();
        try {
            applyQualityHints(copy);
            if (visualState == VisualState.RUNNING) {
                paintRunningActivity(copy);
            } else if (visualState == VisualState.BURSTING) {
                paintBurst(copy);
            } else if (visualState == VisualState.FLOATING_RESULT) {
                paintFloatingResult(copy);
            }
        } finally {
            copy.dispose();
        }
        super.paintComponent(graphics);
    }

    private void buildUi() {
        setOpaque(false);
        setLayout(new BorderLayout(0, 5));
        setBorder(createContentBorder());
        headerRow.setOpaque(false);
        // WEST/EAST instead of a box with glue: the stamp is right-aligned however wide the
        // transcript really lays this bubble out.
        headerRow.setLayout(new BorderLayout(6, 0));
        headerRow.add(titleLabel, BorderLayout.WEST);
        add(headerRow, BorderLayout.NORTH);
        add(explanationArea, BorderLayout.CENTER);
        southStack.setOpaque(false);
        southStack.setLayout(new javax.swing.BoxLayout(southStack, javax.swing.BoxLayout.Y_AXIS));
        southStack.add(progressStrip);
        add(southStack, BorderLayout.SOUTH);
        // The transcript may lay this bubble out NARROWER than the width the preferred size was
        // computed for; re-measuring on the real width is what keeps the bar/link rows BELOW the
        // wrapped text instead of over it.
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent event) {
                revalidate();
            }
        });
        applyExplanation(explanationArea.getText());
    }

    /** Everything below the explanation: the progress bar. */
    private final JPanel southStack = new JPanel();
    /** Title + (optional) the shared stacked time/date stamp, pushed to the bubble's right edge. */
    private final JPanel headerRow = new JPanel();
    private JLabel timestampLabel;

    /** Stamp this activity with its creation time — same format and tooltip as every other bubble. */
    public void setHeaderTimestamp(long epochMillis) {
        String full = BubbleTimestamps.tooltip(epochMillis);
        if (timestampLabel == null) {
            timestampLabel = new JLabel();
            Font base = titleLabel.getFont();
            timestampLabel.setFont(base.deriveFont(Font.PLAIN, Math.max(6f, base.getSize2D() * 0.5f)));
            headerRow.add(timestampLabel, BorderLayout.EAST);
        }
        timestampLabel.setForeground(withAlpha(theme.getForeground(), 190));
        timestampLabel.setText(BubbleTimestamps.stackedHtml(epochMillis));
        timestampLabel.setToolTipText(full);
        titleLabel.setToolTipText(full);
        refreshLayout();
    }

    /** The comic progress bar under the explanation — visible only while an update names a ratio. */
    private final ProgressBarStrip progressStrip = new ProgressBarStrip();

    private final class ProgressBarStrip extends JPanel {
        private int current = -1;
        private int total = -1;

        ProgressBarStrip() {
            setOpaque(false);
            setVisible(false);
        }

        void update(int current, int total) {
            this.current = current;
            this.total = total;
            boolean show = total > 0;
            if (show != isVisible()) {
                setVisible(show);
                revalidate();
            }
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(10, 18);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (total <= 0) {
                return;
            }
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                applyQualityHints(copy);
                int barHeight = 11;
                int y = (getHeight() - barHeight) / 2;
                // Room for the "5/16" caption riding at the bar's right end.
                copy.setFont(resolveResultFont().deriveFont(11f));
                FontMetrics metrics = copy.getFontMetrics();
                String caption = current + "/" + total;
                int captionWidth = metrics.stringWidth(caption);
                int trackWidth = Math.max(24, getWidth() - captionWidth - 12);
                double ratio = Math.max(0.0d, Math.min(1.0d, current / (double) total));

                RoundRectangle2D track = new RoundRectangle2D.Double(
                        1, y, trackWidth, barHeight, barHeight, barHeight);
                copy.setColor(withAlpha(theme.getAccent(), 55));
                copy.fill(track);
                int fillWidth = (int) Math.round(trackWidth * ratio);
                if (fillWidth > 0) {
                    java.awt.Shape oldClip = copy.getClip();
                    copy.clip(track);
                    copy.setColor(withAlpha(theme.getAccent(), 210));
                    copy.fill(new RoundRectangle2D.Double(1, y, fillWidth, barHeight,
                            barHeight, barHeight));
                    copy.setClip(oldClip);
                }
                // The bold comic outline is what makes it a drawn bar, not a widget.
                copy.setStroke(new BasicStroke(1.6f));
                copy.setColor(withAlpha(theme.getForeground(), 180));
                copy.draw(track);
                copy.setColor(theme.getForeground());
                copy.drawString(caption, trackWidth + 8,
                        y + barHeight - (barHeight - metrics.getAscent() + 2) / 2);
            } finally {
                copy.dispose();
            }
        }
    }

    private JLabel createTitleLabel(String title) {
        JLabel label = new JLabel(normalize(title));
        Font font = UIManager.getFont("Label.font");
        if (font == null) {
            font = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        }
        label.setFont(font.deriveFont(Font.BOLD, Math.max(12f, font.getSize2D())));
        label.setForeground(theme.getForeground());
        label.setOpaque(false);
        return label;
    }

    private JTextArea createExplanationArea(String explanation) {
        JTextArea area = new JTextArea(normalize(explanation));
        Font font = UIManager.getFont("TextArea.font");
        if (font == null) {
            font = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        }
        area.setFont(font.deriveFont(Font.PLAIN, Math.max(13f, font.getSize2D())));
        area.setForeground(theme.getForeground());
        area.setCaretColor(theme.getForeground());
        area.setSelectionColor(withAlpha(theme.getAccent(), 90));
        area.setSelectedTextColor(theme.getForeground());
        area.setOpaque(false);
        area.setEditable(false);
        area.setFocusable(true);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(null);
        return area;
    }

    private EmptyBorder createContentBorder() {
        // Reserve the connector margin on the transcript-centre side of the cloud (a left bubble keeps its
        // free strip on the right), so the rising thought-trail lives in that strip beside the cloud.
        int left = CONTENT_PADDING + (side.pointsLeft() ? CONNECTOR_SPACE : 0);
        int right = CONTENT_PADDING + (side.pointsRight() ? CONNECTOR_SPACE : 0);
        return new EmptyBorder(CONTENT_PADDING, left, CONTENT_PADDING, right);
    }

    private Timer createAnimationTimer() {
        return new Timer(TIMER_DELAY_MILLIS, new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                advanceAnimation(System.currentTimeMillis());
            }
        });
    }

    private void complete(ResultKind kind, String summary, Runnable afterAnimation) {
        if (visualState != VisualState.RUNNING) {
            return;
        }
        resultKind = kind == null ? ResultKind.SUCCESS : kind;
        resultSummary = normalize(summary);
        completionListener = afterAnimation;
        visualState = VisualState.BURSTING;
        phaseStartedAt = System.currentTimeMillis();
        headerRow.setVisible(false);
        explanationArea.setVisible(false);
        southStack.setVisible(false);
        if (!animationTimer.isRunning()) {
            animationTimer.start();
        }
        repaint();
    }

    private void advanceAnimation(long now) {
        if (visualState == VisualState.RUNNING) {
            repaint();
            return;
        }
        if (visualState == VisualState.BURSTING
                && now - phaseStartedAt >= BURST_DURATION_MILLIS) {
            if (summaryFloatHandler != null) {
                // Hand the finished summary to the overlay (rises over everything to the top edge) while
                // the bubble still knows its own position, then finish so this row is removed.
                summaryFloatHandler.floatSummary(this, decorateSummary(resultSummary),
                        resolveResultAccent(), resolveResultFont());
                finishAnimation();
                return;
            }
            visualState = VisualState.FLOATING_RESULT;
            phaseStartedAt = now;
            repaint();
            return;
        }
        if (visualState == VisualState.FLOATING_RESULT
                && now - phaseStartedAt >= FLOAT_DURATION_MILLIS) {
            finishAnimation();
            return;
        }
        repaint();
    }

    private void finishAnimation() {
        visualState = VisualState.FINISHED;
        animationTimer.stop();
        repaint();
        Runnable listener = completionListener;
        completionListener = null;
        if (listener != null) {
            listener.run();
        }
    }

    private void paintRunningActivity(Graphics2D graphics) {
        graphics.setColor(theme.getBackground());
        graphics.fill(createCloudShape(1.0d));
        paintActivityOutline(graphics, 1.0f);
        paintConnectorBubbles(graphics);
    }

    private void paintBurst(Graphics2D graphics) {
        double progress = phaseProgress(BURST_DURATION_MILLIS);
        double scale = 1.0d + (0.18d * easeOut(progress));
        float alpha = (float) Math.max(0.0d, 1.0d - progress);

        Graphics2D bubbleGraphics = (Graphics2D) graphics.create();
        try {
            bubbleGraphics.setComposite(AlphaComposite.SrcOver.derive(alpha));
            bubbleGraphics.setColor(resolveResultAccent());
            bubbleGraphics.fill(createCloudShape(scale));
            paintActivityOutline(bubbleGraphics, alpha);
        } finally {
            bubbleGraphics.dispose();
        }
        paintBurstParticles(graphics, progress);
    }

    private void paintFloatingResult(Graphics2D graphics) {
        double progress = phaseProgress(FLOAT_DURATION_MILLIS);
        double eased = easeOut(progress);
        float alpha = progress < 0.55d
                ? 1.0f
                : (float) Math.max(0.0d, 1.0d - ((progress - 0.55d) / 0.45d));
        int rise = (int) Math.round(FLOAT_DISTANCE * eased);

        Graphics2D resultGraphics = (Graphics2D) graphics.create();
        try {
            resultGraphics.setComposite(AlphaComposite.SrcOver.derive(alpha));
            resultGraphics.setFont(resolveResultFont());
            resultGraphics.setColor(resolveResultAccent());
            paintCenteredMultilineText(resultGraphics, decorateSummary(resultSummary), rise);
        } finally {
            resultGraphics.dispose();
        }
    }

    private void paintConnectorBubbles(Graphics2D graphics) {
        long elapsed = System.currentTimeMillis() - animationStartedAt;
        double cycle = (elapsed % 1200L) / 1200.0d;
        // The trail lives entirely inside the free connector strip beside the cloud (never over it) and flows
        // back toward the thinker: for a left-side (assistant) bubble that strip is on the right, so the dots
        // start at its outer bottom corner and rise up-and-inward toward the cloud edge — up and to the left,
        // higher on the bubble side. A right-side bubble mirrors it.
        int cloudEdge = side.pointsRight() ? getWidth() - CONNECTOR_SPACE : CONNECTOR_SPACE;
        int outward = side.pointsRight() ? 1 : -1;
        int baseY = Math.max(30, getHeight() - 22);

        for (int index = 0; index < 4; index++) {
            double progress = (cycle + (index * 0.24d)) % 1.0d;
            double eased = easeOut(progress);
            double radius = 3.0d + (5.0d * eased);
            // Offset shrinks as the dot rises: large near the outer edge at the bottom, small near the cloud
            // edge at the top — a diagonal up-and-inward (up-left for a left bubble), peaking at the cloud.
            double offset = 8.0d + (40.0d * (1.0d - eased));
            double x = cloudEdge + outward * offset;
            double y = baseY - (26.0d * eased) + Math.sin(progress * Math.PI) * 2.0d;
            float alpha = (float) (0.38d + (0.62d * (1.0d - progress)));

            graphics.setComposite(AlphaComposite.SrcOver.derive(alpha));
            graphics.setColor(theme.getAccent());
            graphics.fill(new Ellipse2D.Double(x - radius, y - radius, radius * 2.0d, radius * 2.0d));
        }
        graphics.setComposite(AlphaComposite.SrcOver);
    }

    private void paintBurstParticles(Graphics2D graphics, double progress) {
        int centerX = getBodyCenterX();
        int centerY = getHeight() / 2;
        float alpha = (float) Math.max(0.0d, 1.0d - progress);
        Color accent = resolveResultAccent();

        graphics.setComposite(AlphaComposite.SrcOver.derive(alpha));
        graphics.setColor(accent);
        for (int index = 0; index < burstParticles.size(); index++) {
            BurstParticle particle = burstParticles.get(index);
            double distance = particle.speed * easeOut(progress);
            double x = centerX + Math.cos(particle.angle) * distance;
            double y = centerY + Math.sin(particle.angle) * distance;
            double size = particle.size * (1.0d - (progress * 0.45d));
            graphics.fill(new Ellipse2D.Double(x - size, y - size, size * 2.0d, size * 2.0d));
        }
        graphics.setComposite(AlphaComposite.SrcOver);
    }

    private void paintActivityOutline(Graphics2D graphics, float alpha) {
        graphics.setComposite(AlphaComposite.SrcOver.derive(Math.min(alpha, 0.72f)));
        graphics.setStroke(new BasicStroke(1.2f));
        graphics.setColor(withAlpha(theme.getAccent(), 150));
        graphics.draw(createCloudShape(1.0d));
        graphics.setComposite(AlphaComposite.SrcOver);
    }

    private Area createCloudShape(double scale) {
        int bodyX = side.pointsLeft() ? CONNECTOR_SPACE : 0;
        int bodyWidth = Math.max(1, getWidth() - CONNECTOR_SPACE);
        int bodyHeight = Math.max(1, getHeight());
        double centerX = bodyX + (bodyWidth / 2.0d);
        double centerY = bodyHeight / 2.0d;

        Area cloud = new Area(new RoundRectangle2D.Double(
                bodyX + 3,
                8,
                Math.max(1, bodyWidth - 6),
                Math.max(1, bodyHeight - 16),
                ARC,
                ARC));
        cloud.add(new Area(new Ellipse2D.Double(bodyX + bodyWidth * 0.08d, 1, bodyWidth * 0.30d, 36)));
        cloud.add(new Area(new Ellipse2D.Double(bodyX + bodyWidth * 0.34d, -3, bodyWidth * 0.34d, 42)));
        cloud.add(new Area(new Ellipse2D.Double(bodyX + bodyWidth * 0.65d, 3, bodyWidth * 0.25d, 34)));

        if (Math.abs(scale - 1.0d) < 0.0001d) {
            return cloud;
        }
        AffineTransform transform = new AffineTransform();
        transform.translate(centerX, centerY);
        transform.scale(scale, scale);
        transform.translate(-centerX, -centerY);
        cloud.transform(transform);
        return cloud;
    }

    private void paintCenteredMultilineText(Graphics2D graphics, String text, int rise) {
        FontMetrics metrics = graphics.getFontMetrics();
        int bodyX = side.pointsLeft() ? CONNECTOR_SPACE : 0;
        int bodyWidth = getWidth() - CONNECTOR_SPACE;
        int maximumLineWidth = Math.max(80, bodyWidth - 24);
        List<String> lines = wrapText(text, metrics, maximumLineWidth);
        int lineHeight = metrics.getHeight();
        int blockHeight = lines.size() * lineHeight;
        int baseY = (getHeight() + blockHeight) / 2 - rise;

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int textWidth = metrics.stringWidth(line);
            int x = bodyX + (bodyWidth - textWidth) / 2;
            int y = baseY + (index * lineHeight);
            graphics.drawString(line, x, y);
        }
    }

    private List<String> wrapText(String text, FontMetrics metrics, int maximumWidth) {
        List<String> lines = new ArrayList<String>();
        String[] words = normalize(text).split("\\s+");
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < words.length; index++) {
            String candidate = current.length() == 0
                    ? words[index]
                    : current.toString() + " " + words[index];
            if (current.length() > 0 && metrics.stringWidth(candidate) > maximumWidth) {
                lines.add(current.toString());
                current.setLength(0);
                current.append(words[index]);
            } else {
                current.setLength(0);
                current.append(candidate);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }

    private int calculateNaturalTextWidth() {
        FontMetrics explanationMetrics = explanationArea.getFontMetrics(explanationArea.getFont());
        FontMetrics titleMetrics = titleLabel.getFontMetrics(titleLabel.getFont());
        int maximum = titleMetrics.stringWidth(titleLabel.getText()) + 12;
        String[] lines = explanationArea.getText().split("\\n", -1);
        for (int index = 0; index < lines.length; index++) {
            maximum = Math.max(maximum, explanationMetrics.stringWidth(lines[index]) + 12);
        }
        return maximum;
    }

    private List<BurstParticle> createBurstParticles() {
        Random random = new Random(0xA55A17L);
        List<BurstParticle> particles = new ArrayList<BurstParticle>();
        for (int index = 0; index < 18; index++) {
            double angle = (Math.PI * 2.0d * index / 18.0d) + ((random.nextDouble() - 0.5d) * 0.22d);
            double speed = 24.0d + (random.nextDouble() * 42.0d);
            double size = 2.5d + (random.nextDouble() * 4.5d);
            particles.add(new BurstParticle(angle, speed, size));
        }
        return particles;
    }

    private Font resolveResultFont() {
        Font font = UIManager.getFont("Label.font");
        if (font == null) {
            font = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        }
        return font.deriveFont(Font.BOLD, Math.max(13f, font.getSize2D() + 1f));
    }

    private Color resolveResultAccent() {
        return resultKind == ResultKind.FAILURE ? theme.getFailureAccent() : theme.getAccent();
    }

    /** Ohne Unicode-Symbole: Fehler und Abbruch tragen ein Wort, Erfolg zeigt die Akzentfarbe. */
    private String decorateSummary(String summary) {
        if (resultKind == ResultKind.FAILURE) {
            return "Fehler: " + summary;
        }
        if (resultKind == ResultKind.CANCELLED) {
            return "Abgebrochen: " + summary;
        }
        return summary;
    }

    private int getBodyCenterX() {
        int bodyX = side.pointsLeft() ? CONNECTOR_SPACE : 0;
        int bodyWidth = getWidth() - CONNECTOR_SPACE;
        return bodyX + (bodyWidth / 2);
    }

    private double phaseProgress(long durationMillis) {
        long elapsed = Math.max(0L, System.currentTimeMillis() - phaseStartedAt);
        return Math.min(1.0d, elapsed / (double) durationMillis);
    }

    private static double easeOut(double progress) {
        double inverse = 1.0d - progress;
        return 1.0d - (inverse * inverse * inverse);
    }

    private void refreshLayout() {
        revalidate();
        repaint();
    }

    private static void applyQualityHints(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }

    private static final class BurstParticle {
        private final double angle;
        private final double speed;
        private final double size;

        private BurstParticle(double angle, double speed, double size) {
            this.angle = angle;
            this.speed = speed;
            this.size = size;
        }
    }
}
