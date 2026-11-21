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

    private static java.util.List<String> menuTexts(ChatPanel panel) {
        return panel.chatMenuItems().stream()
                .map(item -> item instanceof javax.swing.JMenuItem menu ? menu.getText() : "—").toList();
    }

    @Test
    void theOpenChatStaysWhileNavigatingAndRenamingKeepsTheId() throws Exception {
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
        // Ein Chat, dessen zweite Frage aus einem anderen Bereich kam; dazwischen hat der Bestand nur nachgeladen.
        ChatRecord earlier = ChatRecord.fresh(1_000)
                .plus(ChatRecord.Message.user("Frühere Frage", 1_000, "Space Handbuch · 1 Seite"), 1_000)
                .plus(ChatRecord.Message.assistant("Frühere Antwort", false, 1_500), 1_500)
                .plus(ChatRecord.Message.user("Noch eine", 1_600, "Space Handbuch · 3 Seiten · 1 davon nicht geladen"),
                        1_600)
                .plus(ChatRecord.Message.user("Und im Ganzen?", 1_700, "Handbuch · alle Spaces · 3 Seiten"), 1_700)
                .plus(ChatRecord.Message.assistant("Antwort im Ganzen", false, 1_800), 1_800);
        store.save(spaceNode, earlier);
        ChatNode sourceNode = new ChatNode("handbuch", null, null);
        ChatRecord other = ChatRecord.fresh(500).plus(ChatRecord.Message.user("Ganz andere Frage", 500), 500);
        store.save(sourceNode, other);
        assertThat(store.all()).extracting(entry -> entry.record().id()).containsExactly(earlier.id(), other.id());
        assertThat(ChatPanel.areaName("Space Handbuch · 3 Seiten · 1 davon nicht geladen")).isEqualTo("Space Handbuch");
        assertThat(ChatPanel.areaName("Handbuch · alle Spaces · " + ChatPanel.NOT_LOADED))
                .isEqualTo("Handbuch · alle Spaces");

        ChatPanel[] panel = new ChatPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new ChatPanel(context);
            assertThat(panel[0].knowledge()).isEqualTo(ChatPanel.NO_SELECTION);
            assertThat(panel[0].chat().isEmpty()).isTrue();
            // Der Space zeigt seinen zuletzt geänderten Chat samt Verlauf.
            panel[0].selection(new Selection("handbuch", space, null, "Handbuch"));
            assertThat(panel[0].chat().id()).isEqualTo(earlier.id());
            assertThat(panel[0].chatName()).isEqualTo(earlier.id());
            assertThat(panel[0].bubbles()).isEqualTo(5);
            // Bereichswechsel im Verlauf: eine Marke vor der Frage aus dem anderen Bereich, keine beim Nachladen.
            assertThat(panel[0].areaMarks()).containsExactly(ChatPanel.FROM_HERE + "Handbuch · alle Spaces · 3 Seiten");
            // Umbenennen an Ort und Stelle: der Name wechselt, die Kennung und die Datei bleiben.
            panel[0].startRename();
            assertThat(panel[0].renamingShown()).isTrue();
            panel[0].chatNameEditor().setText("Zugriffsrechte");
            panel[0].finishRename(true);
            assertThat(panel[0].renamingShown()).isFalse();
            assertThat(panel[0].chatName()).isEqualTo("Zugriffsrechte");
            assertThat(panel[0].chat().id()).isEqualTo(earlier.id());
            // Die ganze Quelle ist ein anderer Bereich: der offene Chat bleibt stehen (Angelo, 21.11.2026), nur der
            // Bereich der nächsten Frage wandert.
            panel[0].selection(new Selection("handbuch", null, null, "Handbuch"));
            assertThat(panel[0].chat().id()).isEqualTo(earlier.id());
            assertThat(panel[0].bubbles()).isEqualTo(5);
            assertThat(panel[0].knowledge()).startsWith("Wissen: Handbuch · alle Spaces");
            // Unter ▾ zuerst die Chats des gewählten Bereichs, darunter abgetrennt alle übrigen; der offene mit Haken.
            assertThat(menuTexts(panel[0])).containsExactly(ChatPanel.CHATS_HERE, "    " + other.id(), "—",
                    ChatPanel.CHATS_ELSEWHERE, "✓ Zugriffsrechte");
            // Ein Chat aus der Liste öffnet sich aus seinem eigenen Bereich und bleibt dort gespeichert.
            ((javax.swing.JMenuItem) panel[0].chatMenuItems().get(1)).doClick();
            assertThat(panel[0].chat().id()).isEqualTo(other.id());
            assertThat(panel[0].areaMarks()).isEmpty();
            panel[0].selection(new Selection("handbuch", space, null, "Handbuch"));
            assertThat(panel[0].chat().id()).isEqualTo(other.id());
            assertThat(menuTexts(panel[0])).containsExactly(ChatPanel.CHATS_HERE, "    Zugriffsrechte", "—",
                    ChatPanel.CHATS_ELSEWHERE, "✓ " + other.id());
            // Ein neuer, leerer Chat gehört noch keinem Bereich: er zieht mit der Auswahl mit.
            javax.swing.AbstractButton fresh = (javax.swing.AbstractButton)
                    ((java.awt.Container) panel[0].headerActions()).getComponent(0);
            fresh.doClick();
            assertThat(panel[0].chat().isEmpty()).isTrue();
            panel[0].selection(new Selection("handbuch", null, null, "Handbuch"));
            assertThat(panel[0].chat().isEmpty()).as("kein gespeicherter Chat springt mehr herein").isTrue();
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
        // Der Composer wächst mit dem Text bis zu zwei Fünfteln der Höhe und schrumpft danach wieder.
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.JTextArea editor = (javax.swing.JTextArea) panel[0].editor();
            javax.swing.JScrollPane scroll = (javax.swing.JScrollPane) editor.getParent().getParent();
            panel[0].setSize(900, 700);
            panel[0].setNormalEditorHeight(64);
            assertThat(panel[0].resizeGrip().shown()).isFalse();
            editor.setText("");
            int small = scroll.getPreferredSize().height;
            editor.setText("Erste Zeile\nZweite Zeile\nDritte Zeile\nVierte Zeile");
            int grown = scroll.getPreferredSize().height;
            editor.setText("Zeile\n".repeat(80));
            int capped = scroll.getPreferredSize().height;
            editor.setText("");
            assertThat(grown).isGreaterThan(small);
            assertThat(capped).isEqualTo(700 * 2 / 5);
            assertThat(scroll.getPreferredSize().height).isEqualTo(small);
            // Der Ziehgriff legt die Normalhöhe des leeren Editors fest; lange Texte wachsen trotzdem weiter.
            panel[0].setNormalEditorHeight(150);
            assertThat(scroll.getPreferredSize().height).isEqualTo(150);
            editor.setText("Kurz");
            assertThat(scroll.getPreferredSize().height).isEqualTo(150);
            editor.setText("Zeile\n".repeat(80));
            assertThat(scroll.getPreferredSize().height).isEqualTo(700 * 2 / 5);
            editor.setText("");
            assertThat(scroll.getPreferredSize().height).isEqualTo(150);
            panel[0].setNormalEditorHeight(10);
            assertThat(panel[0].normalEditorHeight()).isEqualTo(64);
            panel[0].setNormalEditorHeight(5000);
            assertThat(panel[0].normalEditorHeight()).isEqualTo(700 * 2 / 5);
            panel[0].resizeGrip().setShown(true);
            assertThat(panel[0].resizeGrip().shown()).isTrue();
        });
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
