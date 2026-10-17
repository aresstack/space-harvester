package com.aresstack.enterpriseai.ui.comic.control;

import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;

/** "+ Neuer Chat" pressed shows the application's accent blue, not the old purple of the design study. */
public class ResearchPillButtonTest {

    @Test
    public void pressedStatePaintsTheAccentBlue() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ResearchPillButton button = new ResearchPillButton("+ Neuer Chat", 30, 8, 12);
                button.setSize(120, 30);
                assertEquals(ResearchUiPalette.SECONDARY_SURFACE.getRGB() & 0xFFFFFF, render(button).getRGB(60, 3) & 0xFFFFFF);
                button.getModel().setArmed(true);
                button.getModel().setPressed(true);
                assertEquals(ResearchUiPalette.ACCENT_BLUE.getRGB() & 0xFFFFFF, render(button).getRGB(60, 3) & 0xFFFFFF);
            }
        });
    }

    private static BufferedImage render(ResearchPillButton button) {
        BufferedImage image = new BufferedImage(button.getWidth(), button.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = image.createGraphics();
        try {
            button.paint(g2);
        } finally {
            g2.dispose();
        }
        return image;
    }
}
