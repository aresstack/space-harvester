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

    private static void layoutAll(java.awt.Component component) {
        component.doLayout();
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                layoutAll(child);
            }
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
                .plus(ChatRecord.Message.user("Frühere Frage", 1_000, "Space Handbuch · 1 Seite", null), 1_000)
                .plus(ChatRecord.Message.assistant("Frühere Antwort", false, 1_500), 1_500)
                .plus(ChatRecord.Message.user("Noch eine", 1_600, "Space Handbuch · 3 Seiten · 1 davon nicht geladen",
                        null), 1_600)
                .plus(ChatRecord.Message.user("Und im Ganzen?", 1_700, "Handbuch · alle Spaces · 3 Seiten", null), 1_700)
                .plus(ChatRecord.Message.assistant("Antwort im Ganzen [1]", false, 1_800, java.util.List.of(
                        new ChatRecord.Source("Seite", "handbuch", "seite.md", null),
                        new ChatRecord.Source("notiz.txt", "handbuch", null, null),
                        new ChatRecord.Source("Ein sehr langer Titel, der in der schmalen Leiste nicht in eine Zeile "
                                + "passt", "handbuch", "lang.md", null))), 1_800);
        store.save(spaceNode, earlier);
        ChatNode sourceNode = new ChatNode("handbuch", null, null);
        ChatRecord other = ChatRecord.fresh(500).plus(ChatRecord.Message.user("Ganz andere Frage", 500), 500);
        store.save(sourceNode, other);
        assertThat(store.all()).extracting(entry -> entry.record().id()).containsExactly(earlier.id(), other.id());
        assertThat(ChatPanel.areaName("Space Handbuch · 3 Seiten · 1 davon nicht geladen")).isEqualTo("Space Handbuch");
        // Zwei gleich benannte Spaces sind verschiedene Bereiche: es zählt die Kennung, nicht der Name.
        ChatRecord.Message asked = ChatRecord.Message.user("Frage", 1, "Space Handbuch · 2 Seiten",
                ChatPanel.scopeKey(new ChatNode("a", "HB", null), false));
        assertThat(ChatPanel.sameArea(asked, "Space Handbuch · 5 Seiten",
                ChatPanel.scopeKey(new ChatNode("a", "HB", null), false))).isTrue();
        assertThat(ChatPanel.sameArea(asked, "Space Handbuch · 2 Seiten",
                ChatPanel.scopeKey(new ChatNode("b", "HB", null), false))).isFalse();
        assertThat(ChatPanel.sameArea(asked, "Space Handbuch · 2 Seiten",
                ChatPanel.scopeKey(new ChatNode("a", "HB", "p1"), true))).isFalse();
        // Die Adresse eines Chats ist relativ zum Chat-Ordner und gilt auch nach einem Wechsel des Datenordners;
        // nichts außerhalb wird geschrieben.
        assertThat(store.folder(spaceNode)).isRelative();
        store.save(Path.of("..", "draussen"), earlier);
        assertThat(home.resolve("data").resolve("draussen")).doesNotExist();
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
            // Quellen stehen als Links unter ihrer Antwort, in der Nummer der Antwort; ohne Quellen keine Zeile. Ein
            // Klick öffnet die Seite links, ein Anhang ohne Seite ist nur Text, lange Titel kürzt „…“.
            java.util.List<String> opened = new java.util.ArrayList<>();
            Runnable[] revealed = {() -> opened.add("gezeigt")};
            context.setNavigator((AppContext.Navigator) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {AppContext.Navigator.class}, (proxy, method, args) -> {
                        if (method.getName().equals("openPage")) {
                            opened.add(args[0] + "/" + args[1]);
                        }
                        return null;
                    }));
            panel[0].onSourceOpened(() -> revealed[0].run());
            java.util.List<ChatPanel.SourceLinks> links = panel[0].sourceLinks();
            assertThat(links).hasSize(2);
            assertThat(links.get(0).isVisible()).isFalse();
            assertThat(links.get(1).isVisible()).isTrue();
            assertThat(links.get(1).texts()).containsExactly("[1] Seite", "[2] notiz.txt",
                    "[3] Ein sehr langer Titel, der in der schmalen Leis…");
            assertThat(links.get(1).getComponent(1)).isInstanceOf(javax.swing.JLabel.class);
            javax.swing.JButton first = (javax.swing.JButton) links.get(1).getComponent(0);
            assertThat(first.getToolTipText()).isEqualTo("Seite öffnen");
            first.doClick();
            assertThat(opened).containsExactly("handbuch/seite.md", "gezeigt");
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
        // Die Fußzeile der Eingabe in der schmalen Leiste: erst die Modellwahl, dann der Denkaufwand nur als Symbol,
        // der Name steht im Tooltip; „Senden“ und die Symbole rechts überlappen nie.
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.AbstractButton sendButton = panel[0].sendButton();
            java.awt.Container footer = sendButton.getParent().getParent();
            java.awt.Container west = (java.awt.Container) footer.getComponent(0);
            javax.swing.AbstractButton model = (javax.swing.AbstractButton) west.getComponent(0);
            javax.swing.AbstractButton effort = (javax.swing.AbstractButton) west.getComponent(1);
            for (int width : new int[] {900, 420, 320, 300, 900}) {
                panel[0].setSize(width, 700);
                for (int pass = 0; pass < 3; pass++) {
                    layoutAll(panel[0]);
                    for (java.awt.event.ComponentListener listener : footer.getComponentListeners()) {
                        listener.componentResized(null);
                    }
                }
                layoutAll(panel[0]);
                assertThat(west.getPreferredSize().width + sendButton.getParent().getPreferredSize().width)
                        .as("Breite " + width).isLessThanOrEqualTo(footer.getWidth());
                assertThat(sendButton.getText()).isEqualTo(ChatPanel.SEND);
                assertThat(model.getToolTipText()).startsWith(ChatPanel.MODEL_TIP);
                assertThat(effort.getToolTipText()).isEqualTo(ChatPanel.EFFORT_TIP + ": Mittel");
                if (width == 900) {
                    assertThat(panel[0].footerStage()).isZero();
                    assertThat(model.getText()).isEqualTo(ChatPanel.NO_MODEL);
                    assertThat(effort.getText()).isEqualTo("Mittel");
                } else {
                    assertThat(panel[0].footerStage()).isPositive();
                    assertThat(model.getText()).isNullOrEmpty();
                    assertThat(model.getToolTipText()).isEqualTo(ChatPanel.MODEL_TIP + ": " + ChatPanel.NO_MODEL);
                }
            }
        });
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
    void theViewInFrontDecidesTheScopeAndTheTreeIsNeverTouched() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, "space-harvester-app:\n  data-directory: %s\n".formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        Selection source = new Selection("tickets", null, null, "Tickets");
        Selection ticket = new Selection("tickets", "PRJ", "PRJ-7", "PRJ-7 Nachtlauf bricht ab");
        Selection space = new Selection("tickets", "PRJ", null, "PRJ");
        Selection[] seen = new Selection[7];
        String[] quick = new String[1];
        int[] graphUpdates = new int[3];
        SwingUtilities.invokeAndWait(() -> {
            ChatPanel panel = new ChatPanel(context);
            panel.onResolved(resolved -> graphUpdates[0]++);
            // In der Suche: der Filter im Baum.
            panel.selection(source);
            seen[0] = panel.scope();
            graphUpdates[1] = graphUpdates[0];
            // Aus der Suche geöffnet, der Baum bleibt auf der Quelle; vorne ist jetzt die Leseansicht.
            panel.reading(ticket);
            seen[1] = panel.scope();
            panel.readingFront(true);
            seen[2] = panel.scope();
            // Der Graph bleibt auf der Auswahl im Baum: die gelesene Seite meldet ihm nichts.
            graphUpdates[2] = graphUpdates[0];
            quick[0] = String.join(" | ", panel.quickActionLabels());
            // Zurück in die Suche: wieder der Filter, auch wenn die Suche ihn neu setzt.
            panel.readingFront(false);
            panel.selection(space);
            seen[3] = panel.scope();
            // Wieder in der Leseansicht: das offene Dokument.
            panel.readingFront(true);
            seen[4] = panel.scope();
            // Die Leseansicht zeigt eine Quelle statt einer Seite: der Baum.
            panel.reading(null);
            seen[5] = panel.scope();
            panel.reading(Selection.ALL);
            seen[6] = panel.scope();
        });
        assertThat(seen).containsExactly(source, source, ticket, space, ticket, space, space);
        assertThat(graphUpdates[1]).isPositive();
        assertThat(graphUpdates[2]).isEqualTo(graphUpdates[1]);
        assertThat(quick[0]).as("Vorschläge folgen dem Bereich").contains(ChatPanel.SUMMARIZE_PAGE)
                .doesNotContain(ChatPanel.SUMMARIZE_AREA);
    }

    @Test
    void theInternalSearchSwitchTogglesAndIsOffAtFirst() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, "space-harvester-app:\n  data-directory: %s\n".formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        boolean[] on = new boolean[3];
        SwingUtilities.invokeAndWait(() -> {
            ChatPanel panel = new ChatPanel(context);
            on[0] = panel.appSearchOn();
            panel.appSearch(true);
            on[1] = panel.appSearchOn();
            panel.appSearch(false);
            on[2] = panel.appSearchOn();
        });
        assertThat(on).containsExactly(false, true, false);
        assertThat(ChatPanel.appSearchSources(context.settings(), null)).isEmpty();
    }

    @Test
    void aBrowserQuestionInTheTranscriptTakesOnlyTheFirstClickAndTheWebPopupHasAllRows() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, "space-harvester-app:\n  data-directory: %s\n".formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        java.util.List<com.aresstack.enterpriseai.astu.approval.ApprovalDecision.Outcome> answers =
                new java.util.ArrayList<>();
        java.util.List<String> labels = new java.util.ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            ChatPanel panel = new ChatPanel(context);
            panel.showApproval("Bestätigung nötig", "example.org möchte geöffnet werden", true, answers::add);
            java.util.List<javax.swing.JButton> buttons = new java.util.ArrayList<>();
            collect(panel, buttons);
            buttons.forEach(button -> labels.add(button.getText()));
            buttons.stream().filter(b -> ChatPanel.APPROVE_ALWAYS_TEXT.equals(b.getText())).findFirst()
                    .orElseThrow().doClick();
            buttons.stream().filter(b -> ChatPanel.REJECT_TEXT.equals(b.getText())).findFirst()
                    .orElseThrow().doClick();
        });
        assertThat(labels).contains(ChatPanel.APPROVE_ONCE_TEXT, ChatPanel.APPROVE_ALWAYS_TEXT,
                ChatPanel.REJECT_ALWAYS_TEXT, ChatPanel.REJECT_TEXT);
        assertThat(answers).containsExactly(
                com.aresstack.enterpriseai.astu.approval.ApprovalDecision.Outcome.APPROVED_ALWAYS);
    }

    private static void collect(java.awt.Container container, java.util.List<javax.swing.JButton> into) {
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof javax.swing.JButton button && button.getText() != null
                    && "transcript.approval".equals(nameOfApprovalRow(button))) {
                into.add(button);
            }
            if (child instanceof java.awt.Container inner) {
                collect(inner, into);
            }
        }
    }

    private static String nameOfApprovalRow(java.awt.Component component) {
        for (java.awt.Container parent = component.getParent(); parent != null; parent = parent.getParent()) {
            if ("transcript.approval".equals(parent.getName())) {
                return parent.getName();
            }
        }
        return null;
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
