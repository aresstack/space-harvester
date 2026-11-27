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
                "ueberschrift_aendern", "abschnitt_verschieben");
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

        assertThat(joined.definitions()).hasSize(11);
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
}
