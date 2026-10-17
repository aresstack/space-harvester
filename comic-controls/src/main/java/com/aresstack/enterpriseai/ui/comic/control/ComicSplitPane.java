package com.aresstack.enterpriseai.ui.comic.control;

import com.aresstack.enterpriseai.ui.comic.theme.ComicPalette;

import javax.swing.JSplitPane;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

/**
 * A horizontal split pane that hides the standard look-and-feel splitter behind the comic design
 * language: a quiet divider with a dark ink line and a small yellow grip pill. It OWNS the left
 * side's width policy — the left component may be collapsed to zero (divider disappears entirely)
 * or opened at a remembered width that is always clamped into {@code [minLeftWidth, maxLeftWidth]},
 * including while the user drags the divider.
 *
 * <p>The pane deliberately zeroes the left component's minimum size: without that, a collapse to
 * width 0 would fight the component's own layout minimum.</p>
 */
public class ComicSplitPane extends JSplitPane {

    /** Notified with the clamped width whenever the USER moves the divider (not on open/collapse). */
    public interface LeftWidthListener {
        void leftWidthChanged(int width);
    }

    private static final int DIVIDER_THICKNESS = 7;

    private final int minLeftWidth;
    private final int maxLeftWidth;
    private final ComicPalette palette;
    private boolean leftCollapsed;
    private boolean adjusting;
    private int preferredLeftWidth;
    private LeftWidthListener leftWidthListener;

    public ComicSplitPane(Component left, Component right, int minLeftWidth, int maxLeftWidth) {
        this(left, right, minLeftWidth, maxLeftWidth, ComicPalette.defaultPalette());
    }

    public ComicSplitPane(Component left, Component right, int minLeftWidth, int maxLeftWidth,
                          ComicPalette palette) {
        super(HORIZONTAL_SPLIT, true, left, right);
        if (minLeftWidth <= 0 || maxLeftWidth < minLeftWidth) {
            throw new IllegalArgumentException(
                    "invalid width bounds: " + minLeftWidth + ".." + maxLeftWidth);
        }
        if (palette == null) {
            throw new IllegalArgumentException("palette must not be null");
        }
        this.minLeftWidth = minLeftWidth;
        this.maxLeftWidth = maxLeftWidth;
        this.palette = palette;
        this.preferredLeftWidth = minLeftWidth;
        left.setMinimumSize(new Dimension(0, 0));
        setResizeWeight(0); // window resizes go to the right (content) side
        setOneTouchExpandable(false);
        installComicDivider();
        // After setUI: installing the UI re-installs the look-and-feel's SplitPane.border (a shadow line
        // above both children); the comic pane has no frame of its own.
        setBorder(null);
        setDividerSize(DIVIDER_THICKNESS);
        setDividerLocation(preferredLeftWidth);
        addPropertyChangeListener(DIVIDER_LOCATION_PROPERTY, new PropertyChangeListener() {
            public void propertyChange(PropertyChangeEvent event) {
                onDividerMoved(((Integer) event.getNewValue()).intValue());
            }
        });
    }

    /** Collapse the left side completely: width 0 and NO divider — looks like a plain single pane. */
    public void collapseLeft() {
        leftCollapsed = true;
        adjusting = true;
        try {
            setDividerSize(0);
            setDividerLocation(0);
        } finally {
            adjusting = false;
        }
    }

    /** Re-open the left side at the remembered (clamped) width and bring the divider back. */
    public void openLeft() {
        leftCollapsed = false;
        adjusting = true;
        try {
            setDividerSize(DIVIDER_THICKNESS);
            setDividerLocation(clampLeftWidth(preferredLeftWidth));
        } finally {
            adjusting = false;
        }
    }

    public boolean isLeftCollapsed() {
        return leftCollapsed;
    }

    /** The width the left side opens at — updated by every user drag, clamped into the bounds. */
    public int getPreferredLeftWidth() {
        return preferredLeftWidth;
    }

    /** Set the remembered width (e.g. a persisted value); applies immediately when open. */
    public void setPreferredLeftWidth(int width) {
        preferredLeftWidth = clampLeftWidth(width);
        if (!leftCollapsed) {
            adjusting = true;
            try {
                setDividerLocation(preferredLeftWidth);
            } finally {
                adjusting = false;
            }
        }
    }

    public void setLeftWidthListener(LeftWidthListener listener) {
        this.leftWidthListener = listener;
    }

    public int getMinLeftWidth() {
        return minLeftWidth;
    }

    public int getMaxLeftWidth() {
        return maxLeftWidth;
    }

    /** Every user-driven divider move funnels through here: clamp, remember, notify. */
    private void onDividerMoved(int location) {
        if (adjusting || leftCollapsed) {
            return;
        }
        int clamped = clampLeftWidth(location);
        if (clamped != location) {
            adjusting = true;
            try {
                setDividerLocation(clamped);
            } finally {
                adjusting = false;
            }
        }
        preferredLeftWidth = clamped;
        if (leftWidthListener != null) {
            leftWidthListener.leftWidthChanged(clamped);
        }
    }

    private int clampLeftWidth(int width) {
        return Math.max(minLeftWidth, Math.min(maxLeftWidth, width));
    }

    private void installComicDivider() {
        final ComicPalette dividerPalette = palette;
        BasicSplitPaneUI comicUi = new BasicSplitPaneUI() {
            @Override
            public BasicSplitPaneDivider createDefaultDivider() {
                return new ComicDivider(this, dividerPalette);
            }
        };
        setUI(comicUi);
        comicUi.getDivider().setBorder(null);
    }

    /** The quiet comic divider: neutral fill, ink center line, small yellow grip pill. */
    private static final class ComicDivider extends BasicSplitPaneDivider {

        private final ComicPalette palette;

        ComicDivider(BasicSplitPaneUI ui, ComicPalette palette) {
            super(ui);
            this.palette = palette;
        }

        @Override
        public void paint(Graphics g) {
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                Color base = splitPane != null ? splitPane.getBackground() : getBackground();
                g2.setColor(base != null ? base : palette.getSurface());
                g2.fillRect(0, 0, width, height);
                int centerX = width / 2;
                g2.setColor(palette.getInk());
                g2.fillRoundRect(centerX - 1, 8, 2, Math.max(2, height - 16), 2, 2);
                int gripHeight = Math.min(36, height / 3);
                if (gripHeight > 10 && width >= 6) {
                    int gripY = (height - gripHeight) / 2;
                    g2.setColor(palette.getAccentYellow());
                    g2.fillRoundRect(centerX - 2, gripY, 5, gripHeight, 5, 5);
                    g2.setColor(palette.getInk());
                    g2.drawRoundRect(centerX - 2, gripY, 5, gripHeight, 5, 5);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
