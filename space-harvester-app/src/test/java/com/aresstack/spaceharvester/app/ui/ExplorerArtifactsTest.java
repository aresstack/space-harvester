package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.chat.ChatNode;
import com.aresstack.spaceharvester.app.chat.ChatRecord;
import com.aresstack.spaceharvester.app.chat.ChatStore;
import com.aresstack.spaceharvester.app.chat.artifact.ChatArtifact;
import com.aresstack.spaceharvester.app.chat.artifact.ChatArtifactStore;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Links umschalten zwischen Wissensquellen und den globalen Artefakten des aktiven Projekts (ADR 0047 §5). */
class ExplorerArtifactsTest {

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
    void theToggleShowsTheProjectsArtifactsNewestFirstAndAClickOpensOne() throws Exception {
        Path files = Files.createDirectories(home.resolve("ablage"));
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                    - id: ablage
                      type: files
                      options:
                        directory: %s
                """.formatted(home.resolve("data"), files));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();

        // Erfundene Artefakte: ein Dokument und eine Grafik aus einem Chat, ein Buch des Projekts.
        ChatStore chats = new ChatStore(context::chatsDirectory);
        ChatArtifactStore store = new ChatArtifactStore(chats);
        store.projects(context.projects()::artifacts);
        ChatNode node = new ChatNode("ablage", null, null);
        ChatRecord chat = ChatRecord.fresh(1).plus(ChatRecord.Message.user("Schreib", 1), 1);
        chats.save(node, chat);
        Path folder = chats.folder(node);
        ChatArtifact notes = store.createDocument(folder, chat.id(), "Notizen Jahresabschluss", "# Kasse",
                ChatArtifact.Origin.AI);
        Thread.sleep(5);
        ChatArtifact flow = store.createGraphic(folder, chat.id(), "Ablauf Wareneingang",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"/>", ChatArtifact.Origin.AI);
        Thread.sleep(5);
        ChatArtifact book = store.createBook(null, "Lagerprozesse", "# Lager");

        List<String> opened = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        List<String> exported = new ArrayList<>();
        Explorer[] holder = new Explorer[1];
        SwingUtilities.invokeAndWait(() -> {
            Explorer explorer = new Explorer(context);
            holder[0] = explorer;
            explorer.artifacts(store, opened::add, (id, format) -> exported.add(id + ":" + format));
            assertThat(explorer.artifactsShown()).isFalse();

            explorer.view.select(Explorer.ARTIFACTS_TAB);
            assertThat(explorer.artifactsShown()).isTrue();
            ArtifactList list = explorer.artifactList();
            assertThat(list.shownIds()).containsExactly(book.id(), flow.id(), notes.id());

            // „Öffnen“ (wie der Klick) öffnet in der Mitte
            list.list().setSelectedIndex(1);
            ((javax.swing.JMenuItem) list.menu(list.list().getSelectedValue()).getComponent(0)).doClick();
            assertThat(opened).containsExactly(flow.id());
            // „Ausgeben ▸“: bei der Grafik nur SVG
            javax.swing.JMenu output = (javax.swing.JMenu) list.menu(list.list().getSelectedValue()).getComponent(1);
            assertThat(output.getItemCount()).isEqualTo(1);
            output.getItem(0).doClick();
            assertThat(exported).containsExactly(flow.id() + ":null");

            // Das Filterfeld filtert nach Titel
            list.filter("lager");
            assertThat(list.shownIds()).containsExactly(book.id());
            list.filter("");

            // Löschen nur nach „Ja“
            list.confirm = (title, question) -> {
                asked.add(question);
                return true;
            };
            list.delete(store.readInProject(null, notes.id()));
            assertThat(asked).containsExactly(Texts.artifactDeleteQuestion("Notizen Jahresabschluss"));
        });
        // Die Meldung des Speichers kommt über den EDT
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> {
            ArtifactList list = holder[0].artifactList();
            // Gelöscht steht es durchgestrichen am Ende, bis zum nächsten Start; ein Klick holt es zurück.
            assertThat(list.shownIds()).containsExactly(book.id(), flow.id(), notes.id());
            assertThat(list.menu(list.list().getModel().getElementAt(2)).getComponentCount()).isEqualTo(1);
            list.restore(list.list().getModel().getElementAt(2));
            assertThat(opened).containsExactly(flow.id(), notes.id());
            assertThat(store.listInProject(null)).extracting(ChatArtifact::id)
                    .containsExactlyInAnyOrder(book.id(), flow.id(), notes.id());

            list.askTitle = current -> "Lagerprozesse 2027";
            list.rename(store.readInProject(null, book.id()));
        });
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(store.readInProject(null, book.id()).title()).isEqualTo("Lagerprozesse 2027");

        // Ein anderes Projekt hat eigene Artefakte; der Wechsel kommt über den Beobachter
        var other = context.projects().create("Umzug");
        context.projects().activate(other.id());
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> {
            assertThat(holder[0].artifactList().shownIds()).isEmpty();
            // Zurück zu den Wissensquellen: der Baum ist wieder da
            holder[0].view.select(Explorer.SOURCES_TAB);
            assertThat(holder[0].artifactsShown()).isFalse();
        });
    }

    @Test
    void aStaleBookShowsTheMarkAndOffersANewEditionAndItsFolder() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                """.formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        // Erfunden: ein Buch aus einem Ordner, dessen Seite seither nicht mehr im Ordner liegt (also veraltet).
        Path data = context.settings().storage().dataDirectory();
        var page = new com.aresstack.spaceharvester.app.research.BookEditions.Source("wiki", "1", null);
        com.aresstack.spaceharvester.app.research.BookEditions.add(data, "ordner-lager",
                java.time.Instant.parse("2026-11-27T10:00:00Z"), "# Lager", "",
                List.of(new com.aresstack.spaceharvester.app.research.BookEditions.Source("wiki", "1", "v1")),
                List.of(page));
        ChatArtifact book = context.bookArtifacts().publish("ordner-lager", null, "Lagerbuch");
        ChatStore chats = new ChatStore(context::chatsDirectory);
        ChatArtifactStore store = new ChatArtifactStore(chats);
        store.projects(context.projects()::artifacts);

        List<String> editions = new ArrayList<>();
        List<String> folders = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            Explorer explorer = new Explorer(context);
            ArtifactList list = explorer.artifactList();
            list.background = (work, done) -> {
                try {
                    done.accept(work.call());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
            explorer.artifacts(store, id -> { }, (id, format) -> { });
            list.books(editions::add, folders::add);
            explorer.view.select(Explorer.ARTIFACTS_TAB);
            assertThat(list.shownIds()).containsExactly(book.id());
            assertThat(list.staleBooks()).containsExactly(book.id());

            javax.swing.JPopupMenu menu = list.menu(list.list().getModel().getElementAt(0));
            List<String> labels = new ArrayList<>();
            for (java.awt.Component item : menu.getComponents()) {
                if (item instanceof javax.swing.JMenuItem entry) {
                    labels.add(entry.getText());
                    if (Texts.NEW_EDITION.equals(entry.getText()) || ArtifactList.SHOW_FOLDER.equals(entry.getText())) {
                        entry.doClick();
                    }
                }
            }
            assertThat(labels).contains(Texts.NEW_EDITION, ArtifactList.SHOW_FOLDER);
        });
        assertThat(editions).containsExactly("ordner-lager");
        assertThat(folders).containsExactly("ordner-lager");
    }

    @Test
    void theTimeShowsTheClockTodayAndTheDateOtherwise() {
        java.time.ZoneId zone = java.time.ZoneId.of("Europe/Berlin");
        java.time.LocalDate today = java.time.LocalDate.of(2026, 11, 27);
        long noon = java.time.ZonedDateTime.of(2026, 11, 27, 14, 2, 0, 0, zone).toInstant().toEpochMilli();
        long earlier = java.time.ZonedDateTime.of(2026, 11, 23, 9, 0, 0, 0, zone).toInstant().toEpochMilli();
        assertThat(ArtifactList.when(noon, zone, today)).isEqualTo("14:02");
        assertThat(ArtifactList.when(earlier, zone, today)).isEqualTo("23.11.");
    }
}
