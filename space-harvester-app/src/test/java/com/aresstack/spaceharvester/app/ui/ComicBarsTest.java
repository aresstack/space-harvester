package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.ui.comic.control.ComicScrollBarUI;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.swing.JScrollPane;
import org.fife.ui.rtextarea.RTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.junit.jupiter.api.Test;

/** Überall dieselben Rollbalken (Angelo 28.11.2026: im Editor des Command Decks standen noch die von Swing). */
class ComicBarsTest {

    @Test
    void comicBarsGivesBothBarsOfAnyScrollPaneTheSharedLook() {
        RTextScrollPane editor = Comic.comicBars(new RTextScrollPane(new RTextArea(), true));
        assertThat(editor.getVerticalScrollBar().getUI()).isInstanceOf(ComicScrollBarUI.class);
        assertThat(editor.getHorizontalScrollBar().getUI()).isInstanceOf(ComicScrollBarUI.class);
        JScrollPane plain = Comic.comicBars(new JScrollPane());
        assertThat(plain.getVerticalScrollBar().getUI()).isInstanceOf(ComicScrollBarUI.class);
        assertThat(Comic.scroll(new javax.swing.JPanel()).getHorizontalScrollBar().getUI())
                .isInstanceOf(ComicScrollBarUI.class);
    }

    /** Eine neu gebaute Rollfläche, die nicht schon die der comic-controls ist. */
    private static final java.util.regex.Pattern BUILDS = java.util.regex.Pattern.compile(
            "new (javax\\.swing\\.|org\\.fife\\.ui\\.rtextarea\\.)?(JScrollPane|RTextScrollPane)\\(");
    /** So viele Zeilen nach dem Bau darf das Einrichten der Balken stehen (anonyme Unterklasse dazwischen). */
    private static final int WINDOW = 14;

    private static boolean styled(List<String> lines, int at) {
        for (int i = at; i < Math.min(lines.size(), at + WINDOW); i++) {
            if (lines.get(i).contains("comicBars(") || lines.get(i).contains("ComicScrollBarUI.install")) {
                return true;
            }
        }
        return false;
    }

    /** Zentral abgesichert: Keine Datei der App baut eine Rollfläche, ohne ihr die Balken der App zu geben. */
    @Test
    void noSourceBuildsAScrollPaneWithSwingBars() throws IOException {
        Path sources = Path.of("src/main/java");
        assertThat(sources).isDirectory();
        try (Stream<Path> files = Files.walk(sources)) {
            List<String> plain = files.filter(file -> file.toString().endsWith(".java"))
                    .flatMap(file -> {
                        try {
                            List<String> lines = Files.readAllLines(file);
                            return java.util.stream.IntStream.range(0, lines.size())
                                    .filter(i -> BUILDS.matcher(lines.get(i)).find() && !styled(lines, i))
                                    .mapToObj(i -> file.getFileName() + ":" + (i + 1));
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    }).sorted().toList();
            assertThat(plain).as("Rollflächen nur mit Comic.scroll oder Comic.comicBars").isEmpty();
        }
    }
}
