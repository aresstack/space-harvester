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
import java.awt.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
            panel[0].selection(new Selection.Node("handbuch", space, null, "Handbuch"));
            assertThat(panel[0].chat().id()).isEqualTo(earlier.id());
            assertThat(panel[0].chatName()).isEqualTo("Frühere Frage");
            assertThat(panel[0].bubbles()).isEqualTo(5);
            // Preserve the user speech bubble and put the assistant actions below the Markdown body.
            java.util.List<com.aresstack.spaceharvester.app.ui.markdown.AssistantMarkdownMessage> answers =
                    new java.util.ArrayList<>();
            collect(panel[0], com.aresstack.spaceharvester.app.ui.markdown.AssistantMarkdownMessage.class, answers);
            assertThat(answers).isNotEmpty().allSatisfy(answer -> assertThat(answer.isOpaque()).isFalse());
            java.util.List<com.aresstack.enterpriseai.ui.comic.bubble.SpeechBubblePanel> questions =
                    new java.util.ArrayList<>();
            collect(panel[0], com.aresstack.enterpriseai.ui.comic.bubble.SpeechBubblePanel.class, questions);
            assertThat(questions).isNotEmpty();
            // Preserve timestamps and copy the original message text or Markdown.
            for (long at : new long[] {1_000, 1_500, 1_600, 1_700, 1_800}) {
                assertThat(labelsWithTooltip(panel[0],
                        com.aresstack.enterpriseai.ui.comic.bubble.BubbleTimestamps.tooltip(at))).isNotZero();
            }
            java.util.List<com.aresstack.spaceharvester.app.ui.controls.CopyButton> copies =
                    new java.util.ArrayList<>();
            collect(panel[0], com.aresstack.spaceharvester.app.ui.controls.CopyButton.class, copies);
            assertThat(copies).hasSize(5);
            java.util.List<String> copied = new java.util.ArrayList<>();
            panel[0].setClipboard(copied::add);
            copies.get(0).doClick();
            copies.get(1).doClick();
            assertThat(copied).containsExactly("Frühere Frage", "Frühere Antwort");
            assertThat(copies.get(1).copiedShown()).isTrue();
            // Angelo, 26.11.2026: Uhrzeit vor dem QR-Knopf; Kopieren liegt nie neben dem QR-Knopf. Bei der Frage
            // steht es direkt hinter dem Namen, bei der Antwort abgesetzt ganz links, der Export hinter dem QR-Knopf.
            java.util.List<com.aresstack.enterpriseai.ui.comic.bubble.MessageQrButton> qrs = new java.util.ArrayList<>();
            collect(panel[0], com.aresstack.enterpriseai.ui.comic.bubble.MessageQrButton.class, qrs);
            assertThat(qrs).hasSize(5);
            for (int i = 0; i < qrs.size(); i++) {
                java.awt.Container controls = qrs.get(i).getParent();
                int index = controls.getComponentZOrder(qrs.get(i));
                assertThat(index).isPositive();
                assertThat(controls.getComponent(index - 1))
                        .isInstanceOf(com.aresstack.spaceharvester.app.ui.markdown.MessageTimestamp.class);
                boolean question = javax.swing.SwingUtilities.getAncestorOfClass(
                        com.aresstack.enterpriseai.ui.comic.bubble.SpeechBubblePanel.class, controls) != null;
                if (question) {
                    assertThat(index).isEqualTo(controls.getComponentCount() - 1);
                    java.awt.Container header = copies.get(i).getParent();
                    assertThat(header).isNotSameAs(controls);
                    int copyIndex = header.getComponentZOrder(copies.get(i));
                    // Name, Lücke, Kopieren.
                    assertThat(header.getComponent(copyIndex - 2)).isInstanceOf(javax.swing.JLabel.class);
                    assertThat(((javax.swing.JLabel) header.getComponent(copyIndex - 2)).getText()).isEqualTo("Du");
                } else {
                    assertThat(copies.get(i).getParent()).isSameAs(controls);
                    assertThat(controls.getComponentZOrder(copies.get(i))).isZero();
                    assertThat(controls.getComponent(1)).isInstanceOf(javax.swing.Box.Filler.class);
                    // Hinter dem QR-Knopf nur noch der Export.
                    assertThat(index).isEqualTo(controls.getComponentCount() - 2);
                }
            }
            // Ein Export-Menü; die Quellen allein sind sein letzter Eintrag, kein eigenes Menü daneben.
            java.util.List<javax.swing.JMenuItem> menus = panel[0].exportMenus();
            assertThat(menus).hasSize(2);
            javax.swing.JMenu export = (javax.swing.JMenu) menus.get(0);
            assertThat(export.getItem(export.getItemCount() - 1).getText()).isEqualTo(ChatPanel.SOURCES_ONLY);
            java.util.List<String> shown = new java.util.ArrayList<>();
            panel[0].setQrCodes((name, text) -> shown.add(text));
            qrs.get(0).doClick();
            qrs.get(1).doClick();
            assertThat(shown).containsExactly("Frühere Frage", "Frühere Antwort");
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
            panel[0].selection(new Selection.Node("handbuch", null, null, "Handbuch"));
            assertThat(panel[0].chat().id()).isEqualTo(earlier.id());
            assertThat(panel[0].bubbles()).isEqualTo(5);
            assertThat(panel[0].knowledge()).startsWith("Wissen: Handbuch · alle Spaces");
            // Unter ▾ zuerst die Chats des gewählten Bereichs, darunter abgetrennt alle übrigen; der offene mit Haken.
            assertThat(menuTexts(panel[0])).containsExactly(ChatPanel.CHATS_HERE, "    Ganz andere Frage", "—",
                    ChatPanel.CHATS_ELSEWHERE, "✓ Zugriffsrechte");
            // Ein Chat aus der Liste öffnet sich aus seinem eigenen Bereich und bleibt dort gespeichert.
            ((javax.swing.JMenuItem) panel[0].chatMenuItems().get(1)).doClick();
            assertThat(panel[0].chat().id()).isEqualTo(other.id());
            assertThat(panel[0].areaMarks()).isEmpty();
            panel[0].selection(new Selection.Node("handbuch", space, null, "Handbuch"));
            assertThat(panel[0].chat().id()).isEqualTo(other.id());
            assertThat(menuTexts(panel[0])).containsExactly(ChatPanel.CHATS_HERE, "    Zugriffsrechte", "—",
                    ChatPanel.CHATS_ELSEWHERE, "✓ Ganz andere Frage");
            // Ein neuer, leerer Chat gehört noch keinem Bereich: er zieht mit der Auswahl mit.
            javax.swing.AbstractButton fresh = (javax.swing.AbstractButton)
                    ((java.awt.Container) panel[0].headerActions()).getComponent(0);
            fresh.doClick();
            assertThat(panel[0].chat().isEmpty()).isTrue();
            panel[0].selection(new Selection.Node("handbuch", null, null, "Handbuch"));
            assertThat(panel[0].chat().isEmpty()).as("kein gespeicherter Chat springt mehr herein").isTrue();
            assertThat(panel[0].bubbles()).isZero();
            assertThat(panel[0].chatName()).isEqualTo("Neuer Chat");
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
        Selection source = new Selection.Node("tickets", null, null, "Tickets");
        Selection ticket = new Selection.Node("tickets", "PRJ", "PRJ-7", "PRJ-7 Nachtlauf bricht ab");
        Selection space = new Selection.Node("tickets", "PRJ", null, "PRJ");
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
    void withoutSelectionTheChatStillSendsAndOffersANewChat() throws Exception {
        // Angelo 24.11.2026: Die Lupe hebt die Auswahl auf, und der Chat arbeitet trotzdem weiter.
        Path config = home.resolve("app.yml");
        Files.writeString(config, "space-harvester-app:\n  data-directory: %s\n".formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        boolean[] state = new boolean[4];
        SwingUtilities.invokeAndWait(() -> {
            ChatPanel panel = new ChatPanel(context);
            panel.selection(new Selection.Node("handbuch", "HB", null, "Handbuch"));
            panel.selection(null);
            state[0] = panel.sendButton().isEnabled();
            state[1] = panel.newChatEnabled();
            state[2] = ChatPanel.NO_SELECTION.equals(panel.knowledge());
            state[3] = panel.quickActionLabels().isEmpty();
        });
        assertThat(state).containsExactly(true, true, true, true);
    }

    @Test
    void theMenuAtTheTriangleOffersThreeSeparateParallelLimitsWithTheChoicesOfTheJobs() throws Exception {
        // Angelo 24.11.2026: nicht in den Einstellungen, sondern im Dreieck-Menü, mit dem Code der Aufträge.
        Path config = home.resolve("app.yml");
        Files.writeString(config, "space-harvester-app:\n  data-directory: %s\n".formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        List<List<String>> menus = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            for (javax.swing.JMenu menu : new ChatPanel(context).parallelMenus()) {
                List<String> labels = new ArrayList<>(List.of(menu.getText()));
                for (int i = 0; i < menu.getItemCount(); i++) {
                    if (menu.getItem(i) != null) {
                        labels.add(menu.getItem(i).getText());
                    }
                }
                menus.add(labels);
            }
        });
        assertThat(menus).extracting(labels -> labels.get(0).substring(0, labels.get(0).indexOf(':')))
                .containsExactly(ChatPanel.PARALLEL_TABS, ChatPanel.PARALLEL_AGENTS, ChatPanel.PARALLEL_FILES);
        assertThat(menus).allSatisfy(labels -> assertThat(labels.subList(1, 8))
                .containsExactly("1", "2", "4", "8", "16", "32", "64"))
                .allSatisfy(labels -> assertThat(labels.get(8))
                        .startsWith(com.aresstack.spaceharvester.app.ui.ParallelChoice.CUSTOM));
    }

    @Test
    void theButtonsOfTheHeadSitVerticallyCenteredInFullSize() throws Exception {
        // Angelo 24.11.2026: Neuer Chat, Löschen und Groß anzeigen klebten oben, darunter war Luft.
        Path config = home.resolve("app.yml");
        Files.writeString(config, "space-harvester-app:\n  data-directory: %s\n".formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        List<java.awt.Rectangle> buttons = new ArrayList<>();
        int[] head = new int[1];
        SwingUtilities.invokeAndWait(() -> {
            ChatSidebar sidebar = new ChatSidebar(new ChatPanel(context));
            sidebar.setSize(ChatSidebar.DEFAULT_WIDTH, 400);
            layoutAll(sidebar);
            java.awt.Component top = ((java.awt.BorderLayout) sidebar.getLayout())
                    .getLayoutComponent(java.awt.BorderLayout.NORTH);
            head[0] = top.getHeight() - 1;
            List<java.awt.Component> parts = new ArrayList<>(List.of(
                    sidebar.chat().headerActions().getComponents()));
            parts.add(sidebar.enlargeButton());
            for (java.awt.Component part : parts) {
                buttons.add(SwingUtilities.convertRectangle(part.getParent(), part.getBounds(), top));
            }
        });
        assertThat(buttons).hasSize(3).allSatisfy(bounds -> {
            assertThat(bounds.height).isEqualTo(com.aresstack.spaceharvester.app.ui.RoundHover.SIZE);
            assertThat(Math.abs(bounds.y + bounds.height / 2 - head[0] / 2)).isLessThanOrEqualTo(1);
        });
    }

    private static void layoutAll(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof java.awt.Container inner) {
                layoutAll(inner);
            }
        }
    }

    @Test
    void withWebSearchAndInternalSearchBothOnTheMessageAboveWarnsAndGoesAwayWhenOneIsOff() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, "space-harvester-app:\n  data-directory: %s\n".formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        // Ein Firefox, den nichts startet: Die Weltkugel braucht nur seinen Pfad.
        Path firefox = Files.writeString(home.resolve("firefox"), "");
        String before = System.getProperty("enterpriseai.firefox");
        System.setProperty("enterpriseai.firefox", firefox.toString());
        try {
            String[] texts = new String[3];
            boolean[] visible = new boolean[3];
            SwingUtilities.invokeAndWait(() -> {
                ChatPanel panel = new ChatPanel(context);
                panel.webSearch(true);
                visible[0] = panel.feedback().isVisible();
                panel.appSearch(true);
                visible[1] = panel.feedback().isVisible();
                texts[1] = panel.feedback().text();
                panel.webSearch(false);
                visible[2] = panel.feedback().isVisible();
                // Ersetzt eine andere Meldung die Warnung, bleibt sie beim Ausschalten stehen.
                panel.webSearch(true);
                panel.feedback().warning("Download ließ sich nicht starten", null);
                panel.appSearch(false);
                texts[2] = panel.feedback().isVisible() ? panel.feedback().text() : "";
            });
            assertThat(visible).containsExactly(false, true, false);
            assertThat(texts[1]).contains(ChatPanel.BOTH_ON_WARNING);
            assertThat(texts[2]).contains("Download ließ sich nicht starten");
        } finally {
            if (before == null) {
                System.clearProperty("enterpriseai.firefox");
            } else {
                System.setProperty("enterpriseai.firefox", before);
            }
        }
    }

    @Test
    void theOpenChatIsDeletedWithoutQuestionStaysStruckThroughUntilRestartAndOpeningRestoresIt() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, "space-harvester-app:\n  data-directory: %s\n".formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        ChatStore store = new ChatStore(() -> home.resolve("data").resolve(ChatStore.FOLDER));
        ChatNode spaceNode = new ChatNode("handbuch", "HB", null);
        ChatNode sourceNode = new ChatNode("handbuch", null, null);
        ChatRecord first = ChatRecord.fresh(1_000).plus(ChatRecord.Message.user("Erste Frage", 1_000), 1_000);
        ChatRecord second = ChatRecord.fresh(2_000).plus(ChatRecord.Message.user("Zweite Frage", 2_000), 2_000);
        ChatRecord third = ChatRecord.fresh(3_000).plus(ChatRecord.Message.user("Dritte Frage", 3_000), 3_000);
        store.save(spaceNode, first);
        store.save(spaceNode, second);
        store.save(sourceNode, third);
        java.util.List<String> asked = new java.util.ArrayList<>();
        boolean[] enabled = new boolean[2];
        String[] deletedId = new String[1];
        java.util.List<String> struck = new java.util.ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            ChatPanel panel = new ChatPanel(context);
            panel.confirm = (title, question) -> {
                asked.add(question);
                return true;
            };
            // Der Chat aus dem Space ist offen, gewählt ist die ganze Quelle: Er steht unter „Übrige Chats“ und bleibt
            // trotzdem löschbar.
            panel.selection(new Selection.Node("handbuch", null, null, "Handbuch"));
            for (Component item : panel.chatMenuItems()) {
                if (item instanceof javax.swing.JMenuItem menuItem && menuItem.getText().endsWith("Zweite Frage")) {
                    menuItem.doClick();
                }
            }
            deletedId[0] = panel.chat().id();
            assertThat(deletedId[0]).isEqualTo(second.id());
            javax.swing.AbstractButton trash = (javax.swing.AbstractButton)
                    ((java.awt.Container) panel.headerActions()).getComponent(1);
            enabled[0] = trash.isEnabled();
            trash.doClick();
            enabled[1] = trash.isEnabled();
            assertThat(panel.chat().id()).isNotEqualTo(deletedId[0]);
            // Bis zum Neustart steht er durchgestrichen unter ▾; ein Klick holt ihn zurück.
            for (Component item : panel.chatMenuItems()) {
                if (item instanceof javax.swing.JMenuItem menuItem && menuItem.getText().startsWith("<html><s>")) {
                    struck.add(menuItem.getText());
                    menuItem.doClick();
                }
            }
            assertThat(panel.chat().id()).isEqualTo(deletedId[0]);
            // Alle Chats löschen fragt „wirklich“ und löscht alles endgültig.
            for (Component item : new ChatSidebar(panel).moreMenu().getComponents()) {
                if (item instanceof javax.swing.JMenuItem menuItem
                        && ChatSidebar.DELETE_ALL.equals(menuItem.getText())) {
                    menuItem.doClick();
                }
            }
            assertThat(panel.chat().isEmpty()).isTrue();
        });
        assertThat(enabled).containsExactly(true, true);
        assertThat(struck).hasSize(1);
        assertThat(asked).containsExactly(ChatPanel.DELETE_ALL_QUESTION);
        assertThat(store.all()).isEmpty();
        assertThat(store.deleted()).isEmpty();
    }

    @Test
    void aChatDeletedInAnEarlierRunIsGoneForGoodAfterTheRestart() throws Exception {
        Path chats = home.resolve("chats");
        ChatNode node = new ChatNode("handbuch", "HB", null);
        ChatRecord record = ChatRecord.fresh(1_000).plus(ChatRecord.Message.user("Frage", 1_000), 1_000);
        // Ein früherer Programmlauf hat den Chat vorläufig gelöscht (Datei mit Endung .deleted).
        ChatStore before = new ChatStore(() -> chats);
        before.save(node, record);
        Path folder = chats.resolve(before.folder(node));
        Files.move(folder.resolve(record.id() + ".json"), folder.resolve(record.id() + ".json.deleted"));
        // Dieser Programmlauf sieht den Ordner zum ersten Mal: Was dort gelöscht liegt, geht endgültig.
        assertThat(new ChatStore(() -> chats).deleted()).isEmpty();
        assertThat(folder.resolve(record.id() + ".json.deleted")).doesNotExist();
        // Was in diesem Lauf gelöscht wird, bleibt bis zum nächsten Start wiederherstellbar.
        ChatStore store = new ChatStore(() -> chats);
        store.save(node, record);
        store.trash(store.folder(node), record.id());
        assertThat(store.list(node)).isEmpty();
        assertThat(store.deleted()).extracting(entry -> entry.record().id()).containsExactly(record.id());
        assertThat(store.restore(store.folder(node), record.id())).isNotNull();
        assertThat(store.list(node)).extracting(ChatRecord::id).containsExactly(record.id());
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
    void aFolderGivesTheChatItsSubfoldersOnlyWithSubpagesAndOnlyFavoritesOfTheResolvedCollection() {
        com.aresstack.spaceharvester.app.ui.Favorites favorites =
                new com.aresstack.spaceharvester.app.ui.Favorites(() -> home.resolve("favorites.json"));
        String top = favorites.createFolder("Betrieb", null);
        String sub = favorites.createFolder("Nachtlauf", top);
        String deeper = favorites.createFolder("Spool", sub);
        String other = favorites.createFolder("Anderes", null);
        var seite = new com.aresstack.spaceharvester.app.ui.Favorites.Favorite("wiki", "DEV", "42", "Seite",
                java.util.List.of(), java.util.List.of());
        var space = com.aresstack.spaceharvester.app.ui.Favorites.Favorite.space("wiki", "OPS", "Betrieb", "Wiki");
        var spaeter = new com.aresstack.spaceharvester.app.ui.Favorites.Favorite("wiki", "DEV", "77", "Später",
                java.util.List.of(), java.util.List.of());
        favorites.toggle(seite, top);
        favorites.toggle(space, sub);
        var roots = favorites.favoritesIn(top, true).stream()
                .map(com.aresstack.spaceharvester.app.ui.Favorites.Favorite::root).toList();
        // Nach dem Senden dazugekommen: steht nicht in der aufgelösten Sammlung, also nicht im Bereich.
        favorites.toggle(spaeter, top);

        var with = ChatPanel.collectionNodes(favorites, new Selection.Collection(top, "Betrieb", roots, true));
        var without = ChatPanel.collectionNodes(favorites, new Selection.Collection(top, "Betrieb", roots, false));

        assertThat(with.content(null).folders()).extracting(
                com.aresstack.spaceharvester.app.chat.CollectionNodes.Folder::id).containsExactly(sub);
        assertThat(with.content(null).roots()).containsExactly(seite.root());
        assertThat(with.content(sub).roots()).containsExactly(space.root());
        assertThat(with.content(deeper)).as("auch tiefer").isNotNull();
        assertThat(with.content(other)).as("ein Ordner außerhalb").isNull();
        assertThat(without.content(null).folders()).isEmpty();
        assertThat(without.content(sub)).as("ohne Unterordner kein Zugriff").isNull();
        assertThat(ChatPanel.scopeKey(ChatNode.collection(top), false))
                .isNotEqualTo(ChatPanel.scopeKey(ChatNode.collection(other), false))
                .isNotEqualTo(ChatPanel.scopeKey(ChatNode.ALL, false));
    }

    @Test
    void aFolderIsTheAreaAndAChangedFolderCountsFromTheNextQuestion() throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        Files.writeString(files.resolve("datenhaus.md"), "# Datenhaus\n\nText.\n");
        Files.writeString(files.resolve("nachtlauf.md"), "# Nachtlauf\n\nText.\n");
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
        var workspace = context.runtime().workspace();
        String space = workspace.spaces("handbuch").get(0).key();
        workspace.startSync("handbuch", space,
                com.aresstack.spaceharvester.application.sync.SyncMode.FULL);
        var whole = com.aresstack.spaceharvester.application.holdings.Selection.of(
                com.aresstack.spaceharvester.application.holdings.Selection.Root.space("handbuch", space));
        java.util.List<String> pageIds = new java.util.ArrayList<>();
        for (int i = 0; i < 300 && pageIds.size() < 2; i++) {
            pageIds.clear();
            workspace.resolveSelection(whole).pages().asMap().getOrDefault("handbuch", java.util.Set.of())
                    .forEach(pageIds::add);
            Thread.sleep(100);
        }
        assertThat(pageIds).hasSize(2);
        java.util.Collections.sort(pageIds);
        var first = com.aresstack.spaceharvester.application.holdings.Selection.Root.subtree("handbuch", space,
                pageIds.get(0));
        var second = com.aresstack.spaceharvester.application.holdings.Selection.Root.subtree("handbuch", space,
                pageIds.get(1));
        var gone = com.aresstack.spaceharvester.application.holdings.Selection.Root.subtree("handbuch", space,
                "gibt-es-nicht.md");
        ChatPanel[] panel = new ChatPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new ChatPanel(context);
            panel[0].selection(new Selection.Collection("f-1", "Betrieb", java.util.List.of(first, second, gone),
                    true));
        });
        awaitResolved(panel[0], 2);
        String[] line = new String[1];
        SwingUtilities.invokeAndWait(() -> line[0] = panel[0].knowledge());
        assertThat(line[0]).startsWith("Wissen: Ordner Betrieb samt Unterordnern · 2 Seiten")
                .contains("1 Favorit nicht verfügbar");

        // Ein Favorit wird entfernt: Die neue Sammlung ist eine andere Auswahl, die nächste Frage kennt die Seite nicht.
        SwingUtilities.invokeAndWait(() -> panel[0].selection(new Selection.Collection("f-1", "Betrieb",
                java.util.List.of(first), true)));
        awaitResolved(panel[0], 1);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(panel[0].resolved().contains("handbuch", pageIds.get(1))).isFalse();
            assertThat(panel[0].resolved().contains("handbuch", pageIds.get(0))).isTrue();
        });
    }

    /** Wartet, bis der Chat die Auswahl mit {@code pages} Seiten aufgelöst hat. */
    private static void awaitResolved(ChatPanel panel, int pages) throws Exception {
        int[] seen = {-1};
        for (int i = 0; i < 300 && seen[0] != pages; i++) {
            SwingUtilities.invokeAndWait(() -> seen[0] = panel.resolved() == null ? -1
                    : panel.resolved().counts().pages());
            Thread.sleep(50);
        }
        assertThat(seen[0]).isEqualTo(pages);
    }

    @Test
    void theKnowledgeLineNamesTheSelectionItsPagesAndWhatIsMissing() {
        assertThat(ChatPanel.knowledgeText(null, null, true, true, 0)).isEqualTo(ChatPanel.NO_SELECTION);
        Selection page = new Selection.Node("wiki", "DEV", "42", "Handbuch");
        assertThat(ChatPanel.knowledgeText(page, new HoldingCounts(120, 120, 0, 0), true, true, 0))
                .isEqualTo("Wissen: Handbuch samt Unterseiten · 120 Seiten");
        assertThat(ChatPanel.knowledgeText(page, new HoldingCounts(120, 80, 0, 0), true, true, 0))
                .isEqualTo("Wissen: Handbuch samt Unterseiten · 120 Seiten · 40 davon nicht geladen");
        assertThat(ChatPanel.knowledgeText(page, new HoldingCounts(1, 1, 0, 0), true, true, 0))
                .isEqualTo("Wissen: Handbuch samt Unterseiten · 1 Seite");
        Selection space = new Selection.Node("wiki", "DEV", null, "Entwicklung");
        assertThat(ChatPanel.knowledgeText(space, HoldingCounts.ZERO, true, true, 0))
                .isEqualTo("Wissen: Space Entwicklung · " + ChatPanel.NOT_LOADED);
        assertThat(ChatPanel.knowledgeText(page, new HoldingCounts(3, 3, 0, 0), false, true, 0))
                .isEqualTo("Wissen: Handbuch samt Unterseiten · 3 Seiten · " + ExportPanel.INCOMPLETE);
        assertThat(ChatPanel.knowledgeText(space, null, true, true, 0))
                .isEqualTo("Wissen: Space Entwicklung · " + ChatPanel.NOT_LOADED);
        Selection source = new Selection.Node("wiki", null, null, "Beta");
        // Der Chat ist die Ausnahme der KI-Freigabe (Angelo, 13.11.2026): genannt, nicht gesperrt.
        assertThat(ChatPanel.knowledgeText(source, new HoldingCounts(2010, 176, 8, 0), true, false, 0))
                .isEqualTo("Wissen: Beta · alle Spaces · 2010 Seiten · 1826 davon nicht geladen · "
                        + ChatPanel.NOT_ALLOWED);
        // Die globale Auswahl (Überschrift „Wissensquellen“, Angelo 16.11.2026).
        assertThat(Selection.ALL.global()).isTrue();
        assertThat(ChatPanel.knowledgeText(Selection.ALL, new HoldingCounts(10, 10, 0, 0), true, true, 0))
                .isEqualTo("Wissen: alle Wissensquellen · 10 Seiten");
        // Ein Favoriten-Ordner (ADR 0034 §5): Ordnername und Seitenzahl, veraltete Favoriten nur gezählt.
        Selection folder = new Selection.Collection("f-1", "Betrieb", java.util.List.of(
                com.aresstack.spaceharvester.application.holdings.Selection.Root.subtree("wiki", "DEV", "42")), true);
        assertThat(ChatPanel.knowledgeText(folder, new HoldingCounts(4, 4, 0, 0), true, true, 2))
                .isEqualTo("Wissen: Ordner Betrieb samt Unterordnern · 4 Seiten · 2 Favoriten nicht verfügbar");
        assertThat(ChatPanel.knowledgeText(new Selection.Collection("f-1", "Betrieb", java.util.List.of(), false),
                HoldingCounts.ZERO, true, true, 1))
                .isEqualTo("Wissen: Ordner Betrieb · " + ChatPanel.NOT_LOADED + " · 1 Favorit nicht verfügbar");
        assertThat(ChatPanel.allowed(null, null, folder)).as("Quelle des Favoriten nicht freigegeben").isFalse();
    }

    private static <T> void collect(java.awt.Container container, Class<T> type, java.util.List<T> found) {
        for (java.awt.Component child : container.getComponents()) {
            if (type.isInstance(child)) {
                found.add(type.cast(child));
            }
            if (child instanceof java.awt.Container nested) {
                collect(nested, type, found);
            }
        }
    }

    private static long labelsWithTooltip(java.awt.Container container, String tooltip) {
        java.util.List<javax.swing.JLabel> labels = new java.util.ArrayList<>();
        collect(container, javax.swing.JLabel.class, labels);
        return labels.stream().filter(label -> tooltip.equals(label.getToolTipText())).count();
    }
}
