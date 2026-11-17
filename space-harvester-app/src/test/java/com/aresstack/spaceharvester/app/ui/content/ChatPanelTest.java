package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.chat.ChatNode;
import com.aresstack.spaceharvester.app.chat.ChatRecord;
import com.aresstack.spaceharvester.app.chat.ChatStore;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection;
import com.aresstack.spaceharvester.application.holdings.HoldingCounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Statuszeile des Wissens im Chat sagt ehrlich, was links gewählt ist, was fehlt und was die KI sehen darf; die
 * Kopfzeile nennt den Chat des gewählten Bereichs, Umbenennen hält die Kennung, Senden ohne Chat-Modell sagt das.
 */
class ChatPanelTest {

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
    void chatsBelongToTheSelectedNodeAndRenamingKeepsTheId() throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        Files.writeString(files.resolve("seite.md"), "# Seite\n\nText.\n");
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                    - id: handbuch
                      type: files
                      title: Handbuch
                      options:
                        directory: %s
                """.formatted(home.resolve("data"), files));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        String space = context.runtime().workspace().spaces("handbuch").get(0).key();
        ChatStore store = new ChatStore(() -> home.resolve("data").resolve(ChatStore.FOLDER));
        ChatNode spaceNode = new ChatNode("handbuch", space, null);
        ChatRecord earlier = ChatRecord.fresh(1_000).plus(ChatRecord.Message.user("Frühere Frage", 1_000), 1_000)
                .plus(ChatRecord.Message.assistant("Frühere Antwort", false, 1_500), 1_500);
        store.save(spaceNode, earlier);

        ChatPanel[] panel = new ChatPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new ChatPanel(context);
            assertThat(panel[0].knowledge()).isEqualTo(ChatPanel.NO_SELECTION);
            assertThat(panel[0].chat().isEmpty()).isTrue();
            // Der Space zeigt seinen zuletzt geänderten Chat samt Verlauf.
            panel[0].selection(new Selection("handbuch", space, null, "Handbuch"));
            assertThat(panel[0].chat().id()).isEqualTo(earlier.id());
            assertThat(panel[0].chatName()).isEqualTo(earlier.id());
            assertThat(panel[0].bubbles()).isEqualTo(2);
            // Umbenennen an Ort und Stelle: der Name wechselt, die Kennung und die Datei bleiben.
            panel[0].startRename();
            assertThat(panel[0].renamingShown()).isTrue();
            panel[0].chatNameEditor().setText("Zugriffsrechte");
            panel[0].finishRename(true);
            assertThat(panel[0].renamingShown()).isFalse();
            assertThat(panel[0].chatName()).isEqualTo("Zugriffsrechte");
            assertThat(panel[0].chat().id()).isEqualTo(earlier.id());
            // Die ganze Quelle ist ein anderer Bereich: dort gibt es noch keinen Chat.
            panel[0].selection(new Selection("handbuch", null, null, "Handbuch"));
            assertThat(panel[0].chat().isEmpty()).isTrue();
            assertThat(panel[0].bubbles()).isZero();
            assertThat(panel[0].chatName()).isEqualTo(panel[0].chat().id());
        });
        // Senden geht erst, wenn der Core die Auswahl aufgelöst hat (im Hintergrund); ohne Chat-Modell bleibt die
        // Frage stehen und die Statuszeile sagt, was fehlt.
        long end = System.currentTimeMillis() + 30_000;
        while (!panel[0].sendButton().isEnabled() && System.currentTimeMillis() < end) {
            Thread.sleep(50);
        }
        SwingUtilities.invokeAndWait(() -> {
            ((javax.swing.JTextArea) panel[0].editor()).setText("Worum geht es hier?");
            panel[0].send();
            assertThat(panel[0].status()).isEqualTo(ChatPanel.NO_CHAT_MODEL);
            assertThat(panel[0].bubbles()).isZero();
        });
        assertThat(store.load(spaceNode, earlier.id()).title()).isEqualTo("Zugriffsrechte");
    }

    @Test
    void theKnowledgeLineNamesTheSelectionItsPagesAndWhatIsMissing() {
        assertThat(ChatPanel.knowledgeText(null, null, true, true)).isEqualTo(ChatPanel.NO_SELECTION);
        Selection page = new Selection("wiki", "DEV", "42", "Handbuch");
        assertThat(ChatPanel.knowledgeText(page, new HoldingCounts(120, 120, 0, 0), true, true))
                .isEqualTo("Wissen: Handbuch samt Unterseiten · 120 Seiten");
        assertThat(ChatPanel.knowledgeText(page, new HoldingCounts(120, 80, 0, 0), true, true))
                .isEqualTo("Wissen: Handbuch samt Unterseiten · 120 Seiten · 40 davon nicht geladen");
        assertThat(ChatPanel.knowledgeText(page, new HoldingCounts(1, 1, 0, 0), true, true))
                .isEqualTo("Wissen: Handbuch samt Unterseiten · 1 Seite");
        Selection space = new Selection("wiki", "DEV", null, "Entwicklung");
        assertThat(ChatPanel.knowledgeText(space, HoldingCounts.ZERO, true, true))
                .isEqualTo("Wissen: Space Entwicklung · " + ChatPanel.NOT_LOADED);
        assertThat(ChatPanel.knowledgeText(page, new HoldingCounts(3, 3, 0, 0), false, true))
                .isEqualTo("Wissen: Handbuch samt Unterseiten · 3 Seiten · " + ExportPanel.INCOMPLETE);
        assertThat(ChatPanel.knowledgeText(space, null, true, true))
                .isEqualTo("Wissen: Space Entwicklung · " + ChatPanel.NOT_LOADED);
        Selection source = new Selection("wiki", null, null, "Beta");
        // Der Chat ist die Ausnahme der KI-Freigabe (Angelo, 13.11.2026): genannt, nicht gesperrt.
        assertThat(ChatPanel.knowledgeText(source, new HoldingCounts(2010, 176, 8, 0), true, false))
                .isEqualTo("Wissen: Beta · alle Spaces · 2010 Seiten · 1826 davon nicht geladen · "
                        + ChatPanel.NOT_ALLOWED);
        // Die globale Auswahl (Überschrift „Wissensquellen“, Angelo 16.11.2026).
        assertThat(Selection.ALL.global()).isTrue();
        assertThat(ChatPanel.knowledgeText(Selection.ALL, new HoldingCounts(10, 10, 0, 0), true, true))
                .isEqualTo("Wissen: alle Wissensquellen · 10 Seiten");
    }
}
