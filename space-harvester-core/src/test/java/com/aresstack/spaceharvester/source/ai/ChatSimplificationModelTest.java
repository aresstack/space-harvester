package com.aresstack.spaceharvester.source.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.ai.AiServiceSettings;
import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Modell der Vorstufe (ADR 0032, FL3): eigenes Modell je Schritt, sonst das Chat-Modell; ohne Netz. */
class ChatSimplificationModelTest {

    private static final AiServiceSettings AI = new AiServiceSettings(URI.create("https://ai.example.org/v1"), "key",
            "chat-model", null, null, null, null, Set.of());

    @Test
    void theStepModelReplacesTheChatModelAndIsPartOfTheVersion() {
        ChatSimplificationModel model = new ChatSimplificationModel(() -> AI, () -> "step-model", settings -> {
            throw new AssertionError("kein Aufruf");
        });
        assertThat(model.effective().chatModel()).isEqualTo("step-model");
        assertThat(model.effective().baseUrl()).isEqualTo(AI.baseUrl());
        assertThat(model.version()).startsWith("sentence-simplification:step-model@").doesNotContain("example");
        assertThat(model.available()).isTrue();
    }

    @Test
    void withoutStepModelTheChatModelApplies() {
        ChatSimplificationModel model = new ChatSimplificationModel(() -> AI, () -> " ", settings -> {
            throw new AssertionError("kein Aufruf");
        });
        assertThat(model.version()).startsWith("sentence-simplification:chat-model@");
    }

    @Test
    void withoutServiceNothingIsAvailable() {
        ChatSimplificationModel model = new ChatSimplificationModel(() -> null, () -> "step-model", settings -> {
            throw new AssertionError("kein Aufruf");
        });
        assertThat(model.available()).isFalse();
        assertThat(model.version()).isEqualTo("sentence-simplification:none");
    }
}
