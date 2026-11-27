package com.aresstack.spaceharvester.app.ui.controls;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import static org.assertj.core.api.Assertions.assertThat;

class InputFrameGateTest {

    @Test
    void aGateAboveHidesEverySearchFieldUntilItOpensAndClearsThemWhenItCloses() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel root = new JPanel();
            JPanel view = new JPanel();
            root.add(view);
            JTextField text = new JTextField("lager");
            InputFrame search = InputFrame.search(text);
            InputFrame plain = new InputFrame(new JTextField("bleibt"));
            view.add(search);
            view.add(plain);
            assertThat(search.isVisible()).as("ohne Schleuse wie bisher").isTrue();

            InputFrame.gate(root, false);
            assertThat(search.isVisible()).isFalse();
            assertThat(text.getText()).as("kein unsichtbarer Filter").isEmpty();
            assertThat(plain.isVisible()).as("nur Suchfelder folgen der Schleuse").isTrue();
            assertThat(((JTextField) plain.field()).getText()).isEqualTo("bleibt");

            InputFrame.gate(root, true);
            assertThat(search.isVisible()).isTrue();
            assertThat(InputFrame.gated(root)).isSameAs(search);

            // Die Ansicht entscheidet weiter selbst, ob ihr Feld gebraucht wird.
            search.setVisible(false);
            assertThat(search.isVisible()).isFalse();
            assertThat(InputFrame.gated(root)).isNull();
            InputFrame.gate(root, false);
            search.setVisible(true);
            assertThat(search.isVisible()).as("gewollt, aber die Schleuse ist zu").isFalse();
            InputFrame.gate(root, true);
            assertThat(search.isVisible()).isTrue();

            // Ein Feld, das erst später unter die Fläche kommt, folgt ihr sofort.
            InputFrame.gate(root, false);
            InputFrame late = InputFrame.search(new JTextField());
            view.add(late);
            assertThat(late.isVisible()).isFalse();
            view.remove(late);
            assertThat(late.isVisible()).as("außerhalb gilt keine Schleuse").isTrue();
        });
    }
}
