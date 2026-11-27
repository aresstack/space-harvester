package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChatException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatTurnContextExceededTest {

    @Test
    void aTooLongRequestIsNotMistakenForAModelWithoutTools() {
        AiChatException german = new AiChatException(AiChatException.Kind.INVALID_REQUEST, 400,
                "HTTP 400: Die Anfrage überschreitet die Kontextlänge des Modells beispiel/modell: Der Prompt umfasst "
                        + "300000 Tokens", null);
        AiChatException openAi = new AiChatException(AiChatException.Kind.INVALID_REQUEST, 400, "Fehler",
                new IllegalStateException("This model's maximum context length is 128000 tokens"));
        AiChatException tools = new AiChatException(AiChatException.Kind.INVALID_REQUEST, 400,
                "tools are not supported for this model", null);
        AiChatException server = new AiChatException(AiChatException.Kind.PROVIDER_ERROR, 500,
                "context length", null);
        assertThat(ChatTurn.contextExceeded(german)).isTrue();
        assertThat(ChatTurn.contextExceeded(openAi)).as("Ursache in der Kette").isTrue();
        assertThat(ChatTurn.contextExceeded(tools)).isFalse();
        assertThat(ChatTurn.contextExceeded(server)).as("nur abgewiesene Anfragen").isFalse();
    }
}
