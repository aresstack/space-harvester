package com.aresstack.enterpriseai.ui.comic.control;

import com.aresstack.enterpriseai.ui.comic.theme.ComicPalette;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFormattedTextField;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SwingConstants;
import javax.swing.plaf.basic.BasicSliderUI;
import javax.swing.plaf.basic.BasicSpinnerUI;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/**
 * Comic look for the small form controls (check box, slider, number spinner): ink contours, a calm white plate at
 * rest, the yellow action accent on engagement and the navigation blue for the chosen value. Behavior stays the
 * plain Swing control's; only painting changes. Use the {@code install} methods instead of styling by hand.
 */
public final class ComicFormControls {

    private ComicFormControls() {
    }

    /** A check box with the comic box glyph: ink contour, yellow plate with an ink tick when selected. */
    public static void install(JCheckBox box, ComicPalette palette) {
        requireArguments(box, palette);
        CheckIcon icon = new CheckIcon(palette);
        box.setIcon(icon);
        box.setSelectedIcon(icon);
        box.setRolloverIcon(icon);
        box.setRolloverSelectedIcon(icon);
        box.setPressedIcon(icon);
        box.setDisabledIcon(icon);
        box.setDisabledSelectedIcon(icon);
        box.setRolloverEnabled(true);
        box.setOpaque(false);
        box.setFocusPainted(false);
        box.setIconTextGap(6);
        box.setForeground(palette.getInk());
    }

    /** A slider with a rounded ink track, the chosen part blue, a round yellow knob and ink ticks and labels. */
    public static void install(JSlider slider, ComicPalette palette) {
        requireArguments(slider, palette);
        slider.setUI(new SliderUI(slider, palette));
        slider.setOpaque(false);
        slider.setForeground(palette.getInk());
        slider.setFocusable(true);
    }

