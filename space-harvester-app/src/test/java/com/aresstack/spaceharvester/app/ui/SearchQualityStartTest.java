package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.application.quality.PageResolver;
import com.aresstack.spaceharvester.application.quality.QualitySuite;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Startoverlay „Suchqualität messen“ und sein Platz im ▼-Menü (Q1b); nur erfundene Fragen. */
class SearchQualityStartTest {

    private static final String SECRET = "Wer genehmigt den geheimen Mittelabruf?";

    @TempDir
    Path folder;

    private final SearchQualityStart.Runner now = new SearchQualityStart.Runner() {
        @Override
        public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
            T result;
            try {
                result = work.call();
            } catch (Exception e) {
                onFailure.accept(e);
                return;
            }
            onSuccess.accept(result);
        }
    };

    private final List<Path[]> starts = new ArrayList<>();

    private SearchQualityStart overlay() {
        return new SearchQualityStart(folder, path -> QualitySuite.read(Files.readAllBytes(path), PageResolver.NONE),
                (suite, previous) -> {
                    starts.add(new Path[] {suite, previous});
                    return null;
                }, now, status -> { });
    }

    private Path suite(String... rows) throws Exception {
        Path file = folder.resolve("fragen.tsv");
        List<String> lines = new ArrayList<>();
        lines.add("id\tfrage\tbereich\terwartet\tart");
        lines.addAll(List.of(rows));
        Files.write(file, lines, StandardCharsets.UTF_8);
        return file;
    }

    private static void choose(com.aresstack.spaceharvester.app.ui.controls.FileField field, Path file) {
        field.chooser(start -> Optional.of(file));
        field.browseButton().doClick();
    }

    @Test
    void measuringStaysOffUntilATestSetWithAValidQuestionIsChosen() throws Exception {
        Path file = suite("F1\t" + SECRET + "\tq\tq:1\t", "F2\tFrage zwei\t*\tq:2\t", "F3\tFrage drei\tq\tunlesbar\t");
        SwingUtilities.invokeAndWait(() -> {
            SearchQualityStart start = overlay();
            assertThat(start.start.isEnabled()).isFalse();
            assertThat(start.tone()).isEqualTo(SearchQualityStart.Tone.NONE);

            choose(start.suite, file);

            assertThat(start.statusText()).isEqualTo("2 Fragen gültig · 1 Zeile ausgelassen (Zeile 4)")
                    .doesNotContain(SECRET);
            assertThat(start.tone()).isEqualTo(SearchQualityStart.Tone.NOTICE);
            assertThat(start.start.isEnabled()).isTrue();
        });
    }

    @Test
    void aTestSetWithoutValidQuestionIsAProblemAndCannotBeMeasured() throws Exception {
        Path file = suite("F1\tFrage eins\tq\tunlesbar\t");
        SwingUtilities.invokeAndWait(() -> {
            SearchQualityStart start = overlay();
            choose(start.suite, file);
            assertThat(start.tone()).isEqualTo(SearchQualityStart.Tone.PROBLEM);
            assertThat(start.statusText()).startsWith("Keine gültige Frage");
            assertThat(start.start.isEnabled()).isFalse();
        });
    }

    @Test
    void choosingTheSameFileAgainReadsItAgainAfterItWasFixed() throws Exception {
        Path file = suite("F1\tFrage eins\tq\tunlesbar\t");
        SearchQualityStart[] start = new SearchQualityStart[1];
        SwingUtilities.invokeAndWait(() -> {
            start[0] = overlay();
            choose(start[0].suite, file);
            assertThat(start[0].start.isEnabled()).isFalse();
        });
        suite("F1\tFrage eins\tq\tq:1\t");
        SwingUtilities.invokeAndWait(() -> {
            choose(start[0].suite, file);
            assertThat(start[0].statusText()).isEqualTo("1 Frage gültig");
            assertThat(start[0].start.isEnabled()).isTrue();
        });
    }

    @Test
    void measuringStartsWithTheOptionalComparisonAndClosesTheOverlay() throws Exception {
        Path file = suite("F1\tFrage eins\tq\tq:1\t");
        Path earlier = Files.writeString(folder.resolve("fragen – 2026-11-20 16-05.tsv"), "# version=1\n");
        SwingUtilities.invokeAndWait(() -> {
            SearchQualityStart start = overlay();
            AtomicBoolean closed = new AtomicBoolean();
            start.onClose(() -> closed.set(true));
            choose(start.suite, file);
            choose(start.previous, earlier);
            assertThat(start.previous.clearButton().isVisible()).isTrue();
            start.previous.clearButton().doClick();
            assertThat(start.previous.path()).isNull();
            choose(start.previous, earlier);

            start.start.doClick();

            assertThat(starts).hasSize(1);
            assertThat(starts.get(0)[0]).isEqualTo(file.toAbsolutePath().normalize());
            assertThat(starts.get(0)[1]).isEqualTo(earlier.toAbsolutePath().normalize());
            assertThat(closed).isTrue();
        });
    }

    @Test
    void theOverlayCancelsOnlyThroughTheEscCircle() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicBoolean cancelled = new AtomicBoolean();
            javax.swing.JPanel surface = Dialogs.escSurface(SearchQualityStart.TITLE, overlay().component(),
                    () -> cancelled.set(true));
            List<com.aresstack.spaceharvester.app.ui.controls.EscCircle> circles = new ArrayList<>();
            List<javax.swing.JButton> buttons = new ArrayList<>();
            collect(surface, circles, buttons);
            assertThat(circles).hasSize(1);
            assertThat(buttons).allSatisfy(button -> assertThat(button.getText())
                    .isIn(com.aresstack.spaceharvester.app.ui.controls.FileField.BROWSE_LABEL, "✕",
                            SearchQualityStart.START));
            circles.get(0).click();
            assertThat(cancelled).isTrue();
        });
    }

    private static void collect(java.awt.Container parent,
                                List<com.aresstack.spaceharvester.app.ui.controls.EscCircle> circles,
                                List<javax.swing.JButton> buttons) {
        for (java.awt.Component child : parent.getComponents()) {
            if (child instanceof com.aresstack.spaceharvester.app.ui.controls.EscCircle circle) {
                circles.add(circle);
            } else if (child instanceof javax.swing.JButton button) {
                buttons.add(button);
            }
            if (child instanceof java.awt.Container container) {
                collect(container, circles, buttons);
            }
        }
    }

    @Test
    void theEntrySitsAtTheTopOfTheMenuNextToTheBroomAboveDeleteAll() throws Exception {
        Path home = Files.createTempDirectory("q1start");
        AppContext context = new AppContext(new AppSettingsLoader(Placeholders.system(), home),
                home.resolve("app.yml"), "test");
        try {
            SwingUtilities.invokeAndWait(() -> {
                ActivitiesView view = new ActivitiesView(context);
                JPopupMenu menu = view.moreJobsMenu();
                assertThat(((JMenuItem) menu.getComponent(0)).getText()).isEqualTo(SearchQualityStart.MENU);
                assertThat(menu.getComponent(1)).isInstanceOf(JPopupMenu.Separator.class);
                assertThat(((JMenuItem) menu.getComponent(2)).getText()).isEqualTo(ActivitiesView.DELETE_ALL);
                // Darunter die Grenzen der Parallelität als Untermenüs (Angelo 22.11.2026).
                assertThat(menu.getComponent(3)).isInstanceOf(JPopupMenu.Separator.class);
                assertThat(((JMenuItem) menu.getComponent(4)).getText())
                        .startsWith(ActivitiesView.PARALLEL_PREPARATIONS + ": ");
                assertThat(((JMenuItem) menu.getComponent(5)).getText())
                        .startsWith(ActivitiesView.PARALLEL_DOWNLOADS + ": ");
                assertThat(((JMenuItem) menu.getComponent(6)).getText()).startsWith(ActivitiesView.CONNECTIONS + ": ");
                assertThat(view.moreJobs.isEnabled()).isTrue();
            });
        } finally {
            context.shutdown();
        }
    }
}
