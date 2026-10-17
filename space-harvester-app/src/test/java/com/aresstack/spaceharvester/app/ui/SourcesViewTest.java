package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.content.ContentView;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.swing.AbstractButton;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Wissensquellen als Karten: je Quelle ein Verbindungszeichen und Symbolknöpfe statt einer Knopfleiste, die Spaces
 * als Hauptinhalt mit Filter; ein frisch geöffneter Space ohne Bestand lädt live statt leer zu bleiben.
 */
class SourcesViewTest {

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
    void cardsCarryStatusAndActionsAndSpacesAreTheMainContent() throws Exception {
        start();
        SourcesView[] view = new SourcesView[1];
        SwingUtilities.invokeAndWait(() -> view[0] = new SourcesView(context));
        Container root = (Container) view[0].component();

        // Keine Knopfleiste mehr, weder über den Details noch über der Space-Tabelle.
        assertThat(find(root, AbstractButton.class)).noneMatch(b -> List.of("Verbindung prüfen", "Bearbeiten …",
                "Entfernen …", "Öffnen", "Synchronisieren", "Exportieren …").contains(b.getText()));
        List<IconButton> syncs = buttons(root, "Gewählten Space");
        List<IconButton> pencils = buttons(root, "Einstellungen der Quelle");
        assertThat(syncs).hasSize(2);
        assertThat(pencils).hasSize(2);

        List<StatusMark> marks = find(root, StatusMark.class);
        assertThat(marks).hasSize(2);
        // Die App prüft beim Start selbst; danach steht der Haken an der Karte.
        await(() -> marks.get(1).state() == StatusMark.State.CONNECTED);
        assertThat(marks.get(1).getToolTipText()).isEqualTo("Verbunden · 1 Space");

        // Spaces ist der erste Reiter; der Stift an der Karte öffnet die Einstellungen.
        assertThat(view[0].selectedTab()).isEqualTo(SourcesView.SPACES);
        SwingUtilities.invokeAndWait(() -> pencils.get(1).doClick());
        assertThat(view[0].selectedTab()).isEqualTo(SourcesView.SETTINGS);

        // Ohne gewählten Space startet der Kreispfeil nichts, sondern bittet um eine Auswahl.
        JTable spaces = find(root, JTable.class).get(0);
        await(() -> spaces.getRowCount() == 1);
        assertThat(spaces.getSelectedRow()).isEqualTo(-1);
        SwingUtilities.invokeAndWait(() -> syncs.get(1).doClick());
        assertThat(view[0].selectedTab()).isEqualTo(SourcesView.SPACES);
        assertThat(context.runtime().workspace().jobs()).isEmpty();

        JTextField filter = find(root, JTextField.class).get(0);
        SwingUtilities.invokeAndWait(() -> filter.setText("gibt es nicht"));
        assertThat(spaces.getRowCount()).isZero();
        SwingUtilities.invokeAndWait(() -> filter.setText(""));
        assertThat(spaces.getRowCount()).isEqualTo(1);
    }

