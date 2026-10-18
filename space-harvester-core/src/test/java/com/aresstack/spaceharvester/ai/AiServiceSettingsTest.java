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
