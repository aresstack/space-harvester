package com.aresstack.enterpriseai.ui.comic.control;

import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** AskAI's composer buttons: flat at rest, a filled accent plate when primary/emphasized/selected. */
public class ComposerButtonTest {

    @Test
    public void primaryPaintsItsAccentPlateWithWhiteText() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ComposerButton send = ComposerButton.primary(ComposerIcons.send(), "Senden",
                        ResearchUiPalette.ACCENT_BLUE, "Senden");
                assertTrue("primary buttons keep a usable minimum width", send.getPreferredSize().width >= 72);
                assertTrue(send.getPreferredSize().height >= 28);
                send.setSize(90, 28);
                BufferedImage image = render(send);
                assertEquals(ResearchUiPalette.ACCENT_BLUE.getRGB() & 0xFFFFFF, image.getRGB(10, 14) & 0xFFFFFF);
                assertEquals(Color.WHITE, send.getForeground());
                assertTrue("reachable with Tab", send.isFocusable());
                assertFalse("a click leaves the focus in the editor", send.isRequestFocusEnabled());
                assertFalse("the ring below replaces the LaF focus paint", send.isFocusPainted());
            }
        });
    }

    @Test
    public void flatButtonsArePlainUntilEmphasized() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ComposerButton burger = ComposerButton.sidebarToggle("Menü");
                assertEquals(30, burger.getPreferredSize().width);
                assertEquals(28, burger.getPreferredSize().height);
                burger.setSize(30, 28);
                BufferedImage rest = render(burger);
                assertEquals("transparent at rest", 0, (rest.getRGB(3, 14) >>> 24));

                burger.setEmphasized(true);
                BufferedImage latched = render(burger);
                assertEquals("latched: the dark secondary surface", ResearchUiPalette.SECONDARY_SURFACE.getRGB() & 0xFFFFFF,
                        latched.getRGB(3, 14) & 0xFFFFFF);
            }
        });
    }

    @Test
    public void theToggleFillsWhileSelected() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ComposerToggleButton rag = new ComposerToggleButton(null, "RAG", "Wissensbasis");
                rag.setSize(44, 28);
                assertEquals(0, render(rag).getRGB(3, 14) >>> 24);
                rag.setSelected(true);
                assertEquals(ResearchUiPalette.ACCENT_BLUE.getRGB() & 0xFFFFFF, render(rag).getRGB(3, 14) & 0xFFFFFF);
                assertEquals(Color.WHITE, rag.getForeground());
            }
        });
    }

    private static BufferedImage render(javax.swing.JComponent component) {
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        try {
            component.paint(g2);
        } finally {
            g2.dispose();
        }
        return image;
    }
}
