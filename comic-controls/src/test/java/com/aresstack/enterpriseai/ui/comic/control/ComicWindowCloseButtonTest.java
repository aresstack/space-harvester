package com.aresstack.enterpriseai.ui.comic.control;

import com.aresstack.enterpriseai.ui.comic.theme.ComicPalette;
import org.junit.Test;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The comic ✕: white chip with an ink ✕ at rest, the palette's red chip with a white ✕ while
 * hovered or pressed; the overlay's close button IS this chip, not a second painter.
 */
public class ComicWindowCloseButtonTest {

    private final ComicPalette palette = ComicPalette.defaultPalette();

    @Test
    public void paintsWhiteAtRestAndRedWhileHot() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                final AtomicInteger closed = new AtomicInteger();
                ComicWindowCloseButton button = new ComicWindowCloseButton(palette, closed::incrementAndGet);
                button.setSize(24, 24);
                assertEquals(24, button.getPreferredSize().width);
                assertFalse(button.isHot());
                BufferedImage rest = render(button);
                assertEquals("white chip at rest", 0xFFFFFF, rgb(rest, 6, 12));
                assertEquals("ink ✕ at rest", palette.getInk().getRGB() & 0xFFFFFF, rgb(rest, 12, 12));

                button.getModel().setRollover(true);
                assertTrue(button.isHot());
                BufferedImage hot = render(button);
                assertEquals("red chip while hovered", palette.getAccentRed().getRGB() & 0xFFFFFF, rgb(hot, 6, 12));
                assertEquals("white ✕ while hovered", 0xFFFFFF, rgb(hot, 12, 12));

                button.doClick();
                assertEquals(1, closed.get());
            }
        });
    }

    @Test
    public void theOverlayUsesTheSameChip() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                final int[] closed = {0};
                ComicOverlayPanel overlay = new ComicOverlayPanel("Titel", new JLabel("x"), () -> closed[0]++, palette);
                assertTrue(overlay.closeButton() instanceof ComicWindowCloseButton);
                assertSame(ComicOverlayPanel.CloseButton.class, overlay.closeButton().getClass());
                overlay.closeButton().doClick();
                assertEquals(1, closed[0]);
            }
        });
    }

    private static BufferedImage render(ComicWindowCloseButton button) {
        BufferedImage image = new BufferedImage(button.getWidth(), button.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        try {
            button.paint(g2);
        } finally {
            g2.dispose();
        }
        return image;
    }

    private static int rgb(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) & 0xFFFFFF;
    }
}
