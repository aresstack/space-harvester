package com.aresstack.spaceharvester.app.ui.content.viewer;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class VideoFullScreenControllerTest {

    @Test
    void togglesOnlyOnALeftButtonDoubleClick() throws Exception {
        AtomicInteger toggles = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            JPanel videoSurface = new JPanel();
            VideoFullScreenController.installDoubleClick(videoSurface, toggles::incrementAndGet);
            click(videoSurface, MouseEvent.BUTTON1, 1);
            click(videoSurface, MouseEvent.BUTTON3, 2);
            assertThat(toggles).hasValue(0);
            click(videoSurface, MouseEvent.BUTTON1, 2);
            assertThat(toggles).hasValue(1);
        });
    }

    @Test
    void fullScreenButtonSwitchesBetweenExpandAndRestoreIcons() {
        VideoTransportBar bar = new VideoTransportBar(position -> { });

        assertThat(bar.fullScreen().getIcon()).isInstanceOf(VideoFullScreenIcon.class);
        bar.setFullScreen(true);
        assertThat(bar.fullScreen().getToolTipText()).contains("beenden", "Esc");
        assertThat(bar.fullScreen().getAccessibleContext().getAccessibleName()).contains("beenden");

        bar.setFullScreen(false);
        assertThat(bar.fullScreen().getToolTipText()).contains("Doppelklick");
    }

    private static void click(JPanel surface, int button, int count) {
        surface.dispatchEvent(new MouseEvent(surface, MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, 10, 10, count, false, button));
    }
}
