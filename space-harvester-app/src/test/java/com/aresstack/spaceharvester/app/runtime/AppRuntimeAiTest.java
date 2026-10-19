package com.aresstack.spaceharvester.app.runtime;

import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppRuntimeAiTest {

    @Test
    void theRerankModelReachesTheCoreOnlyWhenSet() {
        AppSettings.AiSettings with = new AppSettings.AiSettings("https://ki.firma.de/v1", null,
                "text-embedding-3-small", 1536, null, List.of(), "bge-reranker", null, null);
        AppSettings.AiSettings without = new AppSettings.AiSettings("https://ki.firma.de/v1", null,
                "text-embedding-3-small", 1536, null, List.of());

        assertThat(AppRuntime.aiService(with, "key", "test").rerankModel()).isEqualTo("bge-reranker");
        assertThat(AppRuntime.aiService(without, "key", "test").rerankModel()).isNull();
        assertThat(AppRuntime.aiService(AppSettings.AiSettings.NONE, null, "test")).isEqualTo(AiServiceSettings.NONE);
    }

    @Test
    void theVisionModelReachesTheCoreOnlyWhenSet() {
        AppSettings.AiSettings with = new AppSettings.AiSettings("https://ki.firma.de/v1", null, null, null, null,
                List.of(), null, null, null, "gpt-4o-mini");
        AppSettings.AiSettings without = new AppSettings.AiSettings("https://ki.firma.de/v1", null, null, null, null,
                List.of());

        assertThat(AppRuntime.aiService(with, "key", "test").visionModel()).isEqualTo("gpt-4o-mini");
        assertThat(AppRuntime.aiService(without, "key", "test").visionModel()).isNull();
    }
}
