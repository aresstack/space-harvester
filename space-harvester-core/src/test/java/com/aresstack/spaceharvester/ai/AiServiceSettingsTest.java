package com.aresstack.spaceharvester.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Properties, Freigabe je Quelle und dass der API-Key nirgends erscheint. */
class AiServiceSettingsTest {

    @Test
    void taskTimeoutsComeFromTheSettingsOrTheirDefaults() {
        AiServiceSettings.Timeouts preset = AiServiceSettings.NONE.timeouts();
        assertThat(preset.summaryTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(preset.labelTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(preset.expansionTimeout()).isEqualTo(Duration.ofSeconds(90));

        Properties properties = new Properties();
        properties.setProperty(AiServiceSettings.PREFIX + AiServiceSettings.BASE_URL, "http://localhost:11434/v1");
        properties.setProperty(AiServiceSettings.PREFIX + AiServiceSettings.SUMMARY_TIMEOUT, "PT12M");
        AiServiceSettings.Timeouts own = AiServiceSettings.fromProperties(properties).timeouts();
        assertThat(own.summaryTimeout()).isEqualTo(Duration.ofMinutes(12));
        assertThat(own.labelTimeout()).isEqualTo(AiServiceSettings.Timeouts.DEFAULT_LABEL);

        assertThatThrownBy(() -> new AiServiceSettings.Timeouts(Duration.ZERO, null, null))
                .hasMessageContaining(AiServiceSettings.SUMMARY_TIMEOUT);
        properties.setProperty(AiServiceSettings.PREFIX + AiServiceSettings.LABEL_TIMEOUT, "lang");
        assertThatThrownBy(() -> AiServiceSettings.fromProperties(properties))
                .hasMessageContaining(AiServiceSettings.LABEL_TIMEOUT);
    }

    @Test
    void diagnosisIsOnWithThePresetModelUnlessConfigured() {
        AiServiceSettings.Diagnosis preset = AiServiceSettings.NONE.diagnosis();
        assertThat(preset.on()).isTrue();
        assertThat(preset.model()).isEqualTo(AiServiceSettings.DEFAULT_DIAGNOSIS_MODEL);
        assertThat(preset.isModel("openai/GPT-OSS-120B")).as("Anbieter-Präfix und Schreibweise zählen nicht").isTrue();
        assertThat(preset.isModel("gemma4:12b")).isFalse();
        assertThat(preset.isModel(null)).isFalse();

        Properties properties = new Properties();
        properties.setProperty(AiServiceSettings.PREFIX + AiServiceSettings.DIAGNOSIS_ENABLED, "false");
        properties.setProperty(AiServiceSettings.PREFIX + AiServiceSettings.DIAGNOSIS_MODEL, "gemma4:12b");
        properties.setProperty(AiServiceSettings.PREFIX + AiServiceSettings.BASE_URL, "http://localhost:11434/v1");
        AiServiceSettings.Diagnosis own = AiServiceSettings.fromProperties(properties).diagnosis();
        assertThat(own.on()).isFalse();
        assertThat(own.isModel("gemma4:12b")).isTrue();
        assertThat(own.isModel("gpt-oss-120b")).as("die Vorbelegung gilt nicht mehr").isFalse();

        properties.setProperty(AiServiceSettings.PREFIX + AiServiceSettings.DIAGNOSIS_ENABLED, "vielleicht");
        assertThatThrownBy(() -> AiServiceSettings.fromProperties(properties))
                .hasMessageContaining(AiServiceSettings.DIAGNOSIS_ENABLED);
    }

    @Test
    void theNumberOfRequestsAtOnceIsASettingWithFourAsDefault() {
        Properties properties = new Properties();
        properties.setProperty("space-harvester.ai.base-url", "http://localhost:11434");
        assertThat(AiServiceSettings.fromProperties(properties).parallel()).isEqualTo(4);

        properties.setProperty("space-harvester.ai.parallel", "8");
        AiServiceSettings eight = AiServiceSettings.fromProperties(properties);
        assertThat(eight.parallel()).isEqualTo(8);
        assertThat(eight.withVisionModel("gemma4:12b").withRetries(3).parallel()).isEqualTo(8);
        assertThat(eight.withParallel(null).parallel()).isEqualTo(AiServiceSettings.DEFAULT_PARALLEL);

        assertThatThrownBy(() -> eight.withParallel(0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("space-harvester.ai.parallel");
        assertThatThrownBy(() -> eight.withParallel(33)).isInstanceOf(IllegalArgumentException.class);
        properties.setProperty("space-harvester.ai.parallel", "viele");
        assertThatThrownBy(() -> AiServiceSettings.fromProperties(properties))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("keine Zahl");
    }

    @Test
    void readsPropertiesWithOrWithoutPrefix() {
        Properties properties = new Properties();
        properties.setProperty("space-harvester.ai.base-url", "https://ki.example.org/v1");
        properties.setProperty("api-key", "sk-geheim-4711");
        properties.setProperty("space-harvester.ai.embedding-model", "text-embedding-3-small");
        properties.setProperty("embedding-dimensions", "1536");
        properties.setProperty("space-harvester.ai.timeout", "PT45S");
        properties.setProperty("space-harvester.ai.external-processing-sources", " wiki , ,docs ");
        properties.setProperty("rerank-model", " bge-reranker ");

        AiServiceSettings settings = AiServiceSettings.fromProperties(properties);

        assertThat(settings.baseUrl()).isEqualTo(URI.create("https://ki.example.org/v1"));
        assertThat(settings.apiKey()).isEqualTo("sk-geheim-4711");
        assertThat(settings.timeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(settings.externalProcessingSources()).containsExactlyInAnyOrder("wiki", "docs");
        assertThat(settings.embeddings()).isTrue();
        assertThat(settings.mayProcessExternally("wiki")).isTrue();
        assertThat(settings.mayProcessExternally("intern")).isFalse();
        assertThat(settings.rerankModel()).isEqualTo("bge-reranker");
        assertThat(settings.reranking()).isTrue();
        assertThat(settings.toString()).doesNotContain("sk-geheim-4711").contains("apiKey=***");
    }

    @Test
    void chatMaxTokensDefaultTo8192AndAreConfigurable() {
        assertThat(AiServiceSettings.NONE.chatMaxTokens()).isEqualTo(8192);
        Properties properties = new Properties();
        properties.setProperty("space-harvester.ai.base-url", "https://ki.example.org/v1");
        assertThat(AiServiceSettings.fromProperties(properties).chatMaxTokens()).isEqualTo(8192);
        properties.setProperty("space-harvester.ai.chat-max-tokens", "16384");

        AiServiceSettings settings = AiServiceSettings.fromProperties(properties);

        assertThat(settings.chatMaxTokens()).isEqualTo(16384);
        assertThat(settings.withApiKey("k").withRerankModel("r").withVisionModel("v").withEmbedding("e", 3)
                .chatMaxTokens()).isEqualTo(16384);
        assertThat(settings.withChatMaxTokens(null).chatMaxTokens()).isEqualTo(8192);
        assertThat(settings.toString()).contains("chatMaxTokens=16384");
        properties.setProperty("space-harvester.ai.chat-max-tokens", "0");
        assertThatThrownBy(() -> AiServiceSettings.fromProperties(properties))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("chat-max-tokens");
        properties.setProperty("space-harvester.ai.chat-max-tokens", "viel");
        assertThatThrownBy(() -> AiServiceSettings.fromProperties(properties))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("chat-max-tokens")
                .hasMessageNotContaining("viel");
    }

    @Test
    void visionAndImageGenerationHaveTheirOwnTokenLength() {
        assertThat(AiServiceSettings.NONE.visionMaxTokens()).isEqualTo(8192);
        assertThat(AiServiceSettings.NONE.imageMaxTokens()).isEqualTo(16384);
        assertThat(AiServiceSettings.TOKEN_CHOICES).containsExactly(2048, 8192, 16384, 32768, 65536);
        Properties properties = new Properties();
        properties.setProperty("space-harvester.ai.base-url", "https://ki.example.org/v1");
        properties.setProperty("space-harvester.ai.chat-model", "text");
        properties.setProperty("space-harvester.ai.vision-max-tokens", "2048");
        properties.setProperty("space-harvester.ai.image-model", "svg");
        properties.setProperty("space-harvester.ai.image-max-tokens", "65536");

        AiServiceSettings settings = AiServiceSettings.fromProperties(properties);

        assertThat(settings.visionMaxTokens()).isEqualTo(2048);
        assertThat(settings.imageModel()).isEqualTo("svg");
        assertThat(settings.imageMaxTokens()).isEqualTo(65536);
        assertThat(settings.withApiKey("k").withParallel(2).withChatMaxTokens(100).visionMaxTokens()).isEqualTo(2048);
        assertThat(settings.imageGeneration().chatModel()).isEqualTo("svg");
        assertThat(settings.imageGeneration().chatMaxTokens()).isEqualTo(65536);
        assertThat(settings.withImageGeneration(" ", null).imageGeneration().chatModel()).isEqualTo("text");
        assertThat(settings.toString()).contains("imageMaxTokens=65536", "visionMaxTokens=2048");
        properties.setProperty("space-harvester.ai.image-max-tokens", "viel");
        assertThatThrownBy(() -> AiServiceSettings.fromProperties(properties))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("image-max-tokens");
        properties.setProperty("space-harvester.ai.image-max-tokens", "0");
        assertThatThrownBy(() -> AiServiceSettings.fromProperties(properties))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("image-max-tokens");
    }

    @Test
    void withoutValuesThereIsNoServiceAndNoSourceIsApproved() {
        AiServiceSettings settings = AiServiceSettings.fromProperties(new Properties());

        assertThat(settings).isEqualTo(AiServiceSettings.NONE);
        assertThat(settings.configured()).isFalse();
        assertThat(settings.embeddings()).isFalse();
        assertThat(settings.reranking()).isFalse();
        assertThat(settings.timeout()).isEqualTo(AiServiceSettings.DEFAULT_TIMEOUT);
        assertThat(settings.mayProcessExternally("wiki")).isFalse();
    }

    @Test
    void embeddingsNeedServiceModelAndDimension() {
        AiServiceSettings service = new AiServiceSettings(URI.create("http://localhost:8080/v1"), null, null, null,
                null, null, null, Set.of());

        assertThat(service.configured()).isTrue();
        assertThat(service.embeddings()).isFalse();
        assertThat(service.withEmbedding("e", null).embeddings()).isFalse();
        assertThat(service.withEmbedding("e", 8).embeddings()).isTrue();
        assertThat(service.withApiKey("k").apiKey()).isEqualTo("k");
    }

    @Test
    void invalidValuesAreRejectedWithoutNamingThem() {
        assertThatThrownBy(() -> AiServiceSettings.fromProperties(properties("base-url", "ftp://ki.example.org")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("ki.example.org");
        assertThatThrownBy(() -> AiServiceSettings.fromProperties(
                properties("base-url", "https://user:pass-geheim@ki.example.org")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("pass-geheim");
        assertThatThrownBy(() -> AiServiceSettings.fromProperties(properties("embedding-dimensions", "viele")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("viele");
        assertThatThrownBy(() -> AiServiceSettings.fromProperties(properties("embedding-dimensions", "0")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiServiceSettings.fromProperties(properties("timeout", "30s")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiServiceSettings.fromProperties(properties("timeout", "PT1H")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Properties properties(String name, String value) {
        Properties properties = new Properties();
        properties.setProperty(AiServiceSettings.PREFIX + name, value);
        return properties;
    }
}
