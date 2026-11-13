package com.aresstack.spaceharvester.app.ui.controls;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Zahl plus Einheit statt „1d“: zeigt die größte passende Einheit und liefert beim Sonderwert {@code null}. */
class DurationFieldTest {

    @Test
    void showsTheLargestWholeUnitAndReadsBack() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DurationField field = new DurationField(9_999);
            field.value(Duration.ofHours(6));
            assertThat(field.spinner().getValue()).isEqualTo(6);
            assertThat(field.units().getSelectedItem()).isEqualTo("Stunden");
            field.value(Duration.ofDays(30));
            assertThat(field.spinner().getValue()).isEqualTo(30);
            assertThat(field.units().getSelectedItem()).isEqualTo("Tage");
            field.value(Duration.ofSeconds(90));
            assertThat(field.spinner().getValue()).isEqualTo(90);
            assertThat(field.units().getSelectedItem()).isEqualTo("Sekunden");
            field.value(Duration.ZERO);
            assertThat(field.spinner().getValue()).isEqualTo(0);
            assertThat(field.units().getSelectedItem()).as("0 in Sekunden").isEqualTo("Sekunden");
            field.spinner().setValue(45);
            field.units().setSelectedItem("Minuten");
            assertThat(field.value()).isEqualTo(Duration.ofMinutes(45));
            assertThat(field.isNone()).isFalse();
            field.value(Duration.ofMillis(1_600));
            assertThat(field.value()).as("auf ganze Sekunden gerundet").isEqualTo(Duration.ofSeconds(2));
        });
    }

    @Test
    void theSpecialValueDisablesTheNumberAndMeansNull() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DurationField field = new DurationField("Wie global", 9_999);
            assertThat(field.isNone()).isTrue();
            assertThat(field.value()).isNull();
            assertThat(field.spinner().isEnabled()).isFalse();
            assertThat(field.units().getItemAt(0)).isEqualTo("Wie global");
            int[] changes = {0};
            field.onChange(() -> changes[0]++);
            field.value(Duration.ofDays(1));
            assertThat(field.isNone()).isFalse();
            assertThat(field.spinner().isEnabled()).isTrue();
            assertThat(field.spinner().getValue()).isEqualTo(1);
            assertThat(field.units().getSelectedItem()).isEqualTo("Tage");
            assertThat(changes[0]).isGreaterThan(0);
            field.value(null);
            assertThat(field.isNone()).isTrue();
            assertThat(field.value()).isNull();
            field.setEnabled(false);
            assertThat(field.units().isEnabled()).isFalse();
        });
    }
}
