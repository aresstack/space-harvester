package com.aresstack.spaceharvester.app.ui.controls;

import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Angelo 24.11.2026: ein Zurück wie im Browser, gedrückt halten zeigt oben Vorwärts, Trennlinie, darunter Zurück. */
class BackButtonTest {

    /** Ein Verlauf wie im Browser: Orte A … E, gerade auf C. */
    private static final class Trail implements BackButton.Navigator {
        final List<String> places = new ArrayList<>(List.of("A", "B", "C", "D", "E"));
        int at = 2;
        final List<Integer> jumps = new ArrayList<>();

        @Override
        public BackButton.History history() {
            List<String> forward = new ArrayList<>(places.subList(at + 1, places.size()));
            List<String> back = new ArrayList<>(places.subList(0, at));
            java.util.Collections.reverse(back);
            return new BackButton.History(forward, back);
        }

        @Override
        public void go(int steps) {
            jumps.add(steps);
            at += steps;
        }
    }

    @Test
    void theHistoryShowsForwardAboveTheLineAndBackBelowWithNames() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Trail trail = new Trail();
            BackButton button = new BackButton("Zurück").navigator(trail);
            JPopupMenu menu = button.historyMenu();
            List<String> labels = new ArrayList<>();
            for (java.awt.Component item : menu.getComponents()) {
                labels.add(item instanceof JMenuItem entry ? entry.getText() : item instanceof JSeparator ? "—" : "?");
            }
            assertThat(labels).containsExactly("E", "D", "—", "B", "A");
            ((JMenuItem) menu.getComponent(0)).doClick();
            assertThat(trail.jumps).containsExactly(2);
            assertThat(trail.at).isEqualTo(4);
            JPopupMenu atEnd = button.historyMenu();
            assertThat(atEnd.getComponentCount()).as("am Ende ohne Vorwärts, ohne Trennlinie").isEqualTo(4);
            ((JMenuItem) atEnd.getComponent(3)).doClick();
            assertThat(trail.jumps).as("vom Ende ganz an den Anfang").containsExactly(2, -4);
        });
    }

    @Test
    void aClickGoesOneStepBackAndTheButtonStaysWhileOnlyForwardIsLeft() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Trail trail = new Trail();
            BackButton button = new BackButton("Zurück").navigator(trail);
            button.doClick();
            assertThat(trail.jumps).containsExactly(-1);
            button.doClick();
            button.refresh();
            assertThat(trail.at).isZero();
            assertThat(button.isEnabled()).as("Vorwärts bleibt über den Verlauf erreichbar").isTrue();
            button.doClick();
            assertThat(trail.jumps).as("am Anfang tut ein Klick nichts").containsExactly(-1, -1);
            assertThat(button.historyMenu().getComponentCount()).isEqualTo(4);

            trail.places.clear();
            trail.places.add("nur");
            trail.at = 0;
            button.refresh();
            assertThat(button.isEnabled()).isFalse();
        });
    }
}
