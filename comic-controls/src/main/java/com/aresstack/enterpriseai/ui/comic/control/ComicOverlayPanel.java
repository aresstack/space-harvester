package com.aresstack.enterpriseai.ui.comic.control;

import com.aresstack.enterpriseai.ui.comic.theme.ComicPalette;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;

/**
 * A comic OVERLAY: a dimmed backdrop with a centered rounded plate (ink contour) carrying a title
 * row and arbitrary content, closable via the round comic ✕ in the top-right corner (ink outline,
 * red on hover). The backdrop swallows all mouse events, so whatever lies underneath (e.g. the
 * chat transcript) is untouchable while the overlay is up. Meant to fill its host component — the
 * host adds it on a higher layer and removes it again through the close callback.
 */
public class ComicOverlayPanel extends JPanel {

    private static final int MARGIN = 24;

    private final ComicPalette palette;
    private final CloseButton closeButton;
    private final JPanel plate;
    private Dimension plateLimit;

    public ComicOverlayPanel(String title, JComponent content, Runnable closeAction) {
        this(title, content, closeAction, ComicPalette.defaultPalette());
    }

    public ComicOverlayPanel(String title, JComponent content, Runnable closeAction,
                             ComicPalette palette) {
        super(new GridBagLayout());
        if (content == null || closeAction == null || palette == null) {
            throw new IllegalArgumentException("content, closeAction and palette must not be null");
        }
        this.palette = palette;
        setOpaque(false);
        // Swallow every mouse event so the content BELOW the overlay is unreachable.
        addMouseListener(new MouseAdapter() { });
        addMouseMotionListener(new javax.swing.event.MouseInputAdapter() { });
        addMouseWheelListener(e -> { });

        plate = new ComicSectionPanel(palette);
        plate.setLayout(new BorderLayout(0, 6));
        plate.setBorder(BorderFactory.createEmptyBorder(10, 14, 12, 14));

        JLabel heading = new JLabel(title == null ? "" : title);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 13f));
        heading.setForeground(palette.getInk());
        JPanel titleRow = new JPanel(new BorderLayout(8, 0));
        titleRow.setOpaque(false);
        titleRow.add(heading, BorderLayout.CENTER);
        closeButton = new CloseButton(palette, closeAction);
        titleRow.add(closeButton, BorderLayout.EAST);
        plate.add(titleRow, BorderLayout.NORTH);
        plate.add(content, BorderLayout.CENTER);

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.fill = GridBagConstraints.BOTH;
        constraints.weightx = 1;
        constraints.weighty = 1;
        constraints.insets = new Insets(MARGIN, MARGIN, MARGIN, MARGIN);
        add(plate, constraints);
    }

    /**
     * Caps the plate at {@code limit} (centered, backdrop all around) when the overlay is larger, e.g. when it
     * covers a whole maximized window; {@code null} lets the plate fill the overlay minus the margin (default).
     */
    public void setPlateLimit(Dimension limit) {
        this.plateLimit = limit == null ? null : new Dimension(limit);
        revalidate();
    }

    @Override
    public void doLayout() {
        int horizontal = MARGIN;
        int vertical = MARGIN;
        if (plateLimit != null) {
            horizontal = Math.max(MARGIN, (getWidth() - plateLimit.width) / 2);
            vertical = Math.max(MARGIN, (getHeight() - plateLimit.height) / 2);
        }
        GridBagLayout layout = (GridBagLayout) getLayout();
        GridBagConstraints constraints = layout.getConstraints(plate);
        constraints.insets = new Insets(vertical, horizontal, vertical, horizontal);
        layout.setConstraints(plate, constraints);
        super.doLayout();
    }

    /** The overlay's ✕ (for tests and keyboard wiring). */
    public CloseButton closeButton() {
        return closeButton;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setColor(new Color(0, 0, 0, 110)); // the dimmed backdrop
            g2.fillRect(0, 0, getWidth(), getHeight());
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }

    /**
     * The overlay's ✕ — the very same chip as {@link ComicWindowCloseButton}, kept under its AskAI
     * name so overlay code reads as in the reference; it adds NO painting of its own.
     */
    public static final class CloseButton extends ComicWindowCloseButton {

        public CloseButton(ComicPalette palette, Runnable closeAction) {
            super(palette, closeAction);
        }
    }
}
