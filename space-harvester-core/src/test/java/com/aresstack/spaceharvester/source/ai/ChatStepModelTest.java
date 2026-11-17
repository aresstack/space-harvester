package com.aresstack.spaceharvester.source.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.ai.AiServiceSettings;
import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Modell eines Analyseschritts (ADR 0032, FL3/FL4): eigenes Modell je Schritt, sonst das Chat-Modell; ohne Netz. */
class ChatStepModelTest {

    private static final AiServiceSettings AI = new AiServiceSettings(URI.create("https://ai.example.org/v1"), "key",
            "chat-model", null, null, null, null, Set.of());

    @Test
    void theStepModelReplacesTheChatModelAndIsPartOfTheVersion() {
        ChatStepModel model = new ChatStepModel(ChatStepModel.SIMPLIFICATION, () -> AI, () -> "step-model", settings -> {
            throw new AssertionError("kein Aufruf");
        });
        assertThat(model.effective().chatModel()).isEqualTo("step-model");
        assertThat(model.effective().baseUrl()).isEqualTo(AI.baseUrl());
        assertThat(model.version()).startsWith("sentence-simplification:step-model@").doesNotContain("example");
        assertThat(model.available()).isTrue();
    }

    @Test
    void withoutStepModelTheChatModelApplies() {
        ChatStepModel model = new ChatStepModel(ChatStepModel.SIMPLIFICATION, () -> AI, () -> " ", settings -> {
            throw new AssertionError("kein Aufruf");
        });
        assertThat(model.version()).startsWith("sentence-simplification:chat-model@");
    }

    @Test
    void bothStepsShareTheAdapterButNotTheVersion() {
        ChatStepModel check = new ChatStepModel(ChatStepModel.CHECK, () -> AI, () -> "", settings -> {
            throw new AssertionError("kein Aufruf");
        });
        assertThat(check.version()).startsWith("statement-check:chat-model@");
    }

    @Test
    void withoutServiceNothingIsAvailable() {
        ChatStepModel model = new ChatStepModel(ChatStepModel.SIMPLIFICATION, () -> null, () -> "step-model", settings -> {
            throw new AssertionError("kein Aufruf");
        });
        assertThat(model.available()).isFalse();
        assertThat(model.version()).isEqualTo("sentence-simplification:none");
    }
}
