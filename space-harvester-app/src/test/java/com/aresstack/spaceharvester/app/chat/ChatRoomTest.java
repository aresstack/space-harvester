package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.app.runtime.ModelBudget;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRoomTest {

    @Test
    void withoutKnownContextLengthThereIsNoLimit() {
        assertThat(ChatRoom.left(null, List.of(AiChat.Message.user("Frage")), List.of(), 1))
                .isEqualTo(ChatRoom.UNLIMITED);
        assertThat(ChatRoom.chars()).as("außerhalb eines Turns").isEqualTo(ChatRoom.UNLIMITED);
    }

    @Test
    void theRoomFollowsTheModelAndShrinksWithTheConversation() {
        ModelBudget small = new ModelBudget(2_000, 500);
        ModelBudget large = new ModelBudget(200_000, 500);
        List<AiChat.Message> asked = List.of(AiChat.Message.user("Wie läuft der Nachtlauf?"));
        int smallRoom = ChatRoom.left(small, asked, List.of(), 1);
        assertThat(ChatRoom.left(large, asked, List.of(), 1)).isGreaterThan(smallRoom * 50);
        List<AiChat.Message> longer = List.of(asked.get(0), AiChat.Message.user("x ".repeat(1_000)));
        assertThat(ChatRoom.left(small, longer, List.of(), 1)).isLessThan(smallRoom);
        assertThat(ChatRoom.left(small, asked, List.of(), 2)).as("zwei Aufrufe teilen sich den Platz")
                .isEqualTo(smallRoom / 2);
        assertThat(ChatRoom.left(small, List.of(AiChat.Message.user("x ".repeat(5_000))), List.of(), 1))
                .as("voll").isZero();
    }

    @Test
    void aDeckTextComesInPiecesThatFitAndContinuesWithAb() {
        String text = "a".repeat(250);
        String first = ChatRoom.within(100, () -> DeckChatTools.page("Editor", text, 0));
        assertThat(first).contains("Zeichen 0–100 von 250").contains("weiter mit ab=100");
        assertThat(DeckChatTools.page("Editor", text, 0)).as("ohne Grenze ganz").doesNotContain("weiter mit");
        assertThat(ChatRoom.within(0, () -> DeckChatTools.page("Editor", text, 0))).isEqualTo(ChatRoom.FULL);
        assertThat(ChatRoom.chars()).as("danach wieder ohne Grenze").isEqualTo(ChatRoom.UNLIMITED);
    }

    @Test
    void aBinaryFileInTheViewStaysInsideTheRoomInTokensNotJustInCharacters() {
        // Erfundener Inhalt eines Lademoduls, als Text gelesen; früher ging die Ansicht nach Zeichen gekürzt mit und
        // sprengte die Anfrage in Tokens (HTTP 400 „Prompt umfasst 727218 Tokens“ bei 128000 Kontext).
        byte[] raw = new byte[200_000];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i % 256);
        }
        String salad = new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1);
        javax.swing.JTextArea editor = new javax.swing.JTextArea(salad);
        javax.swing.JPanel view = new javax.swing.JPanel();
        view.add(editor);
        int room = 30_000;
        String shown = ChatRoom.within(room, () -> new DeckChatTools(() -> view, () -> true, null).snapshot());
        assertThat(ModelBudget.tokens(shown)).as("Ansicht im Platz der Anfrage, in Tokens gemessen")
                .isLessThanOrEqualTo(room / ModelBudget.CHARACTERS_PER_TOKEN + 64);
        assertThat(shown).contains("text_lesen nr=1");
        String piece = ChatRoom.within(room, () -> DeckChatTools.page("Editor", salad, 0));
        assertThat(ModelBudget.tokens(piece)).isLessThanOrEqualTo(room / ModelBudget.CHARACTERS_PER_TOKEN + 64);
        assertThat(piece).contains("weiter mit ab=");
    }
}
