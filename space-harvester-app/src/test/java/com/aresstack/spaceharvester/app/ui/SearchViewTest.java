package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.swing.AbstractButton;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Die Suche der App ist die globale Suche des Core (#114): „Alle Quellen“ liefert eine global sortierte Liste über
 * beide Quellen mit gleichen Seiten-IDs (Dateiquellen: Titel und ID aus dem Dateinamen), und „Weitere laden“ folgt dem Cursor über die ersten 100 Treffer hinaus.
 */
class SearchViewTest {

    private static final int PAGES_PER_SOURCE = 60;

    @TempDir
    Path home;

    private AppContext context;

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
    }

    @Test
    void allSourcesIsOneGlobalCoreSearchWithLoadMore() throws Exception {
        StringBuilder sources = new StringBuilder();
        for (String id : List.of("alpha", "beta")) {
            Path files = Files.createDirectories(home.resolve(id));
            for (int i = 0; i < PAGES_PER_SOURCE; i++) {
                Files.writeString(files.resolve("seite-%03d.md".formatted(i)), "# Betrieb\n\nText %03d.\n".formatted(i));
            }
            sources.append("""
                        - id: %s
                          type: files
                          options:
                            directory: %s
                    """.formatted(id, files));
        }
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                %s""".formatted(home.resolve("data"), sources));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        Workspace workspace = context.runtime().workspace();
        for (String id : List.of("alpha", "beta")) {
            String job = workspace.startSync(id, workspace.spaces(id).get(0).key()).id();
            await(() -> workspace.job(job).state().terminal());
        }

        SearchView[] view = new SearchView[1];
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new SearchView(context);
            view[0].search("seite");
        });
        JTable table = find(view[0].component(), JTable.class).get(0);
        AbstractButton more = button(view[0].component(), "Weitere laden");
        await(() -> table.getRowCount() == 100);
        await(more::isEnabled);

        SwingUtilities.invokeAndWait(more::doClick);
        await(() -> table.getRowCount() == 2 * PAGES_PER_SOURCE);
        await(() -> !more.isEnabled());

        List<String> titles = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        Set<String> sourcesSeen = new HashSet<>();
        SwingUtilities.invokeAndWait(() -> {
            for (int row = 0; row < table.getRowCount(); row++) {
                titles.add((String) table.getModel().getValueAt(row, 0));
                String sourceId = (String) table.getModel().getValueAt(row, 1);
                sourcesSeen.add(sourceId);
                keys.add(sourceId + "/" + table.getModel().getValueAt(row, 3));
            }
        });
        // Global nach Titel sortiert: gleiche Titel beider Quellen stehen nebeneinander, nichts doppelt oder fehlend.
        assertThat(titles.subList(0, 4)).containsExactly("seite-000.md", "seite-000.md", "seite-001.md", "seite-001.md");
        assertThat(titles).isSortedAccordingTo(String::compareTo);
        assertThat(keys).hasSize(2 * PAGES_PER_SOURCE);
        assertThat(sourcesSeen).containsExactlyInAnyOrder("alpha", "beta");
    }

    private static void await(BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 600; i++) {
            boolean[] done = new boolean[1];
            SwingUtilities.invokeAndWait(() -> done[0] = condition.getAsBoolean());
            if (done[0]) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Bedingung nicht erreicht");
    }

    private static AbstractButton button(Container container, String text) {
        return find(container, AbstractButton.class).stream().filter(button -> text.equals(button.getText()))
                .findFirst().orElseThrow();
    }

    private static <T> List<T> find(Container container, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (type.isInstance(child)) {
                found.add(type.cast(child));
            }
            if (child instanceof Container nested) {
                found.addAll(find(nested, type));
            }
        }
        return found;
    }
}