    /** A number spinner with a rounded ink contour and comic arrow buttons. */
    public static void install(JSpinner spinner, ComicPalette palette) {
        requireArguments(spinner, palette);
        spinner.setUI(new SpinnerUI(palette));
        spinner.setOpaque(false);
        spinner.setBorder(new RoundBorder(palette));
        JComponent editor = spinner.getEditor();
        editor.setOpaque(false);
        if (editor instanceof JSpinner.DefaultEditor) {
            JFormattedTextField text = ((JSpinner.DefaultEditor) editor).getTextField();
            text.setOpaque(false);
            text.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 2));
            text.setForeground(palette.getInk());
            text.setCaretColor(palette.getInk());
            text.setHorizontalAlignment(SwingConstants.RIGHT);
        }
    }

    /**
     * A two-state button as a comic pill: white plate with ink contour at rest, the navigation blue with white text
     * when selected, the yellow accent while hovered. The caller sets the text of each state.
     */
    public static void install(javax.swing.JToggleButton toggle, final ComicPalette palette) {
        requireArguments(toggle, palette);
        toggle.setUI(new javax.swing.plaf.basic.BasicToggleButtonUI() {
            @Override
            public void paint(Graphics g, JComponent c) {
                AbstractButton button = (AbstractButton) c;
                Graphics2D g2 = smooth(g);
                try {
                    boolean hovered = button.getModel().isRollover() || button.getModel().isArmed();
                    RoundRectangle2D pill = new RoundRectangle2D.Float(1, 1, c.getWidth() - 2, c.getHeight() - 2,
                            c.getHeight() - 2, c.getHeight() - 2);
                    g2.setColor(!c.isEnabled() ? palette.getSurface() : button.isSelected()
                            ? palette.getNavigationBlue() : hovered ? palette.getAccentYellow() : Color.WHITE);
                    g2.fill(pill);
                    g2.setColor(c.isEnabled() ? palette.getInk() : muted(palette));
                    g2.setStroke(new BasicStroke(2f));
                    g2.draw(pill);
                } finally {
                    g2.dispose();
                }
                c.setForeground(button.isSelected() && c.isEnabled() ? Color.WHITE : palette.getInk());
                super.paint(g, c);
            }
        });
        toggle.setOpaque(false);
        toggle.setContentAreaFilled(false);
        toggle.setBorderPainted(false);
        toggle.setFocusPainted(false);
        toggle.setRolloverEnabled(true);
        toggle.setBorder(BorderFactory.createEmptyBorder(3, 12, 3, 12));
        toggle.setForeground(palette.getInk());
    }

    private static void requireArguments(Object control, ComicPalette palette) {
        if (control == null || palette == null) {
            throw new IllegalArgumentException("control and palette must not be null");
        }
    }

    private static Graphics2D smooth(Graphics graphics) {
        Graphics2D g2 = (Graphics2D) graphics.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g2;
    }

    private static Color muted(ComicPalette palette) {
        Color ink = palette.getInk();
        return new Color(ink.getRed(), ink.getGreen(), ink.getBlue(), 110);
    }

    /** The 16×16 comic check box glyph. */
    static final class CheckIcon implements Icon {

        private static final int SIZE = 16;
        private final ComicPalette palette;

        CheckIcon(ComicPalette palette) {
            this.palette = palette;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            boolean selected = false;
            boolean hovered = false;
            boolean enabled = component == null || component.isEnabled();
            if (component instanceof AbstractButton) {
                AbstractButton button = (AbstractButton) component;
                selected = button.isSelected();
                hovered = button.getModel().isRollover() || button.getModel().isArmed();
            }
            Graphics2D g2 = smooth(graphics);
            try {
                g2.translate(x, y);
                RoundRectangle2D box = new RoundRectangle2D.Float(1.5f, 1.5f, SIZE - 3, SIZE - 3, 7, 7);
                g2.setColor(!enabled ? palette.getSurface() : selected ? palette.getAccentYellow()
                        : hovered ? lighter(palette.getAccentYellow()) : Color.WHITE);
                g2.fill(box);
                g2.setColor(enabled ? palette.getInk() : muted(palette));
                g2.setStroke(new BasicStroke(2f));
                g2.draw(box);
                if (selected) {
                    g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawPolyline(new int[]{4, 7, 12}, new int[]{8, 11, 4}, 3);
                }
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }

        private static Color lighter(Color color) {
            return new Color((color.getRed() + 255 * 2) / 3, (color.getGreen() + 255 * 2) / 3,
                    (color.getBlue() + 255 * 2) / 3);
        }
    }

    /** The comic slider delegate. */
    static final class SliderUI extends BasicSliderUI {

        private static final int TRACK = 6;
        private static final int KNOB = 14;
        private final ComicPalette palette;

        SliderUI(JSlider slider, ComicPalette palette) {
            super(slider);
            this.palette = palette;
        }

        @Override
        protected Dimension getThumbSize() {
            return new Dimension(KNOB + 2, KNOB + 2);
        }

        @Override
        public void paintFocus(Graphics g) {
            // the knob shows focus itself
        }

        @Override
        public void paintTrack(Graphics g) {
            Graphics2D g2 = smooth(g);
            try {
                Rectangle track = trackRect;
                boolean horizontal = slider.getOrientation() == JSlider.HORIZONTAL;
                float x = horizontal ? track.x : track.x + (track.width - TRACK) / 2f;
                float y = horizontal ? track.y + (track.height - TRACK) / 2f : track.y;
                float w = horizontal ? track.width : TRACK;
                float h = horizontal ? TRACK : track.height;
                RoundRectangle2D bar = new RoundRectangle2D.Float(x, y, w, h, TRACK, TRACK);
                g2.setColor(Color.WHITE);
                g2.fill(bar);
                if (slider.isEnabled()) {
                    g2.setColor(palette.getNavigationBlue());
                    if (horizontal) {
                        float filled = thumbRect.x + thumbRect.width / 2f - x;
                        g2.fill(new RoundRectangle2D.Float(x, y, Math.max(0f, filled), h, TRACK, TRACK));
                    } else {
                        float top = thumbRect.y + thumbRect.height / 2f;
                        g2.fill(new RoundRectangle2D.Float(x, top, w, Math.max(0f, y + h - top), TRACK, TRACK));
                    }
                }
                g2.setColor(slider.isEnabled() ? palette.getInk() : muted(palette));
                g2.setStroke(new BasicStroke(1.6f));
                g2.draw(bar);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public void paintThumb(Graphics g) {
            Graphics2D g2 = smooth(g);
            try {
                int x = thumbRect.x + (thumbRect.width - KNOB) / 2;
                int y = thumbRect.y + (thumbRect.height - KNOB) / 2;
                boolean engaged = isDragging() || slider.hasFocus();
                g2.setColor(!slider.isEnabled() ? palette.getSurface()
                        : engaged ? palette.getAccentOrange() : palette.getAccentYellow());
                g2.fillOval(x, y, KNOB, KNOB);
                g2.setColor(slider.isEnabled() ? palette.getInk() : muted(palette));
                g2.setStroke(new BasicStroke(2f));
                g2.drawOval(x, y, KNOB, KNOB);
            } finally {
                g2.dispose();
            }
        }

        @Override
        protected void paintMinorTickForHorizSlider(Graphics g, Rectangle tickBounds, int x) {
            g.setColor(muted(palette));
            g.drawLine(x, 1, x, getTickLength() / 2);
        }

        @Override
        protected void paintMajorTickForHorizSlider(Graphics g, Rectangle tickBounds, int x) {
            Graphics2D g2 = smooth(g);
            try {
                g2.setColor(palette.getInk());
                g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(x, 1, x, getTickLength() - 1);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            Graphics2D g2 = smooth(g);
            try {
                super.paint(g2, c);
            } finally {
                g2.dispose();
            }
        }
    }

    /** The comic spinner delegate: chevron buttons on the white plate. */
    static final class SpinnerUI extends BasicSpinnerUI {

        private final ComicPalette palette;

        SpinnerUI(ComicPalette palette) {
            this.palette = palette;
        }

        @Override
        protected Component createNextButton() {
            Component button = new ArrowButton(palette, true);
            installNextButtonListeners(button);
            return button;
        }

        @Override
        protected Component createPreviousButton() {
            Component button = new ArrowButton(palette, false);
            installPreviousButtonListeners(button);
            return button;
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            Graphics2D g2 = smooth(g);
            try {
                g2.setColor(c.isEnabled() ? Color.WHITE : palette.getSurface());
                g2.fill(new RoundRectangle2D.Float(1, 1, c.getWidth() - 2, c.getHeight() - 2, 10, 10));
            } finally {
                g2.dispose();
            }
            super.paint(g, c);
        }
    }

    /** One spinner arrow: an ink chevron that warms up yellow under the pointer. */
    static final class ArrowButton extends JButton {

        private final ComicPalette palette;
        private final boolean up;

        ArrowButton(ComicPalette palette, boolean up) {
            this.palette = palette;
            this.up = up;
            setName(up ? "Spinner.nextButton" : "Spinner.previousButton");
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setFocusable(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setBorder(BorderFactory.createEmptyBorder());
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(14, 9);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = smooth(g);
            try {
                int w = getWidth();
                int h = getHeight();
                if (isEnabled() && (getModel().isRollover() || getModel().isPressed())) {
                    g2.setColor(getModel().isPressed() ? palette.getAccentOrange() : palette.getAccentYellow());
                    g2.fillRoundRect(1, 1, w - 3, h - 2, 6, 6);
                }
                g2.setColor(isEnabled() ? palette.getInk() : muted(palette));
                g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int cx = w / 2 - 1;
                int cy = h / 2;
                if (up) {
                    g2.drawPolyline(new int[]{cx - 3, cx, cx + 3}, new int[]{cy + 2, cy - 1, cy + 2}, 3);
                } else {
                    g2.drawPolyline(new int[]{cx - 3, cx, cx + 3}, new int[]{cy - 2, cy + 1, cy - 2}, 3);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    /** Rounded 2px ink contour around a spinner. */
    static final class RoundBorder implements javax.swing.border.Border {

        private final ComicPalette palette;

        RoundBorder(ComicPalette palette) {
            this.palette = palette;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Graphics2D g2 = smooth(g);
            try {
                g2.setColor(c.isEnabled() ? palette.getInk() : muted(palette));
                g2.setStroke(new BasicStroke(2f));
                g2.draw(new RoundRectangle2D.Float(x + 1, y + 1, width - 2, height - 2, 10, 10));
            } finally {
                g2.dispose();
            }
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(3, 3, 3, 4);
        }

        @Override
        public boolean isBorderOpaque() {
            return false;
        }
    }
}
