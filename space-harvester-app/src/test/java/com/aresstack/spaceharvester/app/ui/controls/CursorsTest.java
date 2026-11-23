package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Cursor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.swing.JLabel;
import org.junit.jupiter.api.Test;

/**
 * Der Mauszeiger wird nur gesetzt, wenn er sich ändert: Jeder Aufruf von {@code setCursor} lässt AWT den Zeiger unter
 * der Maus neu bestimmen und verwirft dabei wartende Mausbewegungen. Eine Anzeige, die das bei jeder Meldung tut
 * (die Statuszeile, solange Aufträge laufen), nahm den Trennern den Doppelpfeil (Angelo, 23.11.2026).
 */
class CursorsTest {

    /** Zählt, wie oft AWT wirklich einen Zeiger gesetzt bekommt. */
    private static final class Counting extends JLabel {
        int calls;

        @Override
        public void setCursor(Cursor cursor) {
            calls++;
            super.setCursor(cursor);
        }
    }

    @Test
    void theCursorIsOnlySetWhenItChanges() {
        Counting label = new Counting();
        Cursor hand = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
        // Ohne eigenen Zeiger ist „keiner“ keine Änderung, auch wiederholt nicht.
        Cursors.set(label, null);
        Cursors.set(label, null);
        assertThat(label.calls).isZero();
        Cursors.set(label, hand);
        Cursors.set(label, hand);
        Cursors.set(label, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        assertThat(label.calls).isEqualTo(1);
        assertThat(label.getCursor()).isEqualTo(hand);
        Cursors.set(label, null);
        Cursors.set(label, null);
        assertThat(label.calls).isEqualTo(2);
        assertThat(label.isCursorSet()).isFalse();
    }

    /** Die Statuszeile zeigt bei laufenden Aufträgen ständig neue Meldungen; ihr Zeiger bleibt dabei unberührt. */
    @Test
    void aNewStatusMessageLeavesTheCursorAlone() {
        StatusLabel status = new StatusLabel();
        for (int i = 0; i < 5; i++) {
            status.show(StatusNote.plain("Aufträge: 7 laufen · " + i + " %"));
        }
        assertThat(status.isCursorSet()).isFalse();
    }

    /** Zentral abgesichert: Kein Code der App setzt einen Zeiger an {@link Cursors} vorbei. */
    @Test
    void noCodeOfTheAppSetsACursorPastThisClass() throws IOException {
        Path sources = Path.of("src/main/java");
        assertThat(sources).isDirectory();
        try (Stream<Path> files = Files.walk(sources)) {
            List<String> direct = files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> !file.getFileName().toString().equals("Cursors.java"))
                    .flatMap(file -> {
                        try {
                            List<String> lines = Files.readAllLines(file);
                            return java.util.stream.IntStream.range(0, lines.size())
                                    .filter(i -> lines.get(i).contains("setCursor(")
                                            && !lines.get(i).strip().startsWith("*")
                                            && !lines.get(i).strip().startsWith("//"))
                                    .mapToObj(i -> file.getFileName() + ":" + (i + 1));
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    }).toList();
            assertThat(direct).as("setCursor nur über Cursors.set").isEmpty();
        }
    }
}
