package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.Comic;
import java.awt.image.BufferedImage;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Die Fläche, die sich als Ganzes gelb gewählt zeigt (Angelo 28.11.2026). */
class SelectionGlowTest {

    @Test
    void theGlowFillsItsExtentWithRoundCornersAndFlowsFromTheTop() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SelectionGlow glow = new SelectionGlow();
            glow.setSize(200, 300);
            glow.extent(() -> 120);
            assertThat(color(glow, 100, 60)).as("nicht gewählt: nichts gezeichnet").isZero();

            // Ohne Fenster gibt es keine Bewegung: gewählt steht die Fläche sofort.
            glow.on(true);
            assertThat(glow.on()).isTrue();
            assertThat(glow.shown()).isEqualTo(1f);
            assertThat(color(glow, 100, 60)).isEqualTo(Comic.YELLOW.getRGB());
            assertThat(color(glow, 100, 119)).isEqualTo(Comic.YELLOW.getRGB());
            assertThat(color(glow, 100, 130)).as("nur bis zu seiner Höhe").isZero();
            assertThat(color(glow, 0, 0)).as("runde Ecke").isZero();

            // Unterwegs läuft die Mitte den Rändern voraus.
            glow.frame(0.3f);
            int middle = reach(glow, 100);
            int side = reach(glow, 2);
            assertThat(middle).isGreaterThan(side).isLessThan(120);

            glow.on(false);
            assertThat(glow.shown()).isZero();
            assertThat(color(glow, 100, 60)).isZero();
        });
    }

    private static int reach(SelectionGlow glow, int x) {
        BufferedImage image = paint(glow);
        int y = 0;
        while (y < image.getHeight() && image.getRGB(x, y) != 0) {
            y++;
        }
        return y;
    }

    private static int color(SelectionGlow glow, int x, int y) {
        return paint(glow).getRGB(x, y);
    }

    private static BufferedImage paint(SelectionGlow glow) {
        BufferedImage image = new BufferedImage(glow.getWidth(), glow.getHeight(), BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        glow.paint(g);
        g.dispose();
        return image;
    }
}
