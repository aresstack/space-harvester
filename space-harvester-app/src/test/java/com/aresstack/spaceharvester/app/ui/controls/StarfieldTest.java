package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Der Sternenflug zeichnet ohne Bildschirm und läuft nur, solange er zu sehen ist. */
class StarfieldTest {

    @Test
    void paintsStarsOnDeepSpaceAndDoesNotAnimateWhileHidden() {
        Starfield stars = new Starfield(Starfield.Mode.WARP, new Random(7)).headline("MISSION CONTROL")
                .caption("In Vorbereitung").cockpit(true);
        stars.setSize(640, 400);
        assertThat(stars.animating()).as("nicht auf dem Bildschirm, keine Animation").isFalse();
        for (int i = 0; i < 30; i++) {
            stars.advance(0.05);
        }
        BufferedImage image = new BufferedImage(640, 400, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        stars.paint(g);
        g.dispose();
        int bright = 0;
        for (int x = 0; x < 640; x += 2) {
            for (int y = 0; y < 400; y += 2) {
                int rgb = image.getRGB(x, y);
                if (((rgb >> 16) & 0xFF) > 200 && ((rgb >> 8) & 0xFF) > 200 && (rgb & 0xFF) > 200) {
                    bright++;
                }
            }
        }
        assertThat(bright).as("helle Sterne und Schrift auf dunklem Grund").isGreaterThan(20);
        int corner = image.getRGB(320, 200 - 120);
        assertThat(((corner >> 16) & 0xFF) + ((corner >> 8) & 0xFF) + (corner & 0xFF)).isLessThan(3 * 200);
    }
}
