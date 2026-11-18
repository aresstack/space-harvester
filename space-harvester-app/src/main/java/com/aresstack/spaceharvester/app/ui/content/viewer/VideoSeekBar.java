package com.aresstack.spaceharvester.app.ui.content.viewer;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Locale;
import java.util.function.DoubleConsumer;

/**
 * Full-width, timeline-style video scrubber inspired by Audio Studio's waveform navigation.
 * Preview drag positions locally; commit only once on release to avoid flooding the BSCW range source.
 */
final class VideoSeekBar extends JComponent {

    private static final int INSET = 14;
    private static final int TRACK_TOP = 13;
    private static final int TRACK_HEIGHT = 7;
    private static final Color TRACK = new Color(0x303C4B);
    private static final Color PLAYED = new Color(0x68BEDD);
    private static final Color LABEL = new Color(0xC9D6E1);

    private final DoubleConsumer seek;
    private long durationMillis;
    private long playbackMillis;
    private double playbackShare;
    private boolean positionKnown;
    private boolean dragging;
    private double dragShare;

    VideoSeekBar(DoubleConsumer seek) {
        this.seek = seek;
        setPreferredSize(new Dimension(680, 48));
        setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 12));
        setMinimumSize(new Dimension(140, 48));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        putClientProperty(javax.accessibility.AccessibleContext.ACCESSIBLE_NAME_PROPERTY, "Videoposition");
        setToolTipText(" ");
        setFocusable(true);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (!canSeek() || !javax.swing.SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }
                requestFocusInWindow();
                dragging = true;
                preview(event.getX());
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (dragging) {
                    preview(event.getX());
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (!dragging) {
                    return;
                }
                preview(event.getX());
                dragging = false;
                seek.accept(dragShare);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        bindKey("LEFT", -5000);
        bindKey("RIGHT", 5000);
    }

    void update(long elapsedMillis, long totalMillis) {
        durationMillis = Math.max(0, totalMillis);
        playbackMillis = Math.max(0, Math.min(durationMillis, elapsedMillis));
        playbackShare = durationMillis > 0 ? playbackMillis / (double) durationMillis : 0;
        positionKnown = durationMillis > 0;
        setEnabled(positionKnown);
        repaint();
    }

    /** Use VLC's normalized progress when a stream has not exposed its duration yet. */
    void updateFraction(double share) {
        if (!Double.isFinite(share) || share < 0 || share > 1) {
            return;
        }
        positionKnown = true;
        playbackShare = share;
        setEnabled(true);
        repaint();
    }

    void reset() {
        dragging = false;
        playbackMillis = 0;
        playbackShare = 0;
        positionKnown = false;
        durationMillis = 0;
        setEnabled(false);
        repaint();
    }

    boolean isDragging() {
        return dragging;
    }

    boolean canSeek() {
        return isEnabled() && positionKnown;
    }

    long durationMillis() {
        return durationMillis;
    }

    long displayedMillis() {
        return durationMillis <= 0 ? -1
                : dragging ? Math.round(dragShare * durationMillis) : playbackMillis;
    }

    static String clock(long milliseconds) {
        if (milliseconds < 0) {
            return "--:--";
        }
        long seconds = milliseconds / 1000;
        long hours = seconds / 3600;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60);
        }
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        if (!canSeek()) {
            return "Die Videolänge wird ermittelt";
        }
        return durationMillis > 0 ? clock(Math.round(shareAt(event.getX()) * durationMillis))
                : Math.round(shareAt(event.getX()) * 100) + " %";
    }

    private void preview(int x) {
        dragShare = shareAt(x);
        repaint();
    }

    private double shareAt(int x) {
        return Math.max(0d, Math.min(1d, (x - INSET) / (double) Math.max(1, getWidth() - 2 * INSET)));
    }

    private void bindKey(String key, long millis) {
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), key);
        getActionMap().put(key, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                if (!canSeek()) {
                    return;
                }
                if (durationMillis > 0) {
                    long next = Math.max(0, Math.min(durationMillis, playbackMillis + millis));
                    seek.accept(next / (double) durationMillis);
                } else {
                    seek.accept(Math.max(0, Math.min(1, playbackShare + millis / 500000d)));
                }
            }
        });
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int span = Math.max(1, getWidth() - 2 * INSET);
            double share = dragging ? dragShare : playbackShare;
            share = Math.max(0, Math.min(1, share));
            g.setColor(TRACK);
            g.fill(new RoundRectangle2D.Double(INSET, TRACK_TOP, span, TRACK_HEIGHT, 8, 8));
            int playedWidth = (int) Math.round(span * share);
            if (playedWidth > 0) {
                g.setColor(PLAYED);
                g.fill(new RoundRectangle2D.Double(INSET, TRACK_TOP, playedWidth, TRACK_HEIGHT, 8, 8));
            }
            for (int tick = 1; tick < 4; tick++) {
                int x = INSET + span * tick / 4;
                g.setColor(new Color(0x748494));
                g.fillRect(x, TRACK_TOP + 2, 1, 3);
            }
            if (canSeek()) {
                int x = INSET + playedWidth;
                g.setColor(new Color(0x15212A));
                g.setStroke(new BasicStroke(2f));
                g.fill(new Ellipse2D.Double(x - 6, TRACK_TOP - 3, 12, 12));
                g.setColor(Color.WHITE);
                g.fill(new Ellipse2D.Double(x - 4, TRACK_TOP - 1, 8, 8));
            }
            g.setColor(LABEL);
            g.setFont(getFont().deriveFont(12f));
            FontMetrics metrics = g.getFontMetrics();
            String played = clock(displayedMillis());
            String end = durationMillis > 0 ? clock(durationMillis) : "--:--";
            g.drawString(played, INSET, 39);
            g.drawString(end, Math.max(INSET, getWidth() - INSET - metrics.stringWidth(end)), 39);
        } finally {
            g.dispose();
        }
    }
}
