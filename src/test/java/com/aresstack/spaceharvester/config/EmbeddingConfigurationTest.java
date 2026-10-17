package com.aresstack.spaceharvester.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Ohne {@code space-harvester.embedding.provider=onnx} kein Provider; mit ihm ein Provider, der einbettet (#89). */
class EmbeddingConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EmbeddingConfiguration.class);

    private static String tiny() {
        try {
            return Path.of(EmbeddingConfigurationTest.class.getResource("/embedding/tiny").toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void offByDefault() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(EmbeddingProvider.class);
            assertThat(context).doesNotHaveBean(EmbeddingProcessor.class);
        });
        runner.withPropertyValues("space-harvester.embedding.provider=none")
                .run(context -> assertThat(context).doesNotHaveBean(EmbeddingProvider.class));
    }

    @Test
    void onnxWithConfiguredFilesEmbeds() {
        runner.withPropertyValues("space-harvester.embedding.provider=onnx",
                        "space-harvester.embedding.max-chunk-chars=800",
                        "space-harvester.embedding.onnx.model-file=" + tiny() + "/model.onnx",
                        "space-harvester.embedding.onnx.tokenizer-file=" + tiny() + "/tokenizer.json",
                        "space-harvester.embedding.onnx.model-sha256="
                                + "d1034da8d30a310414c1fed0c2d2c439321e106b9e83ec79240831b9e645e494",
                        "space-harvester.embedding.onnx.tokenizer-sha256="
                                + "4b004d9910b9a19f6f0e6cfb96f004d6d97bf9ca2d320967cefa2c66edfca061",
                        "space-harvester.embedding.onnx.model-id=tiny@1",
                        "space-harvester.embedding.onnx.dimensions=4")
                .run(context -> {
                    EmbeddingProvider provider = context.getBean(EmbeddingProvider.class);
                    assertThat(provider.modelId()).isEqualTo("tiny@1");
                    assertThat(provider.embed(List.of("alpha")).get(0)).hasSize(4);
                    assertThat(context.getBean(EmbeddingProcessor.class).version()).contains("tiny@1+4+800");
                });
    }

    @Test
    void missingModelFilesDoNotStopTheStart() {
        runner.withPropertyValues("space-harvester.embedding.provider=onnx",
                        "space-harvester.embedding.onnx.model-dir=/nicht/vorhanden")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EmbeddingProvider.class).modelId())
                            .startsWith("intfloat/multilingual-e5-small@");
                });
    }
}
