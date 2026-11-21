package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.aresstack.spaceharvester.app.settings.VideoSettings;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Angelo 20.11.2026: Sprung zurück und vor mit Pfeilen im Kreis, gegenläufig, neben „Stoppen“; die Schrittweite
 * steht unter Einstellungen › Video, Vorgabe 5 Sekunden.
 */
final class VideoSkipButtonsTest {

    @Test
    void skipButtonsSitAfterStopWithMirroredCircularArrows() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            VideoTransportBar bar = new VideoTransportBar(position -> { });
            JButton back = bar.skipBack();
            JButton forward = bar.skipForward();
            Container row = bar.stop().getParent();
            int at = Arrays.asList(row.getComponents()).indexOf(bar.stop());
            assertThat(row.getComponent(at + 1)).isSameAs(back);
            assertThat(row.getComponent(at + 2)).isSameAs(forward);
            assertThat(back.isVisible()).isTrue();
            assertThat(paint(back)).as("gegenläufig, also gespiegelt").isNotEqualTo(paint(forward));
        });
        assertThat(VideoSettings.Value.SKIP_SECONDS.defaultValue()).isEqualTo(5);
    }

    private static int[] paint(JButton button) {
        BufferedImage image = new BufferedImage(17, 17, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        button.getIcon().paintIcon(button, g, 0, 0);
        g.dispose();
        return image.getRGB(0, 0, 17, 17, null, 0, 17);
    }
}
