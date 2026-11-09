package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Der umbrechende Text nennt immer die Höhe für eine echte Breite, nie für den Zustand seines Textfelds. */
class WrappedTextTest {

    private static final String SENTENCE = "„Zertifikat verwenden“ ist eingeschaltet, aber kein Zertifikat gewählt.";

    /**
     * Ein unsichtbarer Behälter wird mit Breite 0 ausgelegt, der Text darin bekommt eine negative Breite. Danach
     * brach er je Zeichen um und meldete eine Zeile je Buchstabe als Wunschhöhe.
     */
    @Test
    void aTextLaidOutWithoutWidthStillReportsTheHeightOfItsLines() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WrappedText text = new WrappedText("");
            int line = text.getFontMetrics(text.getFont()).getHeight();
            text.setBounds(0, 0, -28, text.getPreferredSize().height);

            text.setText(SENTENCE);

            assertThat(text.getPreferredSize().height).as("eine oder zwei Zeilen, nicht eine je Zeichen")
                    .isLessThanOrEqualTo(2 * line);
        });
    }

    @Test
    void theHeightGrowsWhenTheWidthShrinks() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WrappedText text = new WrappedText(SENTENCE);
            int line = text.getFontMetrics(text.getFont()).getHeight();

            assertThat(text.heightFor(2000)).as("eine Zeile").isEqualTo(line);
            assertThat(text.heightFor(120)).as("mehrere Zeilen").isGreaterThanOrEqualTo(3 * line);

            text.setSize(120, 10);
            assertThat(text.getPreferredSize().height).as("Wunschhöhe für die eigene Breite")
                    .isEqualTo(text.heightFor(120));
        });
    }

    /** Ein Wort ohne Leerzeichen bricht an Zeichen um; der Text wird höher, nie breiter. */
    @Test
    void aLongTokenWrapsInsteadOfWidening() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WrappedText text = new WrappedText("x".repeat(600));
            int line = text.getFontMetrics(text.getFont()).getHeight();

            assertThat(text.getPreferredSize().width).as("drückt seine Spalte nicht auf").isLessThanOrEqualTo(40);
            assertThat(text.heightFor(300)).isGreaterThan(5 * line);
        });
    }
}
