package com.aresstack.enterpriseai.astu.chat;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChatConversationTest {

    private final ChatConversationId id = new ChatConversationId("c1");

    @Test
    public void appendKeepsInsertionOrderAndLeavesOriginalUntouched() {
        ChatConversation empty = ChatConversation.empty(id);
        ChatConversation one = empty.append(ChatMessage.user("a"));
        ChatConversation two = one.append(ChatMessage.assistant("b")).append(ChatMessage.user("c"));

        assertTrue(empty.isEmpty());
        assertEquals(1, one.messages().size());
        assertEquals(Arrays.asList(ChatMessage.user("a"), ChatMessage.assistant("b"), ChatMessage.user("c")),
                two.messages());
    }

    @Test
    public void systemPromptAlwaysLeadsTheRequestMessages() {
        ChatConversation conversation = ChatConversation.empty(id)
                .append(ChatMessage.user("frage"))
                .append(ChatMessage.assistant("antwort"))
                .withSystemPrompt("Du bist hilfreich.");

        List<ChatMessage> request = conversation.requestMessages();
        assertEquals(ChatMessage.system("Du bist hilfreich."), request.get(0));
        assertEquals(ChatMessage.user("frage"), request.get(1));
        assertEquals(ChatMessage.assistant("antwort"), request.get(2));
        assertEquals("history excludes the system prompt", 2, conversation.messages().size());
    }

    @Test
    public void blankSystemPromptMeansNone() {
        ChatConversation conversation = ChatConversation.empty(id).withSystemPrompt("  ")
                .append(ChatMessage.user("x"));
        assertNull(conversation.systemPrompt());
        assertEquals(Collections.singletonList(ChatMessage.user("x")), conversation.requestMessages());
    }

    @Test
    public void clearedKeepsSystemPromptAndIdentity() {
        ChatConversation cleared = ChatConversation.empty(id).withSystemPrompt("sys")
                .append(ChatMessage.user("x")).cleared();
        assertTrue(cleared.isEmpty());
        assertEquals("sys", cleared.systemPrompt());
        assertEquals(id, cleared.id());
    }

    @Test(expected = IllegalArgumentException.class)
    public void systemMessagesDoNotBelongIntoTheHistory() {
        ChatConversation.empty(id).append(ChatMessage.system("sys"));
    }

    @Test
    public void historyIsUnmodifiable() {
        ChatConversation conversation = ChatConversation.empty(id).append(ChatMessage.user("x"));
        try {
            conversation.messages().add(ChatMessage.user("y"));
            fail("history must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            assertEquals(1, conversation.messages().size());
        }
    }

    @Test
    public void developerRoleIsAvailableInTheModel() {
        ChatConversation conversation = ChatConversation.empty(id).append(ChatMessage.developer("regel"));
        assertEquals(ChatRole.DEVELOPER, conversation.messages().get(0).role());
    }

    @Test
    public void toStringDoesNotRevealContent() {
        ChatMessage message = ChatMessage.user("geheimes Passwort 1234");
        assertFalse(message.toString().contains("Passwort"));
        ChatConversation conversation = ChatConversation.empty(id).withSystemPrompt("geheim").append(message);
        assertFalse(conversation.toString().contains("geheim"));
    }
}
