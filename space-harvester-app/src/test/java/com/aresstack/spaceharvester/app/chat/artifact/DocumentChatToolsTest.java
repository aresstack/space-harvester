package com.aresstack.spaceharvester.app.chat.artifact;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.app.chat.ChatNode;
import com.aresstack.spaceharvester.app.chat.ChatRecord;
import com.aresstack.spaceharvester.app.chat.ChatRoom;
import com.aresstack.spaceharvester.app.chat.ChatStore;
import com.aresstack.spaceharvester.app.chat.ChatToolset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Die Schreibwerkzeuge ändern nur abschnittsweise und antworten kurz (ADR 0045, Abschnitt 3); erfundene Texte. */
class DocumentChatToolsTest {

    @TempDir
    Path home;

    private ChatArtifactStore store;
    private Path folder;
    private String chatId;
    private final AtomicReference<String> focus = new AtomicReference<>();
    private final List<String> opened = new ArrayList<>();
    private DocumentChatTools tools;

    @BeforeEach
    void setUp() {
        ChatStore chats = new ChatStore(() -> home.resolve("chats"));
        ChatNode node = new ChatNode("wiki", null, null);
        ChatRecord chat = ChatRecord.fresh(1).plus(ChatRecord.Message.user("Schreib", 1), 1);
        chats.save(node, chat);
        store = new ChatArtifactStore(chats);
        folder = chats.folder(node);
        chatId = chat.id();
        tools = new DocumentChatTools(store, folder, chatId, focus::get, artifact -> opened.add(artifact.id()));
    }

    private String call(String name, String json) {
        return tools.run(new AiChat.ToolCall("c" + name, name, json));
    }

    @Test
    void noToolReplacesAWholeDocument() {
        assertThat(tools.definitions()).extracting(AiChat.Tool::name).containsExactlyInAnyOrder(
                "dokumente_auflisten", "dokument_anlegen", "dokument_gliederung", "abschnitt_lesen",
                "abschnitt_ersetzen", "text_ersetzen", "abschnitt_einfuegen", "abschnitt_loeschen",
                "ueberschrift_aendern", "abschnitt_verschieben", "grafik_anlegen", "grafik_elemente", "grafik_lesen",
                "grafik_element_ersetzen", "grafik_ersetzen", "grafik_einbinden");
        assertThat(tools.definitions()).allSatisfy(tool -> assertThat(tool.parametersSchemaJson()).startsWith("{\"type\""));
    }

    @Test
    void aDocumentGrowsSectionBySectionWithShortConfirmations() {
        assertThat(call("dokumente_auflisten", "{}")).contains("noch keine Dokumente");

        String created = call("dokument_anlegen",
                "{\"titel\":\"Gartenfibel\",\"text\":\"# Gartenfibel\\n\\n## Boden\\n\\n## Aussaat\"}");
        assertThat(created).contains("D1").contains("a1 # Gartenfibel").contains("a3 ## Aussaat");

        String filled = call("abschnitt_ersetzen",
                "{\"dokument\":\"D1\",\"abschnitt\":\"a2\",\"text\":\"Lockerer Boden mit viel Humus.\"}");
        assertThat(filled).startsWith("Erledigt").contains("(a2)").contains("Stand 2");
        assertThat(filled).as("nie das Dokument zurück").doesNotContain("Humus");

        assertThat(call("text_ersetzen", "{\"abschnitt\":\"a2\",\"suchen\":\"viel\",\"ersetzen\":\"etwas\"}"))
                .as("ohne Kennung das einzige Dokument").startsWith("Erledigt");
        assertThat(call("abschnitt_lesen", "{\"abschnitt\":\"a2\"}"))
                .contains("## Boden").contains("Lockerer Boden mit etwas Humus.");
        assertThat(call("abschnitt_einfuegen", "{\"nach\":\"a3\",\"ueberschrift\":\"Ernte\",\"text\":\"Im Juli.\"}"))
                .contains("(a4)");
        assertThat(call("ueberschrift_aendern", "{\"abschnitt\":\"a4\",\"ueberschrift\":\"Ernten\"}"))
                .startsWith("Erledigt");
        assertThat(call("abschnitt_verschieben", "{\"abschnitt\":\"a4\",\"nach\":\"a1\"}")).startsWith("Erledigt");
        assertThat(call("abschnitt_loeschen", "{\"abschnitt\":\"a3\"}")).startsWith("Erledigt");

        assertThat(store.read(folder, chatId, "D1").content()).isEqualTo("""
                # Gartenfibel

                ## Ernten

                Im Juli.

                ## Boden

                Lockerer Boden mit etwas Humus.
                """);
        assertThat(call("dokument_gliederung", "{}")).contains("a1 # Gartenfibel (0 Wörter)")
                .contains("a4 ## Ernten (2 Wörter)");
        assertThat(opened).containsOnly("D1");
        assertThat(tools.footer()).contains("[Gartenfibel](artefakt:D1)");
    }

