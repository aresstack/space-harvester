package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Werkzeugaufrufe als Antworttext; erfundene Daten (Suche nach „Nachtlauf“, Seite „wiki:42“). */
class TextToolCallsTest {

    private static final List<AiChat.Tool> TOOLS = List.of(
            new AiChat.Tool("im_bereich_suchen", "Sucht.", "{\"type\":\"object\",\"properties\":{\"text\":"
                    + "{\"type\":\"string\"}},\"required\":[\"text\"]}"),
            new AiChat.Tool("seite_lesen", "Liest.", "{\"type\":\"object\",\"properties\":{\"kennung\":"
                    + "{\"type\":\"string\"},\"versatz\":{\"type\":\"integer\"}},\"required\":[\"kennung\"]}"));

    @Test
    void theCmdArrayMapsItsValuesOntoTheParametersInOrder() {
        assertThat(TextToolCalls.parse("We will call the search.\n{\"cmd\":[\"im_bereich_suchen\",\"Nachtlauf\"]}",
                TOOLS)).containsExactly(new AiChat.ToolCall("text_call_0", "im_bereich_suchen",
                "{\"text\":\"Nachtlauf\"}"));
        assertThat(TextToolCalls.parse("{\"cmd\":[\"seite_lesen\",\"wiki:42\",3000]}", TOOLS))
                .containsExactly(new AiChat.ToolCall("text_call_0", "seite_lesen",
                        "{\"kennung\":\"wiki:42\",\"versatz\":3000}"));
    }

    @Test
    void nameAndArgumentsObjectsAndToolCallTagsAreRecognised() {
        assertThat(TextToolCalls.parse("<tool_call>{\"name\":\"im_bereich_suchen\",\"arguments\":"
                + "{\"text\":\"Nachtlauf\"}}</tool_call>", TOOLS)).extracting(AiChat.ToolCall::argumentsJson)
                .containsExactly("{\"text\":\"Nachtlauf\"}");
        assertThat(TextToolCalls.parse("{\"name\":\"functions.seite_lesen\",\"arguments\":\"{\\\"kennung\\\":"
                + "\\\"wiki:42\\\"}\"}", TOOLS)).extracting(AiChat.ToolCall::name).containsExactly("seite_lesen");
        assertThat(TextToolCalls.parse("{\"function\":{\"name\":\"im_bereich_suchen\",\"arguments\":"
                + "{\"text\":\"Plan\"}}}", TOOLS)).extracting(AiChat.ToolCall::name)
                .containsExactly("im_bereich_suchen");
    }

    @Test
    void aNameRightBeforeAnObjectIsACall() {
        assertThat(TextToolCalls.parse("to=functions.im_bereich_suchen <|constrain|>json<|message|>"
                + "{\"text\":\"Nachtlauf\"}", TOOLS)).containsExactly(new AiChat.ToolCall("text_call_0",
                "im_bereich_suchen", "{\"text\":\"Nachtlauf\"}"));
        assertThat(TextToolCalls.parse("seite_lesen({\"kennung\":\"wiki:42\"})", TOOLS))
                .extracting(AiChat.ToolCall::name).containsExactly("seite_lesen");
    }

    @Test
    void unknownToolsAndOrdinaryAnswersAreNoCalls() {
        assertThat(TextToolCalls.parse("{\"cmd\":[\"bash\",\"-lc\",\"ls\"]}", TOOLS)).isEmpty();
        assertThat(TextToolCalls.parse("Der Nachtlauf bricht bei vollem Spool ab [1].", TOOLS)).isEmpty();
        assertThat(TextToolCalls.parse("Die Konfiguration sieht so aus: {\"timeout\": 30}", TOOLS)).isEmpty();
        assertThat(TextToolCalls.parse("{\"cmd\":[\"im_bereich_suchen\",\"x\"]}", List.of())).isEmpty();
    }

    @Test
    void shortAnnouncementsCountButGermanAnswersDoNot() {
        assertThat(TextToolCalls.announces("We will perform a silent tool call.")).isTrue();
        assertThat(TextToolCalls.announces("We need to call search.")).isTrue();
        assertThat(TextToolCalls.announces("Let me search for that.")).isTrue();
        assertThat(TextToolCalls.announces("Ich habe mit im_bereich_suchen gesucht und nichts gefunden.")).isFalse();
        assertThat(TextToolCalls.announces("Im Bereich steht dazu nichts.")).isFalse();
        assertThat(TextToolCalls.announces("")).isFalse();
    }

    @Test
    void storedAnswersThatWereOnlyToolCallsAsTextAreLeaks() {
        assertThat(TextToolCalls.leaked("We need to call the tool correctly.\n{\"cmd\":[\"im_bereich_suchen\","
                + "\"Plan\"]}")).isTrue();
        assertThat(TextToolCalls.leaked("We will perform a silent tool call.")).isTrue();
        assertThat(TextToolCalls.leaked("Der Plan steht auf der Startseite [1].")).isFalse();
    }

    @Test
    void textThatCouldBecomeACallIsHeldBack() {
        assertThat(TextToolCalls.suspicious("We will call", TOOLS)).isTrue();
        assertThat(TextToolCalls.suspicious("{\"cmd\":[\"im_ber", TOOLS)).isTrue();
        assertThat(TextToolCalls.suspicious("Der Nachtlauf bricht ab, weil der Spool voll ist.", TOOLS)).isFalse();
    }
}
