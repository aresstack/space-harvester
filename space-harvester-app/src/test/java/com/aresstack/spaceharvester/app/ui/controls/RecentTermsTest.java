package com.aresstack.spaceharvester.app.ui.controls;

import org.junit.jupiter.api.Test;

import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Angelo 28.11.2026: Gedrückthalten bzw. Pfeil nach unten im Suchfeld zeigt die letzten Suchbegriffe, Shift+Entf löscht. */
class RecentTermsTest {

    @Test
    void theNewestTermComesFirstWithoutDuplicatesAndAtMostAsManyAsTheBackButtonShows() {
        List<List<String>> saved = new ArrayList<>();
        RecentTerms terms = new RecentTerms(List.of("alt", " ", "alt", "älter"), saved::add);
        assertThat(terms.terms()).containsExactly("alt", "älter");
        terms.add("  neu  ");
        terms.add("alt");
        assertThat(terms.terms()).containsExactly("alt", "neu", "älter");
        terms.add("alt");
        terms.add("");
        assertThat(saved).as("nur Änderungen werden gespeichert").hasSize(2);
        terms.remove("neu");
        assertThat(terms.terms()).containsExactly("alt", "älter");
        assertThat(saved.get(saved.size() - 1)).containsExactly("alt", "älter");
        IntStream.range(0, RecentTerms.MAX_TERMS + 3).forEach(i -> terms.add("Begriff " + i));
        assertThat(terms.terms()).hasSize(BackButton.MAX_ENTRIES)
                .startsWith("Begriff " + (RecentTerms.MAX_TERMS + 2));
    }

    @Test
    void arrowDownOpensTheListShiftDeleteRemovesAndEnterSearchesTheChosenTerm() throws Exception {
        List<String> searched = new ArrayList<>();
        List<String> opened = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            JTextField field = new JTextField(20);
            field.getInputMap().put(KeyStroke.getKeyStroke("DOWN"), "fallback");
            field.getActionMap().put("fallback", new javax.swing.AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    opened.add("Optionen");
                }
            });
            RecentTerms terms = new RecentTerms(List.of("drei", "zwei", "eins"), list -> { });
            RecentTermsPopup popup = RecentTermsPopup.install(field, field, terms, searched::add);
            popup.display = () -> true;

            press(field, "DOWN");
            assertThat(popup.isVisible()).as("geschlossen tut Pfeil nach unten, was er vorher tat").isFalse();
            assertThat(opened).containsExactly("Optionen");

            assertThat(popup.show(true)).isTrue();
            assertThat(popup.list.getSelectedValue()).isEqualTo("drei");
            press(field, "DOWN");
            assertThat(popup.list.getSelectedValue()).isEqualTo("zwei");
            press(field, "shift DELETE");
            assertThat(terms.terms()).as("Shift+Entf löscht wie im Windows-Dropdown").containsExactly("drei", "eins");
            assertThat(popup.list.getSelectedValue()).isEqualTo("eins");
            assertThat(field.getText()).as("Shift+Entf schneidet bei offener Liste nichts aus").isEmpty();
            press(field, "UP");
            press(field, "ENTER");
            assertThat(popup.isVisible()).isFalse();
            assertThat(field.getText()).isEqualTo("drei");
            assertThat(searched).containsExactly("drei");
            assertThat(opened).as("bei offener Liste nur die Liste").containsExactly("Optionen");

            popup.show(false);
            press(field, "ESCAPE");
            assertThat(popup.isVisible()).isFalse();
        });
    }

    private static void press(JTextField field, String key) {
        Object name = field.getInputMap().get(KeyStroke.getKeyStroke(key));
        field.getActionMap().get(name).actionPerformed(new ActionEvent(field, ActionEvent.ACTION_PERFORMED, key));
    }
}