    @Test
    void errorsTellTheModelWhatToDo() {
        call("dokument_anlegen", "{\"titel\":\"Brief\",\"text\":\"## Anrede\\n\\nHallo Hallo.\"}");
        call("dokument_anlegen", "{\"titel\":\"Notiz\"}");

        assertThat(call("abschnitt_lesen", "{\"abschnitt\":\"a1\"}"))
                .contains("'dokument' fehlt").contains("D1").contains("D2");
        assertThat(call("abschnitt_lesen", "{\"dokument\":\"D1\",\"abschnitt\":\"a9\"}"))
                .contains("Vorhandene Kennungen: a1").contains("dokument_gliederung");
        assertThat(call("text_ersetzen", "{\"dokument\":\"D1\",\"abschnitt\":\"a1\",\"suchen\":\"Hallo\","
                + "\"ersetzen\":\"Moin\"}")).contains("2-mal");
        assertThat(call("abschnitt_lesen", "{\"dokument\":\"D7\",\"abschnitt\":\"a1\"}"))
                .contains("dokumente_auflisten");
        assertThat(call("abschnitt_lesen", "kein json")).contains("kein gültiges JSON");
        assertThat(call("abschnitt_einfuegen", "{\"dokument\":\"D2\"}")).contains("Überschrift");
    }

    @Test
    void theOpenDocumentIsTheDefaultAndItsOutlineGoesWithTheRules() {
        call("dokument_anlegen", "{\"titel\":\"Brief\",\"text\":\"## Anrede\\n\\nHallo.\"}");
        call("dokument_anlegen", "{\"titel\":\"Notiz\",\"text\":\"## Punkt\\n\\nEins.\"}");
        focus.set("D2");

        assertThat(call("abschnitt_lesen", "{\"abschnitt\":\"a1\"}")).contains("Eins.");
        assertThat(tools.rules()).contains("D1 Dokument „Brief“").contains("gerade D2 „Notiz“ geöffnet")
                .contains("a1 ## Punkt (1 Wörter)");
    }

    @Test
    void theToolsJoinTheOtherToolsOfTheTurn() {
        ChatToolset base = new ChatToolset() {
            @Override
            public List<AiChat.Tool> definitions() {
                return List.of(new AiChat.Tool("suche", "Suche", "{}"));
            }

            @Override
            public String run(AiChat.ToolCall call) {
                return "Treffer";
            }

            @Override
            public String describe(AiChat.ToolCall call) {
                return "sucht";
            }
        };
        ChatToolset joined = tools.attach(base);

        assertThat(joined.definitions()).hasSize(17);
        assertThat(joined.run(new AiChat.ToolCall("1", "suche", "{}"))).isEqualTo("Treffer");
        assertThat(joined.run(new AiChat.ToolCall("2", "dokumente_auflisten", "{}"))).contains("noch keine");
        assertThat(joined.describe(new AiChat.ToolCall("3", "abschnitt_ersetzen", "{}")))
                .isEqualTo("Abschnitt wird überarbeitet");
    }

    @Test
    void aLongSectionIsReadInPiecesThatFitTheRequest() {
        call("dokument_anlegen", "{\"titel\":\"Fibel\",\"text\":\"## Boden\\n\\n"
                + "Humus ".repeat(200).strip() + "\"}");

        String first = ChatRoom.within(400, () -> call("abschnitt_lesen", "{\"abschnitt\":\"a1\"}"));
        assertThat(first).contains("Zeichen 0 bis").contains("versatz");
        int offset = Integer.parseInt(first.replaceAll("(?s).*versatz (\\d+)\\).*", "$1"));
        String rest = call("abschnitt_lesen", "{\"abschnitt\":\"a1\",\"versatz\":" + offset + "}");
        assertThat(rest).doesNotContain("Fortsetzung").endsWith("Humus");
        assertThat(ChatRoom.within(0, () -> call("abschnitt_lesen", "{\"abschnitt\":\"a1\"}")))
                .endsWith(ChatRoom.FULL);
    }

    private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"40\" height=\"20\">"
            + "<rect width=\"40\" height=\"20\" fill=\"#8c8\"/></svg>";

    @Test
    void aWishForAGraphicMakesAGraphicNotADocument() {
        // Angelo 27.11.2026: „Ich wollte eine Grafik erstellen, er hat mir nur ein neues Dokument erstellt.“
        assertThat(tools.rules()).contains("legst du eine Grafik an, kein Dokument")
                .doesNotContain("  grafik_anlegen").doesNotContain("     ");
    }

    @Test
    void aQuoteWithReferenceNamesTheSectionToChange() {
        // ADR 0045 §7: das Zitat aus dem Dokument trägt Artefakt und Abschnitt; die Regeln sagen, was das heißt.
        assertThat(tools.rules()).contains("(" + DocumentChatTools.QUOTE_REFERENCE + " Dokument D")
                .contains("ändere nur dort");
    }

