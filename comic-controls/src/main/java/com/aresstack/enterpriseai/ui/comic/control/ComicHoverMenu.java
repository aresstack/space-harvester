package com.aresstack.enterpriseai.ui.comic.control;

import com.aresstack.enterpriseai.ui.comic.border.ComicBorder;
import com.aresstack.enterpriseai.ui.comic.paint.ComicImpactPainter;
import com.aresstack.enterpriseai.ui.comic.theme.ComicPalette;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * A top-level menu with a comic-impact accent. In its normal state it is a plain look-and-feel
 * {@link JMenu} (same font, no permanent burst). The {@link ComicImpactPainter} plate appears
 * while the mouse hovers the title AND stays while the menu is open (selected) — clicking a menu
 * must not make the comic style vanish. Leaving/closing returns to the plain look immediately.
 *
 * <p>The dropdown carries the design language WITHOUT turning its entries into burst controls:
 * the popup gets a {@link ComicBorder} ink contour, and every added {@link JMenuItem} gets a
 * {@link ComicMenuItemUI} — normal font and layout, but yellow/ink selection instead of the
 * look and feel's default highlight.</p>
 */
public class ComicHoverMenu extends JMenu {

    /** Breathing room so the impact plate's points never crowd the title text. */
    private static final int EXTRA_TITLE_PADDING = 8;

    private final ComicPalette palette;
    private final ComicImpactPainter painter;
    private boolean hoverActive;

    public ComicHoverMenu(String text) {
        this(text, ComicPalette.defaultPalette());
    }

    public ComicHoverMenu(String text, ComicPalette palette) {
        super(text);
        if (palette == null) {
            throw new IllegalArgumentException("palette must not be null");
        }
        this.palette = palette;
        this.painter = new ComicImpactPainter(palette);
        // Non-opaque: the menu bar shows through in the normal state, and the comic plate can be
        // painted without fighting an opaque background fill.
        setOpaque(false);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                setComicHoverActive(true);
            }

            @Override
            public void mouseExited(MouseEvent event) {
                setComicHoverActive(false);
            }
        });
        installComicPopupStyle();
    }

    /** Whether the mouse currently hovers the menu title. */
    public boolean isComicHoverActive() {
        return hoverActive;
    }

    /** Whether the comic plate is painted right now: hovered OR open — never plain in between. */
    public boolean isComicPaintActive() {
        return hoverActive || isSelected();
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize();
        size.width += EXTRA_TITLE_PADDING;
        return size;
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (!isComicPaintActive()) {
            super.paintComponent(g); // plain look and feel, untouched
            return;
        }
        // Comic state: paint plate + title ourselves. Delegating to super would let the look and
        // feel's selection rectangle wipe the plate the moment the popup opens.
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            painter.paint(g2, getWidth(), getHeight());
            String title = getText() == null ? "" : getText();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(getFont());
            g2.setColor(palette.getInk());
            FontMetrics metrics = g2.getFontMetrics();
            int x = Math.max(0, (getWidth() - metrics.stringWidth(title)) / 2);
            int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.drawString(title, x, y);
        } finally {
            g2.dispose();
        }
    }

    /**
     * The dropdown keeps normal entries but continues the design language: ink contour around the
     * popup, yellow/ink selection on every item. A container listener catches EVERY way an entry
     * can arrive (add, insert, Action), so callers need no comic-specific wiring.
     */
    private void installComicPopupStyle() {
        JPopupMenu popup = getPopupMenu();
        popup.setBorder(ComicBorder.popupBorder(palette));
        popup.setBackground(palette.getSurface());
        popup.addContainerListener(new ContainerAdapter() {
            @Override
            public void componentAdded(ContainerEvent event) {
                applyComicItemStyle(event.getChild());
            }
        });
    }

    private void applyComicItemStyle(Component child) {
        if (child instanceof JMenu) { // submenu titles keep the language too (JMenu extends JMenuItem)
            ((JMenu) child).setUI(new ComicMenuUI(palette));
        } else if (child instanceof JMenuItem) {
            ((JMenuItem) child).setUI(new ComicMenuItemUI(palette));
        }
    }

    private void setComicHoverActive(boolean active) {
        if (hoverActive != active) {
            hoverActive = active;
            repaint();
        }
    }
}