    private static List<IconButton> buttons(Container root, String tooltip) throws Exception {
        List<IconButton> found = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> find(root, IconButton.class).stream()
                .filter(b -> b.getToolTipText().startsWith(tooltip)).forEach(found::add));
        return found;
    }

    @Test
    void settingsAreEditedInPlaceWithThePencil() throws Exception {
        start();
        SourcesView[] view = new SourcesView[1];
        SwingUtilities.invokeAndWait(() -> view[0] = new SourcesView(context));
        Container root = (Container) view[0].component();
        EditableProperty[] name = new EditableProperty[1];
        SwingUtilities.invokeAndWait(() -> {
            name[0] = find(root, EditableProperty.class).stream().filter(p -> p.name().equals("Anzeigename"))
                    .findFirst().orElseThrow();
            name[0].edit();
        });
        assertThat(name[0].editing()).isTrue();
        // Eine Bearbeitung je Quelle: Der Ordner schließt den offenen Anzeigenamen, der Name kommt danach wieder.
        SwingUtilities.invokeAndWait(() -> find(root, EditableProperty.class).stream()
                .filter(p -> p.name().equals("Ordner")).findFirst().orElseThrow().edit());
        assertThat(name[0].editing()).isFalse();
        SwingUtilities.invokeAndWait(name[0]::edit);
        assertThat(find(root, EditableProperty.class)).filteredOn(EditableProperty::editing).containsExactly(name[0]);
        SwingUtilities.invokeAndWait(() -> {
            name[0].field().setText("Neuer Name");
            name[0].field().postActionEvent();
        });
        Path config = home.resolve("app.yml");
        await(() -> {
            try {
                return Files.readString(config).contains("title: \"Neuer Name\"");
            } catch (java.io.IOException e) {
                return false;
            }
        });
        await(() -> find(root, javax.swing.JTextArea.class).stream().anyMatch(a -> "Neuer Name".equals(a.getText())));
        // Die übrige Datei bleibt, wie sie war; die zweite Quelle ist unverändert.
        assertThat(Files.readString(config)).contains("Kurz");

        // Ungültiges bleibt im Feld stehen, der Grund steht darunter, die Datei ändert sich nicht.
        String before = Files.readString(config);
        EditableProperty[] folder = new EditableProperty[1];
        SwingUtilities.invokeAndWait(() -> {
            folder[0] = find(root, EditableProperty.class).stream().filter(p -> p.name().equals("Ordner"))
                    .findFirst().orElseThrow();
            folder[0].edit();
            folder[0].field().setText("");
            folder[0].field().postActionEvent();
        });
        await(() -> folder[0].error() != null);
        assertThat(folder[0].editing()).isTrue();
        assertThat(Files.readString(config)).isEqualTo(before);
    }

    @Test
    void longNamesWrapInsteadOfWideningTheCard() throws Exception {
        start();
        SourcesView[] view = new SourcesView[1];
        SwingUtilities.invokeAndWait(() -> view[0] = new SourcesView(context));
        int[] heights = new int[2];
        SwingUtilities.invokeAndWait(() -> {
            List<Component> cards = new ArrayList<>();
            for (StatusMark mark : find((Container) view[0].component(), StatusMark.class)) {
                cards.add(mark.getParent());
            }
            // Gleiche Breite; der lange Name braucht mehr Zeilen und damit mehr Höhe.
            heights[0] = ((Container) cards.get(0)).getPreferredSize().height;
            heights[1] = ((Container) cards.get(1)).getPreferredSize().height;
        });
        assertThat(heights[0]).isGreaterThan(heights[1]);
    }

    @Test
    void openingASpaceWithoutLocalCopyLoadsItLive() throws Exception {
        start();
        ContentView[] view = new ContentView[1];
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new ContentView(context);
            view[0].openSpace("kurz", context.runtime().workspace().spaces("kurz").get(0).key());
        });
        JTable pages = find((Container) view[0].component(), JTable.class).get(0);
        await(() -> pages.getRowCount() == 3);
        // Ohne Bestand kommt die Liste live, ohne Fehlermeldung; die Ansicht sagt es und bietet die Übernahme an.
        assertThat(find((Container) view[0].component(), javax.swing.JLabel.class))
                .anySatisfy(label -> assertThat(label.getText()).startsWith("Seiten werden live"));
        assertThat(find((Container) view[0].component(), AbstractButton.class))
                .anySatisfy(button -> assertThat(button.getText()).isEqualTo("Space synchronisieren"));
    }

    private void start() throws Exception {
        StringBuilder sources = new StringBuilder();
        for (String id : List.of("lang", "kurz")) {
            Path files = Files.createDirectories(home.resolve(id));
            for (int i = 0; i < 3; i++) {
                Files.writeString(files.resolve("seite-%d.md".formatted(i)), "# Seite\n\nText %d.\n".formatted(i));
            }
            sources.append("""
                        - id: %s
                          type: files
                          title: %s
                          options:
                            directory: %s
                    """.formatted(id, id.equals("lang")
                    ? "Ein ausgesprochen langer Anzeigename für eine Quelle, der umbrechen muss" : "Kurz", files));
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