    @Test
    void graphicsAreOwnArtifactsEmbeddedByReference() {
        List<String> drawn = new ArrayList<>();
        DocumentChatTools withDrawing = new DocumentChatTools(store, folder, chatId, focus::get,
                artifact -> opened.add(artifact.id()), (topic, material) -> {
                    drawn.add(topic + ": " + material);
                    return java.util.Optional.of(SVG);
                });
        assertThat(tools.definitions()).extracting(AiChat.Tool::name).doesNotContain("grafik_zeichnen")
                .contains("grafik_anlegen", "grafik_einbinden");
        call("dokument_anlegen", "{\"titel\":\"Gartenfibel\",\"text\":\"## Beet\\n\\nDrei Reihen.\"}");

        String drawnAnswer = withDrawing.run(new AiChat.ToolCall("1", "grafik_zeichnen",
                "{\"titel\":\"Beetplan\",\"inhalt\":\"drei Reihen Bohnen\"}"));
        assertThat(drawnAnswer).contains("G1").contains("grafik_einbinden");
        assertThat(drawn).containsExactly("Beetplan: drei Reihen Bohnen");
        assertThat(call("grafik_anlegen", "{\"titel\":\"Kaputt\",\"svg\":\"<rect/>\"}"))
                .contains("kein vollständiges SVG");
        assertThat(call("grafik_anlegen", "{\"titel\":\"Zaun\",\"svg\":\"Hier: " + SVG.replace("\"", "\\\"")
                + " fertig\"}")).contains("G2");
        assertThat(store.read(folder, chatId, "G2").current().svg()).isEqualTo(SvgElements.withIds(SVG))
                .contains("id=\"e1\"");

        assertThat(withDrawing.run(new AiChat.ToolCall("2", "grafik_einbinden",
                "{\"abschnitt\":\"a1\",\"grafik\":\"G1\"}"))).startsWith("Erledigt");
        assertThat(store.read(folder, chatId, "D1").document().section("a1").text())
                .isEqualTo("Drei Reihen.\n\n![Beetplan](artefakt:G1)");
        assertThat(withDrawing.run(new AiChat.ToolCall("3", "grafik_einbinden",
                "{\"abschnitt\":\"a1\",\"grafik\":\"G1\"}"))).contains("schon");
        assertThat(call("grafik_einbinden", "{\"abschnitt\":\"a1\",\"grafik\":\"G9\"}")).contains("G9");
        assertThat(withDrawing.footer()).as("nur Verweise, nie der Inhalt").contains("Grafik: [Beetplan](artefakt:G1)")
                .doesNotContain("<svg");

        store.replaceSvg(folder, chatId, "G1", ChatArtifactStore.ANY, ChatArtifact.Origin.USER, "", "<svg>neu</svg>");
        assertThat(GraphicReferences.inline(store.read(folder, chatId, "D1").content(),
                id -> store.read(folder, chatId, id).current().svg()))
                .as("Verweis, keine Kopie: die korrigierte Grafik steht von selbst im Dokument")
                .contains("<svg>neu</svg>").doesNotContain("artefakt:G1");
    }

    @Test
    void anExistingGraphicIsChangedElementByElement() {
        call("grafik_anlegen", "{\"titel\":\"Beetplan\",\"svg\":\"" + TWO.replace("\"", "\\\"") + "\"}");
        assertThat(tools.rules()).contains("grafik_element_ersetzen").contains("Element e…");
        assertThat(call("grafik_elemente", "{}")).as("einzige Grafik ohne Angabe")
                .contains("e1 Rechteck").contains("e2 Text · „Bohnen“");
        assertThat(call("grafik_lesen", "{\"grafik\":\"G1\",\"element\":\"e2\"}")).contains("Bohnen")
                .doesNotContain("<rect");

        assertThat(call("grafik_element_ersetzen", "{\"element\":\"e2\",\"svg\":\"<text x='2' y='9'>Erbsen</text>\"}"))
                .contains("Element e2 ersetzt").contains("Stand 2");
        String svg = store.read(folder, chatId, "G1").current().svg();
        assertThat(svg).contains("Erbsen").doesNotContain("Bohnen").contains("fill=\"#8c8\"");
        assertThat(SvgElements.element(svg, "e2")).as("Bezug bleibt gültig").contains("Erbsen");
        assertThat(opened).contains("G1");

        assertThat(call("grafik_element_ersetzen", "{\"element\":\"e9\",\"svg\":\"\"}")).contains("e9");
        // Codex-Befund zu #1650: Änderungen gehen vom neuesten Stand aus, unter dem Schloss des Speichers.
        store.changeSvg(folder, chatId, "G1", ChatArtifactStore.ANY, ChatArtifact.Origin.AI, "", svgText ->
                SvgElements.replace(svgText, "e1", "<rect width='9' height='9'/>"));
        assertThat(store.read(folder, chatId, "G1").current().svg()).contains("Erbsen").contains("width=\"9\"");
        assertThat(call("grafik_ersetzen", "{\"svg\":\"kein svg\"}")).contains("kein vollständiges SVG");
        assertThat(call("grafik_ersetzen", "{\"svg\":\"" + SVG.replace("\"", "\\\"") + "\"}")).contains("Stand 4");
        assertThat(store.read(folder, chatId, "G1").current().svg()).doesNotContain("Erbsen");
    }

    private static final String TWO = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"40\" height=\"20\">"
            + "<rect width=\"40\" height=\"20\" fill=\"#8c8\"/><text x=\"2\" y=\"9\">Bohnen</text></svg>";
}
