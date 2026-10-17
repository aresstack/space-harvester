package com.aresstack.enterpriseai.ui.comic.control;

import com.aresstack.enterpriseai.ui.comic.theme.ComicPalette;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The comic button is calm at rest (white plate, ink contour, ink text) and shows its role's
 * accent only on engagement: yellow hover for ACTION, red for CRITICAL.
 */
public class ComicButtonTest {

    @Test
    public void restingButtonIsCalmAndHoverFillsTheActionAccent() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ComicButton button = new ComicButton("New chat");
                button.setSize(120, 30);
                assertEquals("text is ink", ComicPalette.defaultPalette().getInk(),
                        button.getForeground());
                assertFalse("no warm accent at rest", containsWarmPixel(render(button)));

                button.getModel().setRollover(true);
                assertTrue("hover fills the yellow action accent",
                        containsWarmPixel(render(button)));
            }
        });
    }

    @Test
    public void criticalButtonsHoverRedNotYellow() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                ComicButton button = new ComicButton("Delete all chats…",
                        ComicButton.Accent.CRITICAL);
                button.setSize(160, 30);
                button.getModel().setRollover(true);
                BufferedImage image = render(button);
                assertTrue("hover fills red", containsColorLike(image,
                        ComicPalette.defaultPalette().getAccentRed()));
                assertFalse("no yellow on a critical action", containsColorLike(image,
                        ComicPalette.defaultPalette().getAccentYellow()));
            }
        });
    }

    @Test
    public void keyboardFocusIsVisibleOnButtonsAndToggles() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                java.awt.Color blue = ComicPalette.defaultPalette().getNavigationBlue();
                ComicButton unfocused = new ComicButton("Senden");
                unfocused.setSize(100, 30);
                assertFalse("no ring without focus", containsColorLike(render(unfocused), blue));

                ComicButton focused = new ComicButton("Senden") {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public boolean isFocusOwner() {
                        return true;
                    }
                };
                focused.setSize(100, 30);
                assertTrue("focused button shows the blue ring", containsColorLike(render(focused), blue));

                ComicToggleButton toggle = new ComicToggleButton("RAG") {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public boolean isFocusOwner() {
                        return true;
                    }
                };
                toggle.setSize(80, 30);
                assertTrue("focused toggle shows the blue ring", containsColorLike(render(toggle), blue));
            }
        });
    }

    private static BufferedImage render(javax.swing.AbstractButton button) {
        BufferedImage image = new BufferedImage(button.getWidth(), button.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        try {
            button.paint(g2);
        } finally {
            g2.dispose();
        }
        return image;
    }

    private static boolean containsWarmPixel(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                int red = (argb >> 16) & 0xFF;
                int blue = argb & 0xFF;
                if (alpha > 200 && red > 180 && blue < 130 && red - blue > 80) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsColorLike(BufferedImage image, java.awt.Color color) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                if (((argb >>> 24) & 0xFF) > 200
                        && Math.abs(((argb >> 16) & 0xFF) - color.getRed()) < 12
                        && Math.abs(((argb >> 8) & 0xFF) - color.getGreen()) < 12
                        && Math.abs((argb & 0xFF) - color.getBlue()) < 12) {
                    return true;
                }
            }
        }
        return false;
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
