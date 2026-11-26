package com.aresstack.spaceharvester.app.ui.content;

import org.junit.jupiter.api.Test;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ProgressiveTextTest {

    @Test
    void aLongTextIsShownCompletelyWithTheCaretAtTheStart() throws Exception {
        String text = "x".repeat(ProgressiveText.STEP_CHARS * 3 + 7);
        AtomicReference<JTextArea> area = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            area.set(new JTextArea());
            ProgressiveText.show(area.get(), text);
            assertThat(area.get().getText()).hasSize(ProgressiveText.STEP_CHARS);
        });
        drain();
        SwingUtilities.invokeAndWait(() -> {
            assertThat(area.get().getText()).isEqualTo(text);
            assertThat(area.get().getCaretPosition()).isZero();
        });
    }

    @Test
    void aNewTextStopsTheFillingOfTheOldOne() throws Exception {
        String old = "a".repeat(ProgressiveText.STEP_CHARS * 2);
        AtomicReference<JTextArea> area = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            area.set(new JTextArea());
            ProgressiveText.show(area.get(), old);
            ProgressiveText.show(area.get(), "neu");
        });
        drain();
        SwingUtilities.invokeAndWait(() -> assertThat(area.get().getText()).isEqualTo("neu"));
    }

    /** Lässt alle schon eingereihten und von ihnen nachgereihten Schritte laufen. */
    private static void drain() throws Exception {
        for (int i = 0; i < 16; i++) {
            SwingUtilities.invokeAndWait(() -> {
            });
        }
    }
}
