package com.aresstack.spaceharvester.app.ui.controls;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Die Skala um den Vorlese-Knopf: oben Geschwindigkeit, unten satzweise springen. */
class ReadAloudDialTest {

    @Test
    void theUpperHalfMapsLeftToSlowerTopToNormalAndRightToFaster() throws Exception {
        List<Double> rates = new ArrayList<>();
        ReadAloudDial[] dial = new ReadAloudDial[1];
        SwingUtilities.invokeAndWait(() -> {
            dial[0] = new ReadAloudDial(new PodcastOrb(), rates::add, sentences -> { });
            dial[0].setLimits(0.5, 2.0, 0.05, 10);
        });
        ReadAloudDial d = dial[0];
        assertThat(d.rateFor(0)).isEqualTo(1.0);
        assertThat(d.rateFor(-90)).isEqualTo(0.5);
        assertThat(d.rateFor(90)).isEqualTo(2.0);
        assertThat(d.rateFor(-45)).isCloseTo(0.75, within(1e-9));
        assertThat(d.rateFor(45)).isCloseTo(1.5, within(1e-9));
        assertThat(d.angleFor(0.75)).isCloseTo(-45, within(1e-9));
        assertThat(d.angleFor(1.5)).isCloseTo(45, within(1e-9));
        // eingestellte Grenzen gelten, das Raster rastet ein
        SwingUtilities.invokeAndWait(() -> d.setLimits(0.8, 1.5, 0.1, 5));
        assertThat(d.rateFor(-90)).isCloseTo(0.8, within(1e-9));
        assertThat(d.rateFor(90)).isCloseTo(1.5, within(1e-9));
        assertThat(d.rateFor(10)).isCloseTo(1.1, within(1e-9));
        assertThat(rates).isEmpty();
    }

    @Test
    void theLowerHalfCountsSentencesFromTheBottomLeftBackRightForward() throws Exception {
        ReadAloudDial[] dial = new ReadAloudDial[1];
        SwingUtilities.invokeAndWait(() -> {
            dial[0] = new ReadAloudDial(new PodcastOrb(), rate -> { }, sentences -> { });
            dial[0].setLimits(0.5, 2.0, 0.05, 10);
        });
        assertThat(ReadAloudDial.fromBottom(180)).isEqualTo(0);
        assertThat(ReadAloudDial.fromBottom(-135)).isEqualTo(-45);
        assertThat(ReadAloudDial.fromBottom(135)).isEqualTo(45);
        assertThat(dial[0].sentencesFor(-90)).isEqualTo(-10);
        assertThat(dial[0].sentencesFor(45)).isEqualTo(5);
        assertThat(dial[0].sentencesFor(4)).isEqualTo(0);
    }

    @Test
    void ratesReadLikeTheEnterpriseAiClient() {
        assertThat(ReadAloudDial.formatRate(1.0)).isEqualTo("1×");
        assertThat(ReadAloudDial.formatRate(0.75)).isEqualTo("0,75×");
        assertThat(ReadAloudDial.formatRate(1.5)).isEqualTo("1,5×");
    }

    @Test
    void withoutPlaybackTheDialStaysHidden() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ReadAloudDial dial = new ReadAloudDial(new PodcastOrb(), rate -> { }, sentences -> { });
            dial.offer();
            assertThat(dial.shown()).isFalse();
            dial.setActive(true);
            dial.setActive(false);
            assertThat(dial.shown()).isFalse();
        });
    }
}
