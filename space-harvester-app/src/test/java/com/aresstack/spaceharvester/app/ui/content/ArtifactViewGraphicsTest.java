package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.chat.ChatNode;
import com.aresstack.spaceharvester.app.chat.ChatRecord;
import com.aresstack.spaceharvester.app.chat.ChatStore;
import com.aresstack.spaceharvester.app.chat.artifact.ChatArtifact;
import com.aresstack.spaceharvester.app.chat.artifact.ChatArtifactStore;
import com.aresstack.spaceharvester.app.chat.artifact.GraphicReferences;
import com.aresstack.spaceharvester.app.chat.artifact.SvgElements;
import com.aresstack.spaceharvester.app.ui.controls.TextContextMenu;
import com.aresstack.spaceharvester.app.ui.markdown.MermaidDiagramPanel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Rechtsklick auf eine Grafik im Artefakt (ADR 0045 §6): Grafik oder Element an den Chat; erfundene Grafiken. */
class ArtifactViewGraphicsTest {

    private static final String BEET = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"80\" height=\"40\">"
            + "<g><rect width=\"40\" height=\"20\" fill=\"#8c8\"/><text x=\"2\" y=\"9\">Bohnen</text></g>"
            + "<circle cx=\"60\" cy=\"20\" r=\"8\"/></svg>";

    @TempDir
    Path home;

    private ChatArtifactStore store;
    private Path folder;
    private String chatId;
    private final List<String> quotes = new ArrayList<>();
    private final TextContextMenu.QuoteTarget target = new TextContextMenu.QuoteTarget() {
        @Override
        public boolean accepts(Component field) {
            return true;
        }

        @Override
        public void quote(String text, String reference) {
            quotes.add(reference + " | " + text);
        }
    };

    @BeforeEach
    void setUp() {
        ChatStore chats = new ChatStore(() -> home.resolve("chats"));
        ChatNode node = new ChatNode("wiki", null, null);
        ChatRecord chat = ChatRecord.fresh(1).plus(ChatRecord.Message.user("Mal", 1), 1);
        chats.save(node, chat);
        store = new ChatArtifactStore(chats);
        folder = chats.folder(node);
        chatId = chat.id();
        TextContextMenu.setQuoteTarget(target);
    }

    @AfterEach
    void tearDown() {
        // Der Chat der App ist statisch angemeldet: andere Tests erwarten keinen.
        TextContextMenu.setQuoteTarget(null);
    }

    @Test
    void aGraphicOrOneOfItsElementsGoesToTheChatWithItsReference() throws Exception {
        ChatArtifact graphic = store.createGraphic(folder, chatId, "Beet", BEET, ChatArtifact.Origin.AI);
        store.createDocument(folder, chatId, "Fibel", "## Plan\n\n" + GraphicReferences.reference(graphic),
                ChatArtifact.Origin.AI);
        SwingUtilities.invokeAndWait(() -> {
            ArtifactView view = new ArtifactView(store, folder, chatId, "G1");
            JPopupMenu menu = view.diagramMenu(panelIn(view));
            ((JMenuItem) menu.getComponent(0)).doClick();
            JMenu elements = (JMenu) menu.getComponent(1);
            assertThat(elements.getText()).isEqualTo(ArtifactView.ELEMENT_QUOTE);
            JMenu group = (JMenu) elements.getMenuComponent(0);
            assertThat(group.getText()).startsWith("e1 Gruppe");
            ((JMenuItem) group.getMenuComponent(0)).doClick();
            ((JMenuItem) group.getMenuComponent(3)).doClick();
            ((JMenuItem) elements.getMenuComponent(1)).doClick();

            ArtifactView document = new ArtifactView(store, folder, chatId, "D1");
            JPopupMenu inDocument = document.diagramMenu(panelIn(document.block("a1")));
            assertThat(inDocument).as("eingebundene Grafik im Abschnitt wird erkannt").isNotNull();
            ((JMenuItem) inDocument.getComponent(0)).doClick();
        });
        assertThat(quotes).containsExactly(
                "Grafik G1 „Beet“ | Grafik „Beet“",
                "Grafik G1 „Beet“, Element e1 | e1 Gruppe",
                "Grafik G1 „Beet“, Element e3 | e3 Text · „Bohnen“ x=2 y=9",
                "Grafik G1 „Beet“, Element e4 | e4 Kreis · cx=60 cy=20 r=8",
                "Grafik G1 „Beet“ | Grafik „Beet“");
    }

