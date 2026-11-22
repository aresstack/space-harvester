package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Die editierbare Auswahl für parallele Downloads (Angelo 11.11.2026): Vorschläge samt 0, freie Eingabe nur ganzer
 * Zahlen in den Grenzen; Ungültiges springt mit Meldung auf den alten Wert zurück.
 */
class ParallelChoiceTest {

    @Test
    void onlyWholeNumbersWithinTheLimitsAreAccepted() {
        assertThat(ParallelChoice.parse("0", 0, 64)).isZero();
        assertThat(ParallelChoice.parse(" 12 ", 0, 64)).isEqualTo(12);
        assertThat(ParallelChoice.parse("64", 0, 64)).isEqualTo(64);
        assertThat(ParallelChoice.parse("65", 0, 64)).isNull();
        assertThat(ParallelChoice.parse("0", 1, 16)).isNull();
        assertThat(ParallelChoice.parse("-1", 0, 64)).isNull();
        assertThat(ParallelChoice.parse("2.5", 0, 64)).isNull();
        assertThat(ParallelChoice.parse("", 0, 64)).isNull();
        assertThat(ParallelChoice.parse("99999999999", 0, 64)).isNull();
    }

    @Test
    void aValidEntryIsAppliedAnInvalidOneRevertsWithAMessage() {
        List<Integer> applied = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        ParallelChoice choice = new ParallelChoice("tip", 0, 64, List.of(0, 1, 2, 4), 4, applied::add,
                problems::add);
        assertThat(choice.box().getItemAt(0)).as("0 steht in der Liste").isEqualTo("0");
        choice.box().setSelectedItem("0");
        assertThat(applied).containsExactly(0);
        assertThat(choice.value()).isZero();
        choice.box().setSelectedItem("99");
        assertThat(applied).containsExactly(0);
        assertThat(problems).containsExactly("Bitte eine ganze Zahl von 0 bis 64 eingeben.");
        assertThat(choice.box().getSelectedItem()).isEqualTo("0");
        choice.refresh(8);
        assertThat(choice.value()).isEqualTo(8);
        assertThat(applied).as("Auffrischen übernimmt nichts").containsExactly(0);
    }

    /** Angelo 22.11.2026: die Auswahl als Untermenü, der geltende Wert mit Haken, freie Zahl über „Benutzerdefiniert …“. */
    @Test
    void theMenuChecksTheCurrentValueAndTakesACustomNumber() {
        java.util.List<Integer> applied = new java.util.ArrayList<>();
        java.util.List<String> invalid = new java.util.ArrayList<>();
        ParallelChoice choice = new ParallelChoice("Downloads", 0, 64, java.util.List.of(0, 1, 2, 4, 8), 4,
                applied::add, invalid::add);

        javax.swing.JMenu menu = choice.menu("Parallele Downloads");
        assertThat(menu.getText()).isEqualTo("Parallele Downloads: 4");
        assertThat(checked(menu)).containsExactly("4");
        // Ein Vorschlag gilt sofort.
        item(menu, "8").doClick();
        assertThat(applied).containsExactly(8);
        assertThat(checked(choice.menu("Parallele Downloads"))).containsExactly("8");

        // Eine freie Zahl: der Eintrag „Benutzerdefiniert …“ trägt danach den Haken und nennt sie.
        choice.ask = current -> 12;
        item(choice.menu("Parallele Downloads"), ParallelChoice.CUSTOM).doClick();
        assertThat(applied).containsExactly(8, 12);
        assertThat(checked(choice.menu("Parallele Downloads"))).containsExactly(ParallelChoice.CUSTOM + " (12)");
        // Abgebrochen: nichts ändert sich.
        choice.ask = current -> null;
        item(choice.menu("Parallele Downloads"), ParallelChoice.CUSTOM + " (12)").doClick();
        assertThat(applied).containsExactly(8, 12);
        assertThat(invalid).isEmpty();
    }

    private static java.util.List<String> checked(javax.swing.JMenu menu) {
        java.util.List<String> checked = new java.util.ArrayList<>();
        for (java.awt.Component entry : menu.getMenuComponents()) {
            if (entry instanceof javax.swing.JCheckBoxMenuItem item && item.isSelected()) {
                checked.add(item.getText());
            }
        }
        return checked;
    }

    private static javax.swing.JMenuItem item(javax.swing.JMenu menu, String text) {
        for (java.awt.Component entry : menu.getMenuComponents()) {
            if (entry instanceof javax.swing.JMenuItem item && text.equals(item.getText())) {
                return item;
            }
        }
        throw new AssertionError("Kein Eintrag " + text);
    }
}
