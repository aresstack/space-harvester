package com.aresstack.spaceharvester.app.ui.controls;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;

import static org.assertj.core.api.Assertions.assertThat;

/** Länge als Zahl mit Einheit: Wörter als Standard, Seiten werden mit den einstellbaren Wörtern je Seite umgerechnet. */
class LengthFieldTest {

    @Test
    void wordsByDefaultPagesAreConvertedAndTheGrayHintSaysHowMany() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LengthField field = new LengthField(350);
            assertThat(field.unit()).isEqualTo(LengthField.Unit.WORDS);
            assertThat(field.words()).isZero();
            assertThat(field.equalsText()).contains("keine Vorgabe");

            field.value(1200, LengthField.Unit.WORDS);
            assertThat(field.words()).isEqualTo(1200);
            assertThat(field.equalsText()).as("bei Wörtern nichts umzurechnen").isEmpty();

            field.value(10, LengthField.Unit.PAGES);
            assertThat(field.wordsPerPage()).isEqualTo(350);
            assertThat(field.words()).isEqualTo(3500);
            assertThat(field.equalsText()).isEqualTo("entspricht 3.500 Wörtern");

            field.value(3, null);
            assertThat(field.unit()).as("null = Wörter").isEqualTo(LengthField.Unit.WORDS);
        });
    }

    @Test
    void typedTextCountsEvenWithoutLeavingTheField() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LengthField field = new LengthField(400);
            javax.swing.JSpinner spinner = (javax.swing.JSpinner) field.getComponent(0);
            ((javax.swing.JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setText("1200");
            assertThat(field.words()).isEqualTo(1200);
        });
    }

    @Test
    void unknownUnitNamesFallBackToWords() {
        assertThat(LengthField.Unit.parse("PAGES")).isEqualTo(LengthField.Unit.PAGES);
        assertThat(LengthField.Unit.parse("")).isEqualTo(LengthField.Unit.WORDS);
        assertThat(LengthField.Unit.parse(null)).isEqualTo(LengthField.Unit.WORDS);
    }
}