    @Test
    void aMermaidDiagramBecomesAnEditableGraphic() throws Exception {
        String code = "flowchart LR\n  A --> B";
        store.createDocument(folder, chatId, "Fibel", "## Ablauf\n\nVorher.\n\n```mermaid\n" + code
                + "\n```\n\nNachher.", ChatArtifact.Origin.AI);
        String[] message = new String[1];
        SwingUtilities.invokeAndWait(() -> {
            ArtifactView view = new ArtifactView(store, folder, chatId, "D1");
            MermaidDiagramPanel mermaid = panelIn(view.block("a1"));
            assertThat(mermaid.kind()).isEqualTo(MermaidDiagramPanel.Kind.MERMAID);
            JPopupMenu menu = view.diagramMenu(mermaid);
            assertThat(((JMenuItem) menu.getComponent(0)).getText()).isEqualTo(ArtifactView.ADOPT);
            message[0] = view.adoptSvg(view.artifact(), "a1", "flowchart LR\n    A --> B\n", BEET);
        });
        assertThat(message[0]).contains("G1");
        String section = store.read(folder, chatId, "D1").document().section("a1").markdown();
        assertThat(section).contains("Vorher.").contains("![Ablauf](artefakt:G1)").contains("Nachher.")
                .doesNotContain("mermaid");
        assertThat(SvgElements.list(store.read(folder, chatId, "G1").current().svg())).hasSize(4);
        assertThat(GraphicReferences.replaceBlock(section, code, "x")).as("schon übernommen").isNull();
    }

    @Test
    void twoEqualLookingGraphicsAreTellApartAndOldGraphicsGetElementIds() throws Exception {
        // Codex-Befunde zu #1650: Kennung statt Inhalt; eine Grafik ohne Kennungen bekommt sie beim Lesen.
        String old = "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"4\" height=\"4\"/></svg>";
        ChatArtifact first = store.createGraphic(folder, chatId, "Erste", old, ChatArtifact.Origin.AI);
        ChatArtifact second = store.createGraphic(folder, chatId, "Zweite", old, ChatArtifact.Origin.AI);
        store.createDocument(folder, chatId, "Fibel", "## Plan\n\n" + GraphicReferences.reference(first) + "\n\n"
                + GraphicReferences.reference(second), ChatArtifact.Origin.AI);
        assertThat(SvgElements.list(old)).extracting(SvgElements.Item::id).containsExactly("e1");
        SwingUtilities.invokeAndWait(() -> {
            ArtifactView document = new ArtifactView(store, folder, chatId, "D1");
            List<MermaidDiagramPanel> panels = new ArrayList<>();
            panelsIn(document.block("a1"), panels);
            assertThat(panels).hasSize(2);
            ((JMenuItem) document.diagramMenu(panels.get(1)).getComponent(0)).doClick();
        });
        assertThat(quotes).containsExactly("Grafik G2 \u201EZweite\u201C | Grafik \u201EZweite\u201C");
        assertThat(GraphicReferences.replaceBlock("```mermaid\nA[\"A B\"]\n```\n\n```mermaid\nA[\"AB\"]\n```",
                "A[\"AB\"]", "X")).as("Leerraum in Beschriftungen zählt").isEqualTo("```mermaid\nA[\"A B\"]\n```\n\nX");
    }

    private static void panelsIn(Component component, List<MermaidDiagramPanel> out) {
        if (component instanceof MermaidDiagramPanel panel) {
            out.add(panel);
        } else if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                panelsIn(child, out);
            }
        }
    }

    private static MermaidDiagramPanel panelIn(Component component) {
        if (component instanceof MermaidDiagramPanel panel) {
            return panel;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                MermaidDiagramPanel found = panelIn(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
