package com.aresstack.spaceharvester.app.ui.content.viewer;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VideoSeekBarTest {

    @Test
    void draggingPreviewsLocallyAndCommitsExactlyOneSeekOnRelease() throws Exception {
        List<Double> seeks = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            VideoSeekBar bar = new VideoSeekBar(seeks::add);
            bar.setSize(800, 48);
            bar.update(15000, 120000);
            mouse(bar, MouseEvent.MOUSE_PRESSED, 20);
            mouse(bar, MouseEvent.MOUSE_DRAGGED, 440);
            assertThat(seeks).isEmpty();
            assertThat(bar.displayedMillis()).isGreaterThan(60000);
            mouse(bar, MouseEvent.MOUSE_RELEASED, 786);
            assertThat(seeks).containsExactly(1d);
            assertThat(bar.isDragging()).isFalse();
        });
    }

    @Test
    void timelineRemainsDisabledUntilVlcKnowsTheDuration() throws Exception {
        List<Double> seeks = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            VideoSeekBar bar = new VideoSeekBar(seeks::add);
            bar.setSize(800, 48);
            bar.reset();
            mouse(bar, MouseEvent.MOUSE_PRESSED, 400);
            mouse(bar, MouseEvent.MOUSE_RELEASED, 400);
            assertThat(seeks).isEmpty();
            bar.update(5000, 10000);
            assertThat(bar.canSeek()).isTrue();
            assertThat(bar.displayedMillis()).isEqualTo(5000);
        });
    }

    @Test
    void allowsNormalizedSeekingWhenTheRemoteVideoHasNoKnownDuration() throws Exception {
        List<Double> seeks = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            VideoSeekBar bar = new VideoSeekBar(seeks::add);
            bar.setSize(800, 48);
            bar.reset();
            bar.updateFraction(0.25);
            assertThat(bar.canSeek()).isTrue();
            assertThat(bar.displayedMillis()).isEqualTo(-1);
            mouse(bar, MouseEvent.MOUSE_PRESSED, 400);
            mouse(bar, MouseEvent.MOUSE_RELEASED, 400);
            assertThat(seeks).singleElement().satisfies(share ->
                    assertThat(share).isBetween(0.49, 0.51));
        });
    }

    @Test
    void clockShowsMinutesHoursAndUnknownDuration() {
        assertThat(VideoSeekBar.clock(-1)).isEqualTo("--:--");
        assertThat(VideoSeekBar.clock(81000)).isEqualTo("01:21");
        assertThat(VideoSeekBar.clock(3723000)).isEqualTo("1:02:03");
    }

    @Test
    void transportUsesAnOpaqueSurfaceSeparateFromNativeVlc() {
        VideoTransportBar bar = new VideoTransportBar(share -> { });
        assertThat(bar.isOpaque()).isTrue();
        assertThat(bar.play().getText()).isEmpty();
        assertThat(bar.pause().getText()).isEmpty();
        assertThat(bar.stop().getText()).isEmpty();
        assertThat(bar.cancelDownload().isVisible()).isFalse();
        assertThat(bar.seekBar().getPreferredSize().width).isGreaterThan(600);
        bar.showDownloadCancellation(true);
        assertThat(bar.cancelDownload().isVisible()).isTrue();
    }

    private static void mouse(VideoSeekBar bar, int kind, int x) {
        int modifiers = kind == MouseEvent.MOUSE_RELEASED ? 0 : MouseEvent.BUTTON1_DOWN_MASK;
        bar.dispatchEvent(new MouseEvent(bar, kind, System.currentTimeMillis(), modifiers, x, 17, 1,
                false, MouseEvent.BUTTON1));
    }
}
