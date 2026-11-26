package com.aresstack.spaceharvester.app.ui.deck;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.app.chat.DeckChatTools;
import com.aresstack.spaceharvester.app.chat.FilesChatTools;
import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.app.ui.content.ChatPanel;
import com.aresstack.spaceharvester.deck.editor.DocumentException;
import com.aresstack.spaceharvester.deck.editor.DocumentStore;
import com.aresstack.spaceharvester.deck.editor.EditorDocument;
import com.aresstack.spaceharvester.deck.editor.EditorSessions;
import com.aresstack.spaceharvester.deck.editor.SaveResult;
import com.aresstack.spaceharvester.deck.files.FileBrowser;
import com.aresstack.spaceharvester.deck.files.local.LocalDocumentStore;
import com.aresstack.spaceharvester.deck.files.local.LocalFileSource;
import java.awt.CardLayout;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Der Chat im Command Deck je Reiter (ADR 0033 „Was ich sehe, sieht der Bot“): jeder Bereich gibt seine Ansicht und
 * eigene Werkzeuge mit; der Bereich Dateien liest Ordner und Dateien, auch Dokumente, nur im offenen Ordner.
 */
class DeckChatToolsTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    private static final Executor NOW = Runnable::run;

    @TempDir
    Path root;

    @Test
    void viewShowsLabelsTablesAndTextsButNeverPasswordsOrHiddenCards() throws Exception {
        AtomicReference<JPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JPanel view = new JPanel(new java.awt.BorderLayout());
            view.add(new JLabel("Bibliothek PROD"), java.awt.BorderLayout.NORTH);
            JTable table = new JTable(new Object[][] {{"PGM01", "Programm"}, {"MAP01", "Map"}},
                    new Object[] {"Name", "Typ"});
            table.setRowSelectionInterval(1, 1);
            view.add(new JScrollPane(table), java.awt.BorderLayout.CENTER);
            JPanel cards = new JPanel(new CardLayout());
            JTextArea source = new JTextArea("DEFINE DATA LOCAL\nEND-DEFINE\n" + "WRITE 'X'\n".repeat(3000));
            source.getAccessibleContext().setAccessibleName("PGM01");
            cards.add(source, "editor");
            cards.add(new JLabel("Verdeckt"), "other");
            view.add(cards, java.awt.BorderLayout.SOUTH);
            JPasswordField secret = new JPasswordField("geheim");
            view.add(secret, java.awt.BorderLayout.EAST);
            panel.set(view);
        });
        AtomicBoolean shown = new AtomicBoolean(true);
        DeckChatTools tools = new DeckChatTools(panel::get, shown::get, null);

        // Wie viel mitgeht, folgt aus dem Platz in der Anfrage (ChatRoom); hier ein kleines Modell.
        String system = DeckChatTools.systemMessage("Command Deck › NDV", "NDV.", List.of(),
                com.aresstack.spaceharvester.app.chat.ChatRoom.within(8_000, tools::snapshot));
        String view = com.aresstack.spaceharvester.app.chat.ChatRoom.within(30_000,
                () -> tools.run(new AiChat.ToolCall("1", DeckChatTools.VIEW, "{}")));
        String text = com.aresstack.spaceharvester.app.chat.ChatRoom.within(12_000,
                () -> tools.run(new AiChat.ToolCall("2", DeckChatTools.TEXT, "{\"nr\":1,\"ab\":12000}")));

        assertThat(system).contains("Bibliothek PROD", "* MAP01 | Map", "  PGM01 | Programm", "DEFINE DATA LOCAL",
                "ganz mit text_lesen nr=1").doesNotContain("geheim", "Verdeckt");
        assertThat(view).contains("Tabelle", "Name | Typ", "[Text 1 – PGM01").doesNotContain("geheim");
        assertThat(text).startsWith("PGM01 (Zeichen 12000–24000 von").contains("weiter mit ab=24000");
        assertThat(tools.definitions()).extracting(AiChat.Tool::name)
                .containsExactly(DeckChatTools.VIEW, DeckChatTools.TEXT);

        shown.set(false);
        assertThat(tools.run(new AiChat.ToolCall("3", DeckChatTools.VIEW, "{}"))).contains("anderen Bereich");
    }

    @Test
    void filesListsAndReadsDocumentsOnlyInsideTheOpenFolder() throws Exception {
        Files.createDirectory(root.resolve("Unterordner"));
        Files.writeString(root.resolve("Unterordner/notiz.txt"), "Grüße an Bord\n");
        Files.write(root.resolve("bericht.pdf"), new byte[] {1, 2, 3});
        Path outside = Files.writeString(root.getParent().resolve("draussen-" + root.getFileName() + ".txt"), "nein");
        DocumentStore pdf = new DocumentStore() {
            @Override
            public String id() {
                return "vorschau";
            }

            @Override
            public boolean writable() {
                return false;
            }

            @Override
            public EditorDocument load(String location) {
                return new EditorDocument(location, "bericht.pdf", "Datenschutz bleibt Chefsache.", "UTF-8", "\n",
                        "h");
            }

            @Override
            public SaveResult save(EditorDocument loaded, String text, boolean overwrite) throws DocumentException {
                throw new DocumentException("nur lesen");
            }
        };
        FilesChatTools tools = new FilesChatTools(new LocalFileSource(FileSystems.getDefault(), root, List.of()),
                root.toString(), new LocalDocumentStore(), pdf,
                com.aresstack.spaceharvester.app.deck.DocumentPreviewStore::isDocument);

        String listed = tools.run(new AiChat.ToolCall("1", FilesChatTools.LIST, "{}"));
        String sub = tools.run(new AiChat.ToolCall("2", FilesChatTools.LIST, "{\"pfad\":\"Unterordner\"}"));
        String document = tools.run(new AiChat.ToolCall("3", FilesChatTools.READ, "{\"pfad\":\"bericht.pdf\"}"));
        String text = tools.run(new AiChat.ToolCall("4", FilesChatTools.READ,
                "{\"pfad\":\"Unterordner\\\\notiz.txt\"}"));
        String absolute = tools.run(new AiChat.ToolCall("5", FilesChatTools.READ,
                "{\"pfad\":\"" + root.resolve("bericht.pdf").toString().replace("\\", "\\\\") + "\"}"));
        String escaped = tools.run(new AiChat.ToolCall("6", FilesChatTools.READ,
                "{\"pfad\":\"../" + outside.getFileName() + "\"}"));
        String elsewhere = tools.run(new AiChat.ToolCall("7", FilesChatTools.LIST,
                "{\"pfad\":\"" + root.getParent().toString().replace("\\", "\\\\") + "\"}"));

        assertThat(listed).contains("[Ordner] Unterordner", "bericht.pdf · 3 B");
        assertThat(sub).contains("notiz.txt");
        assertThat(document).contains("Datenschutz bleibt Chefsache.");
        assertThat(text).contains("Grüße an Bord");
        assertThat(absolute).contains("Datenschutz bleibt Chefsache.");
        assertThat(escaped).isEqualTo(elsewhere).startsWith("Abgelehnt").doesNotContain("nein");
        Files.delete(outside);
    }

    @Test
    void readsWordDocumentsLikeThePreview() throws Exception {
        com.aresstack.spaceharvester.app.chat.TestDocx.write(root.resolve("plan.docx"), "Wartungsplan Kassiopeia",
                "Filter alle 90 Tage tauschen.");
        FilesChatTools tools = new FilesChatTools(new LocalFileSource(FileSystems.getDefault(), root, List.of()),
                root.toString(), new LocalDocumentStore(),
                new com.aresstack.spaceharvester.app.deck.DocumentPreviewStore(),
                com.aresstack.spaceharvester.app.deck.DocumentPreviewStore::isDocument);

        assertThat(tools.run(new AiChat.ToolCall("1", FilesChatTools.READ, "{\"pfad\":\"plan.docx\"}")))
                .contains("Wartungsplan Kassiopeia", "Filter alle 90 Tage tauschen.");
    }

    @Test
    void filesAreaGivesItsFolderAndToolsToTheChat() throws Exception {
        Files.writeString(root.resolve("notiz.txt"), "Grüße an Bord\n");
        AppState.shared().put(FilesWorkspace.LOCATION_KEY, root.toString());
        AtomicReference<CommandDeck> deck = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> deck.set(new CommandDeck()));
        List<String> changes = new java.util.concurrent.CopyOnWriteArrayList<>();
        deck.get().onAreaChanged(() -> changes.add(deck.get().area().name()));
        SwingUtilities.invokeAndWait(() -> {
            deck.get().show(DeckArea.FILES);
            deck.get().files().browser().open(root.toString());
        });
        long until = System.nanoTime() + 10_000_000_000L;
        while (deck.get().files().browser().loading() || deck.get().files().browser().location().isEmpty()) {
            if (System.nanoTime() > until) {
                break;
            }
            Thread.sleep(20);
        }
        SwingUtilities.invokeAndWait(() -> { });
        AtomicReference<ChatPanel.ToolScope> scope = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> scope.set(deck.get().chatScope()));

        assertThat(changes).containsExactly("FILES");
        assertThat(scope.get().area()).isEqualTo("Command Deck › Dateien (Ordner " + root + ")");
        assertThat(scope.get().system()).contains("notiz.txt", FilesChatTools.READ, "Inhalt des offenen Ordners");
        assertThat(scope.get().tools().definitions()).extracting(AiChat.Tool::name).containsExactly(
                DeckChatTools.VIEW, DeckChatTools.TEXT, FilesChatTools.LIST, FilesChatTools.READ);
        assertThat(scope.get().tools().run(new AiChat.ToolCall("1", FilesChatTools.READ,
                "{\"pfad\":\"notiz.txt\"}"))).contains("Grüße an Bord");
        for (DeckArea area : DeckArea.values()) {
            ChatPanel.ToolHints hints = CommandDeck.chatHints(area);
            assertThat(hints.hint()).as(area.name()).isNotBlank();
            assertThat(hints.actions()).as(area.name()).isNotEmpty();
        }
        assertThat(CommandDeck.chatHints(DeckArea.FILES).actions()).extracting(ChatPanel.QuickAction::label)
                .contains("Ordner zusammenfassen");
        deck.get().shutdown();
    }
}
