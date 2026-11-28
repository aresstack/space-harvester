package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.ui.content.WordSearch;
import com.aresstack.spaceharvester.app.ui.controls.InputFrame;
import com.aresstack.spaceharvester.app.ui.controls.SearchModeIcon;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Bildschirmfotos der Wortsuche zur Sichtprüfung (Suchfeld in beiden Modi, Wortliste); läuft nur mit der
 * Umgebungsvariable {@code SCREENSHOTS_DIR}.
 */
class WordSearchScreenshotTest {

    @Test
    void renderWordSearch() throws Exception {
        String directory = System.getenv("SCREENSHOTS_DIR");
        Assumptions.assumeTrue(directory != null && !directory.isBlank());
        Path out = Path.of(directory);
        Files.createDirectories(out);
        SwingUtilities.invokeAndWait(() -> {
            for (boolean word : new boolean[] {false, true}) {
                SearchModeIcon icon = new SearchModeIcon();
                icon.word(word);
                JTextField field = Comic.field(30);
                field.setText("DS Einzelbuchungen");
                InputFrame bar = InputFrame.search(field, icon);
                bar.addButton(InputFrame.flatButton("▾", "Suchoptionen"));
                JPanel surface = new JPanel(new BorderLayout());
                surface.setBackground(Comic.SURFACE);
                surface.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 14, 12, 14));
                surface.add(bar, BorderLayout.NORTH);
                surface.setSize(560, 64);
                Screenshots.write(surface, out.resolve("suchfeld-" + (word ? "wortsuche" : "seitensuche") + ".png"));
            }
            WordHitsPanel panel = new WordHitsPanel(place -> { }, text -> { }, error -> { });
            panel.model.set(List.of(
                    new WordSearch.Word("DS", WordSearch.Kind.ACRONYMS, 14, 52, "Datensatz", null, null),
                    new WordSearch.Word("DSGVO", WordSearch.Kind.ACRONYMS, 3, 7,
                            "Datenschutz-Grundverordnung", null, null),
                    new WordSearch.Word("Einzelbuchung", WordSearch.Kind.IDENTIFIERS, 9, 31, "Einzelbuchungen", null,
                            null),
                    new WordSearch.Word("DS-Einzelbuchungen", WordSearch.Kind.IDENTIFIERS, 2, 4, "", null, null),
                    new WordSearch.Word("buchen", WordSearch.Kind.VERBS, 11, 26, "", null, null)));
            JPanel surface = new JPanel(new BorderLayout());
            surface.setBackground(Comic.SURFACE);
            surface.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 14, 12, 14));
            surface.add(panel, BorderLayout.CENTER);
            surface.setSize(960, 260);
            Screenshots.write(surface, out.resolve("wortsuche-liste.png"));
        });
    }
}
