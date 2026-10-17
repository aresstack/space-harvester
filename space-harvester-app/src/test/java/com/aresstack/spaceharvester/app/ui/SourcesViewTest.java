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
import java.awt.event.MouseEvent;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
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

        List<IconButton> checks = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> find(root, IconButton.class).stream()
                .filter(b -> b.getToolTipText().startsWith("Verbindung prüfen")).forEach(checks::add));
        assertThat(checks).hasSize(2);
        // Keine Knopfleiste mehr über den Details, keine „Verbunden“-Box.
        assertThat(find(root, AbstractButton.class)).noneMatch(b -> "Verbindung prüfen".equals(b.getText())
                || "Bearbeiten …".equals(b.getText()) || "Entfernen …".equals(b.getText()));

        List<StatusMark> marks = find(root, StatusMark.class);
        assertThat(marks).hasSize(2);

        SwingUtilities.invokeAndWait(() -> checks.get(1).doClick());
        await(() -> marks.get(1).state() == StatusMark.State.CONNECTED);
        assertThat(marks.get(1).getToolTipText()).isEqualTo("Verbunden · 1 Space");

        JTable spaces = find(root, JTable.class).get(0);
        await(() -> spaces.getRowCount() == 1);
        JTextField filter = find(root, JTextField.class).get(0);
        SwingUtilities.invokeAndWait(() -> filter.setText("gibt es nicht"));
        assertThat(spaces.getRowCount()).isZero();
        SwingUtilities.invokeAndWait(() -> filter.setText(""));
        assertThat(spaces.getRowCount()).isEqualTo(1);
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

    @Test
    void doubleClickingASpaceOpensExactlyThatSpaceInContents() throws Exception {
        start();
        MainWindow[] window = new MainWindow[1];
        JPanel[] surface = new JPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            window[0] = new MainWindow(context);
            surface[0] = window[0].content(() -> { }, () -> { });
            surface[0].setSize(1280, 820);
            window[0].show(AppContext.Section.SOURCES);
        });
        JTable spaces = find(surface[0], JTable.class).stream()
                .filter(table -> table.getColumnCount() > 1 && "Schlüssel".equals(table.getColumnName(1)))
                .findFirst().orElseThrow();
        await(() -> spaces.getRowCount() > 0);
        String[] key = new String[1];
        SwingUtilities.invokeAndWait(() -> {
            spaces.setRowSelectionInterval(0, 0);
            key[0] = String.valueOf(spaces.getValueAt(0, 1));
            java.awt.Rectangle cell = spaces.getCellRect(0, 0, true);
            spaces.dispatchEvent(new MouseEvent(spaces, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                    cell.x + 2, cell.y + 2, 2, false, MouseEvent.BUTTON1));
        });

        JComboBox<?> space = find(surface[0], JComboBox.class).stream()
                .filter(combo -> "Space wählen oder Space-Key eingeben".equals(combo.getToolTipText()))
                .findFirst().orElseThrow();
        await(() -> key[0].equals(String.valueOf(space.getEditor().getItem())));
        await(() -> find(surface[0], JTable.class).stream().anyMatch(table -> table != spaces
                && table.getRowCount() == 3));
        // Auch nach der nächsten Verbindungsprüfung bleibt der geöffnete Space gewählt.
        CountDownLatch checked = new CountDownLatch(2);
        SwingUtilities.invokeAndWait(() -> context.runtime().workspace().sources()
                .forEach(source -> context.checkConnection(source.id(), checked::countDown)));
        assertThat(checked.await(30, TimeUnit.SECONDS)).isTrue();
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(String.valueOf(space.getEditor().getItem())).isEqualTo(key[0]);
        assertThat(space.getSelectedItem()).isEqualTo(key[0]);
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
