package com.aresstack.enterpriseai.ui.comic.control;

import org.junit.Test;

import javax.swing.JMenu;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The comic hover menu must be a PLAIN look-and-feel menu until the mouse enters it, and paint its
 * impact accent only while hovered (issue #36, test 1).
 */
public class ComicHoverMenuTest {

    @Test
    public void hoverStateFollowsMouseEnterAndExit() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ComicHoverMenu menu = new ComicHoverMenu("Datei");
                assertFalse("no hover accent before any mouse contact", menu.isComicHoverActive());

                menu.dispatchEvent(mouseEvent(menu, MouseEvent.MOUSE_ENTERED));
                assertTrue("entering activates the accent", menu.isComicHoverActive());

                menu.dispatchEvent(mouseEvent(menu, MouseEvent.MOUSE_EXITED));
                assertFalse("exiting returns to the plain look immediately",
                        menu.isComicHoverActive());
            }
        });
    }

    @Test
    public void normalStateKeepsTheLookAndFeelFontAndTextColor() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ComicHoverMenu comic = new ComicHoverMenu("Chat");
                JMenu plain = new JMenu("Chat");
                assertEquals("same menu font as a plain JMenu",
                        plain.getFont(), comic.getFont());
                assertEquals("same text color as a plain JMenu",
                        plain.getForeground(), comic.getForeground());
            }
        });
    }

    @Test
    public void impactColorsAppearOnlyWhileHovered() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ComicHoverMenu menu = new ComicHoverMenu("Models");
                menu.setSize(140, 28);

                assertFalse("plain state paints no warm impact colors",
                        containsWarmImpactPixel(render(menu)));

                menu.dispatchEvent(mouseEvent(menu, MouseEvent.MOUSE_ENTERED));
                assertTrue("hover paints the yellow/orange/red impact plate",
                        containsWarmImpactPixel(render(menu)));

                menu.dispatchEvent(mouseEvent(menu, MouseEvent.MOUSE_EXITED));
                assertFalse("after exit the plate is gone again",
                        containsWarmImpactPixel(render(menu)));
            }
        });
    }

    @Test
    public void theImpactPlateStaysWhileTheMenuIsOpen() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ComicHoverMenu menu = new ComicHoverMenu("Models");
                menu.setSize(140, 28);

                // Clicking opens the popup: the mouse leaves the title but the menu is SELECTED —
                // the comic style must NOT vanish (the original complaint about the first cut).
                menu.setSelected(true);
                menu.dispatchEvent(mouseEvent(menu, MouseEvent.MOUSE_EXITED));
                assertTrue(menu.isComicPaintActive());
                assertTrue("an open menu keeps its comic plate",
                        containsWarmImpactPixel(render(menu)));

                menu.setSelected(false);
                assertFalse(menu.isComicPaintActive());
                assertFalse("closing returns to the plain look",
                        containsWarmImpactPixel(render(menu)));
            }
        });
    }

    @Test
    public void dropdownItemsGetTheComicSelectionButStayNormalItems() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ComicHoverMenu menu = new ComicHoverMenu("Configuration");
                javax.swing.JMenuItem item = new javax.swing.JMenuItem("Connections");
                java.awt.Font before = item.getFont();
                menu.add(item);

                assertTrue("added items carry the comic selection UI",
                        item.getUI() instanceof ComicMenuItemUI);
                assertEquals("the item keeps its normal font — no burst control", before,
                        item.getFont());
                assertTrue("the popup carries the comic ink contour",
                        menu.getPopupMenu().getBorder()
                                instanceof com.aresstack.enterpriseai.ui.comic.border.ComicBorder);
            }
        });
    }

    private static BufferedImage render(ComicHoverMenu menu) {
        BufferedImage image = new BufferedImage(menu.getWidth(), menu.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        try {
            menu.paint(g2);
        } finally {
            g2.dispose();
        }
        return image;
    }

    /** Warm = clearly yellow/orange/red-ish: the palette's impact family, never plain menu text. */
    private static boolean containsWarmImpactPixel(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                int red = (argb >> 16) & 0xFF;
                int green = (argb >> 8) & 0xFF;
                int blue = argb & 0xFF;
                if (alpha > 200 && red > 180 && blue < 130 && red - blue > 80) {
                    return true;
                }
            }
        }
        return false;
    }

    private static MouseEvent mouseEvent(ComicHoverMenu menu, int id) {
        return new MouseEvent(menu, id, System.currentTimeMillis(), 0, 5, 5, 0, false);
    }

    private static void onEdt(Runnable runnable) throws Exception {
        try {
            SwingUtilities.invokeAndWait(runnable);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException) {
                throw (RuntimeException) ex.getCause();
            }
            if (ex.getCause() instanceof Error) {
                throw (Error) ex.getCause();
            }
            throw ex;
        }
    }
}
