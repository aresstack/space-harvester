package com.aresstack.spaceharvester.app.ui.controls;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PodcastOrbTest {

    @Test
    void namesEveryStateForScreenReaders() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PodcastOrb orb = new PodcastOrb();
            assertEquals("Podcast abspielen", orb.getAccessibleContext().getAccessibleName());
            orb.setState(PodcastOrb.State.PAUSE);
            assertEquals("Podcast anhalten", orb.getAccessibleContext().getAccessibleName());
            orb.setState(PodcastOrb.State.WAITING);
            assertEquals("Podcast wird vorbereitet", orb.getAccessibleContext().getAccessibleName());
            orb.setState(PodcastOrb.State.FAILED);
            assertEquals("Podcast erneut versuchen", orb.getAccessibleContext().getAccessibleName());
        });
    }

    @Test
    void aFailedRetryFlashesDarkRedAndStaysPaleRed() throws Exception {
        PodcastOrb[] orb = new PodcastOrb[1];
        SwingUtilities.invokeAndWait(() -> {
            orb[0] = new PodcastOrb();
            orb[0].flashFailure();
            assertEquals(PodcastOrb.State.FAILED, orb[0].state());
            assertTrue(orb[0].flashing());
        });
        long until = System.currentTimeMillis() + 3000;
        boolean[] flashing = {true};
        while (flashing[0] && System.currentTimeMillis() < until) {
            Thread.sleep(50);
            SwingUtilities.invokeAndWait(() -> flashing[0] = orb[0].flashing());
        }
        SwingUtilities.invokeAndWait(() -> {
            assertFalse(orb[0].flashing());
            assertEquals(PodcastOrb.State.FAILED, orb[0].state());
        });
    }

    @Test
    void paintsEachStateInBlueOrPaleRed() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PodcastOrb orb = new PodcastOrb();
            assertEquals(PodcastOrb.SIZE, orb.getPreferredSize().width);
            int center = PodcastOrb.SIZE / 2;
            // Mitte des Rands links: dort liegt die Füllung, kein Zeichen
            for (PodcastOrb.State state : PodcastOrb.State.values()) {
                orb.setState(state);
                BufferedImage image = new BufferedImage(PodcastOrb.SIZE, PodcastOrb.SIZE, BufferedImage.TYPE_INT_ARGB);
                orb.paint(image.createGraphics());
                int rgb = image.getRGB(5, center) & 0xFFFFFF;
                if (state == PodcastOrb.State.FAILED) {
                    assertEquals(PodcastOrb.FAILED.getRGB() & 0xFFFFFF, rgb, state.name());
                } else {
                    assertEquals(com.aresstack.spaceharvester.app.ui.Comic.BLUE.getRGB() & 0xFFFFFF, rgb, state.name());
                }
            }
            orb.setState(PodcastOrb.State.PLAY);
        });
    }
}
