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

    @Test
    void theStepMaxTokensReplaceTheChatMaxTokens() {
        ChatStepModel model = new ChatStepModel("image", () -> AI, () -> null, () -> 32768, settings -> {
            throw new AssertionError("kein Aufruf");
        });
        assertThat(model.effective().chatModel()).isEqualTo("chat-model");
        assertThat(model.effective().chatMaxTokens()).isEqualTo(32768);
    }

    @Test
    void theImageGenerationHasItsOwnModelAndAHigherStandardLength() {
        assertThat(AI.imageGeneration().chatModel()).isEqualTo("chat-model");
        assertThat(AI.imageGeneration().chatMaxTokens()).isEqualTo(AiServiceSettings.DEFAULT_IMAGE_MAX_TOKENS)
                .isEqualTo(16384);
        AiServiceSettings own = AI.withImageGeneration("svg-model", 65536);
        ChatStepModel model = new ChatStepModel("image", own::imageGeneration, () -> null, settings -> {
            throw new AssertionError("kein Aufruf");
        });
        assertThat(model.effective().chatModel()).isEqualTo("svg-model");
        assertThat(model.effective().chatMaxTokens()).isEqualTo(65536);
        assertThat(own.chatMaxTokens()).isEqualTo(AiServiceSettings.DEFAULT_CHAT_MAX_TOKENS);
    }

    @Test
    void theStepThinkingReplacesTheThinkingOfTheTextModelOnlyWhenSet() {
        ChatStepModel inherits = new ChatStepModel("check", () -> AI.withStepThinking("low"), () -> null,
                () -> null, () -> null, settings -> {
                    throw new AssertionError("kein Aufruf");
                });
        ChatStepModel own = new ChatStepModel("check", () -> AI, () -> null, () -> null, () -> "high", settings -> {
            throw new AssertionError("kein Aufruf");
        });

        assertThat(inherits.effective().stepThinking()).isEqualTo("low");
        assertThat(own.effective().stepThinking()).isEqualTo("high");
        assertThat(AI.stepThinking()).isEqualTo(AiServiceSettings.DEFAULT_STEP_THINKING).isEqualTo("none");
    }

    @Test
    void visionAndImageGenerationThinkLikeTheTextModelUntilTheyHaveTheirOwn() {
        AiServiceSettings text = AI.withStepThinking("medium");
        assertThat(text.visionThinkingLevel()).isEqualTo("medium");
        assertThat(text.imageGeneration().stepThinking()).isEqualTo("medium");

        AiServiceSettings own = text.withThinking("none", "HIGH");
        assertThat(own.visionThinkingLevel()).isEqualTo("none");
        assertThat(own.imageGeneration().stepThinking()).isEqualTo("high");
        assertThat(own.stepThinking()).isEqualTo("medium");
    }

    @Test
    void summaryPlanAndPodcastHaveTheirOwnLinesWithSensibleStandards() {
        AiServiceSettings thinking = AI.withStepThinking("high");
        assertThat(thinking.summary().chatModel()).isEqualTo("chat-model");
        assertThat(thinking.summary().chatMaxTokens()).isEqualTo(AiServiceSettings.DEFAULT_SUMMARY_MAX_TOKENS)
                .isEqualTo(32768);
        assertThat(thinking.summary().stepThinking()).as("Zusammenfassung denkt standardmäßig nicht").isEqualTo("none");
        assertThat(thinking.summaryPlan().chatMaxTokens()).isEqualTo(16384);
        assertThat(thinking.summaryPlan().stepThinking()).isEqualTo("none");
        assertThat(thinking.podcast().chatMaxTokens()).isEqualTo(16384);
        assertThat(thinking.podcast().stepThinking()).isEqualTo("none");

        AiServiceSettings own = AI.withUses(new AiServiceSettings.ModelUse("teile-modell", 65536, "low"),
                new AiServiceSettings.ModelUse(null, 8192, "medium"))
                .withSummaryPlan(new AiServiceSettings.ModelUse("plan-modell", null, "HIGH"));
        assertThat(own.summary().chatModel()).isEqualTo("teile-modell");
        assertThat(own.summary().chatMaxTokens()).isEqualTo(65536);
        assertThat(own.summary().stepThinking()).isEqualTo("low");
        assertThat(own.summaryPlan().chatModel()).isEqualTo("plan-modell");
        assertThat(own.summaryPlan().chatMaxTokens()).isEqualTo(16384);
        assertThat(own.summaryPlan().stepThinking()).isEqualTo("high");
        assertThat(own.podcast().chatModel()).isEqualTo("chat-model");
        assertThat(own.podcast().chatMaxTokens()).isEqualTo(8192);
        assertThat(own.podcast().stepThinking()).isEqualTo("medium");
        assertThat(own.chatMaxTokens()).isEqualTo(AiServiceSettings.DEFAULT_CHAT_MAX_TOKENS);
    }

    @Test
    void theSummaryModelUsesItsOwnLineAndThePlanItsPlanLine() {
        AiServiceSettings own = AI.withUses(new AiServiceSettings.ModelUse("teile-modell", null, null), null)
                .withSummaryPlan(new AiServiceSettings.ModelUse("plan-modell", null, null));
        ChatUpdateSummaryModel parts = new ChatUpdateSummaryModel(() -> own, settings -> {
            throw new AssertionError("kein Aufruf");
        });
        ChatUpdateSummaryModel plan = new ChatUpdateSummaryModel(() -> own, settings -> {
            throw new AssertionError("kein Aufruf");
        }, AiServiceSettings::summaryPlan);
        assertThat(parts.version()).contains(":teile-modell@");
        assertThat(plan.version()).contains(":plan-modell@");
    }
}
