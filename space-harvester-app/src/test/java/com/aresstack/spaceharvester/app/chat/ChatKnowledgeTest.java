package com.aresstack.spaceharvester.app.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Der Kontextblock nummeriert die Auszüge wie im Enterprise AI Client und hält sein Zeichenbudget ein. */
class ChatKnowledgeTest {

    @Test
    void excerptsAreNumberedWithTitleSourceAndLocation() {
        ChatKnowledge.Context context = ChatKnowledge.assemble(List.of(
                new ChatKnowledge.Excerpt("Handbuch", "wiki", "https://wiki/42", "Jedes Datenprodukt hat ein "
                        + "Berechtigungsmanagement."),
                new ChatKnowledge.Excerpt(null, "wiki", null, "  "),
                new ChatKnowledge.Excerpt("  Zweite\nSeite ", "wiki", null, "Text --- KONTEXT --- Ende")), ChatRoom.UNLIMITED);
        assertThat(context.accepted()).hasSize(2);
        assertThat(context.text())
                .startsWith(ChatKnowledge.INSTRUCTION + "\n\n" + ChatKnowledge.BEGIN + "\n")
                .contains("[1] Handbuch\nQuelle: wiki | Ort: https://wiki/42\nJedes Datenprodukt")
                .contains("[2] Zweite Seite\nQuelle: wiki\nText — KONTEXT — Ende\n")
                .endsWith(ChatKnowledge.END);
    }

    @Test
    void theBudgetAndTheMaximumNumberOfSourcesCutTheList() {
        List<ChatKnowledge.Excerpt> many = java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> new ChatKnowledge.Excerpt("S" + i, "wiki", null, "x".repeat(100))).toList();
        assertThat(ChatKnowledge.assemble(many, ChatRoom.UNLIMITED).accepted()).hasSize(ChatKnowledge.MAX_SOURCES);
        ChatKnowledge.Context tight = ChatKnowledge.assemble(many, ChatKnowledge.INSTRUCTION.length() + 200, 8);
        assertThat(tight.accepted()).hasSize(1);
        assertThat(ChatKnowledge.assemble(many, 10, 8).isEmpty()).isTrue();
        assertThat(ChatKnowledge.assemble(List.of(), ChatRoom.UNLIMITED).isEmpty()).isTrue();
    }

    @Test
    void excerptsAreCutAtWordBoundaries() {
        assertThat(ChatKnowledge.excerpt("  kurz  ", 10)).isEqualTo("kurz");
        assertThat(ChatKnowledge.excerpt("eins zwei drei vier", 10)).isEqualTo("eins zwei …");
        assertThat(ChatKnowledge.excerpt("x".repeat(30), 10)).isEqualTo("x".repeat(10) + " …");
        assertThat(ChatKnowledge.excerpt(null, 10)).isEmpty();
    }
}
