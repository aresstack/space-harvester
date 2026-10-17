package com.aresstack.spaceharvester.embedding.onnx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingUnavailableException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link OnnxEmbeddingProvider} mit einem Spielzeugmodell (src/test/resources/embedding/tiny, erzeugt mit
 * {@code generate.py}): Präfixe, Mean-Pooling über die Maske, Normierung, Stapel, Abschneiden und das Verhalten bei
 * fehlenden oder falschen Dateien. Den Vergleich mit dem echten Modell macht {@code E5ReferenceTest}.
 */
class OnnxEmbeddingProviderTest {

    private static final String MODEL_SHA = "d1034da8d30a310414c1fed0c2d2c439321e106b9e83ec79240831b9e645e494";
    private static final String TOKENIZER_SHA = "4b004d9910b9a19f6f0e6cfb96f004d6d97bf9ca2d320967cefa2c66edfca061";

    /** Zeile k ist der Vektor der Token-ID k, wie in generate.py. */
    private static final float[][] TABLE = {
            {-2f, -0.5f, 1f, 2.5f}, {-1f, 1.5f, 4f, -0.5f}, {0f, 3.5f, 0f, 3.5f}, {1f, -1.5f, 3f, 0.5f},
            {2f, 0.5f, -1f, 4.5f}, {3f, 2.5f, 2f, 1.5f}, {-3f, -2.5f, -2f, -1.5f}, {-2f, -0.5f, 1f, 2.5f},
            {-1f, 1.5f, 4f, -0.5f}};
    private static final int S = 0;
    private static final int END = 2;
    private static final int QUERY = 4;
    private static final int PASSAGE = 5;
    private static final int ALPHA = 6;
    private static final int BETA = 7;
    private static final int GAMMA = 8;

    @TempDir
    Path temp;

    private static Path resource(String name) {
        try {
            return Path.of(OnnxEmbeddingProviderTest.class.getResource("/embedding/tiny/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static OnnxEmbeddingSettings settings(Path model, String modelSha, int dimensions, int maxTokens,
                                                  int batchSize) {
        return new OnnxEmbeddingSettings(model, resource("tokenizer.json"), modelSha, TOKENIZER_SHA, "tiny@1",
                dimensions, maxTokens, batchSize, 1, 1, Duration.ofSeconds(5), Duration.ofMinutes(5), "passage: ",
                "query: ", 1);
    }

    private static OnnxEmbeddingProvider provider(int maxTokens, int batchSize) {
        return new OnnxEmbeddingProvider(settings(resource("model.onnx"), MODEL_SHA, 4, maxTokens, batchSize));
    }

    private static float[] expected(int... tokens) {
        float[] mean = new float[4];
        for (int token : tokens) {
            for (int d = 0; d < 4; d++) {
                mean[d] += TABLE[token][d] / tokens.length;
            }
        }
        double norm = 0;
        for (float v : mean) {
            norm += v * v;
        }
        for (int d = 0; d < 4; d++) {
            mean[d] = (float) (mean[d] / Math.sqrt(norm));
        }
        return mean;
    }

    private static void assertVector(float[] actual, float[] expected) {
        assertThat(actual).hasSize(expected.length);
        for (int d = 0; d < expected.length; d++) {
            assertThat(actual[d]).isCloseTo(expected[d], within(1e-6f));
        }
    }

    @Test
    void documentsAndQueriesGetTheirPrefixAndAreMeanPooledAndNormalized() {
        try (OnnxEmbeddingProvider provider = provider(512, 16)) {
            List<float[]> passages = provider.embed(List.of("alpha", "beta gamma"));
            assertVector(passages.get(0), expected(S, PASSAGE, ALPHA, END));
            assertVector(passages.get(1), expected(S, PASSAGE, BETA, GAMMA, END));
            assertVector(provider.embedQueries(List.of("alpha")).get(0), expected(S, QUERY, ALPHA, END));
            double norm = 0;
            for (float v : passages.get(1)) {
                norm += v * v;
            }
            assertThat(norm).isCloseTo(1.0, within(1e-6));
        }
    }

    @Test
    void paddingAndBatchSizeDoNotChangeTheVectors() {
        List<String> texts = List.of("alpha", "alpha beta gamma beta gamma", "gamma", "beta alpha");
        try (OnnxEmbeddingProvider one = provider(512, 1); OnnxEmbeddingProvider all = provider(512, 16);
             OnnxEmbeddingProvider odd = provider(512, 3)) {
            List<float[]> single = one.embed(texts);
            List<float[]> together = all.embed(texts);
            List<float[]> split = odd.embed(texts);
            assertThat(together).hasSize(4);
            for (int i = 0; i < texts.size(); i++) {
                assertVector(together.get(i), single.get(i));
                assertVector(split.get(i), single.get(i));
            }
            assertVector(together.get(2), expected(S, PASSAGE, GAMMA, END));
        }
    }

    @Test
    void longTextsAreCutAtMaxTokensIncludingTheSpecialTokens() {
        try (OnnxEmbeddingProvider provider = provider(5, 16)) {
            float[] vector = provider.embed(List.of("alpha beta gamma alpha beta gamma")).get(0);
            // <s> passage: alpha beta </s>: der Tokenizer kürzt und behält das Endtoken
            assertVector(vector, expected(S, PASSAGE, ALPHA, BETA, END));
        }
    }

    @Test
    void anEmptyListNeedsNoModel() {
        OnnxEmbeddingProvider provider = new OnnxEmbeddingProvider(settings(temp.resolve("fehlt.onnx"), MODEL_SHA,
                4, 512, 16));
        assertThat(provider.embed(List.of())).isEmpty();
        assertThat(provider.modelId()).isEqualTo("tiny@1");
        assertThat(provider.dimensions()).isEqualTo(4);
    }

    @Test
    void aMissingModelIsUnavailableAndRetriedOnlyAfterTheWaitingTime() throws IOException {
        Path model = temp.resolve("model.onnx");
        MutableClock clock = new MutableClock();
        OnnxEmbeddingProvider provider = new OnnxEmbeddingProvider(settings(model, MODEL_SHA, 4, 512, 16), clock);

        assertThatThrownBy(() -> provider.embed(List.of("geheimer Text")))
                .isInstanceOf(EmbeddingUnavailableException.class)
                .hasMessageContaining("Modelldatei fehlt")
                .hasMessageNotContaining("geheimer");

        Files.copy(resource("model.onnx"), model);
        clock.advance(Duration.ofMinutes(1));
        assertThatThrownBy(() -> provider.embed(List.of("alpha"))).isInstanceOf(EmbeddingUnavailableException.class);
        clock.advance(Duration.ofMinutes(5));
        assertVector(provider.embed(List.of("alpha")).get(0), expected(S, PASSAGE, ALPHA, END));
        provider.close();
        assertThatThrownBy(() -> provider.embed(List.of("alpha"))).isInstanceOf(EmbeddingUnavailableException.class);
    }

    @Test
    void aFileWithAnotherChecksumIsNotLoaded() {
        String other = "0".repeat(64);
        OnnxEmbeddingProvider provider = new OnnxEmbeddingProvider(settings(resource("model.onnx"), other, 4, 512, 16));
        assertThatThrownBy(() -> provider.embed(List.of("alpha")))
                .isInstanceOf(EmbeddingUnavailableException.class)
                .hasMessageContaining("SHA-256 " + MODEL_SHA + " statt " + other);
    }

    @Test
    void aModelWithAnotherDimensionIsRefused() {
        OnnxEmbeddingProvider provider = new OnnxEmbeddingProvider(settings(resource("model.onnx"), MODEL_SHA, 384,
                512, 16));
        assertThatThrownBy(() -> provider.embed(List.of("alpha")))
                .isInstanceOf(EmbeddingUnavailableException.class)
                .hasMessageContaining("4 statt 384 Dimensionen");
    }

    @Test
    void settingsRejectInvalidValues() {
        assertThatThrownBy(() -> settings(resource("model.onnx"), "abc", 4, 512, 16))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("modelSha256");
        assertThatThrownBy(() -> settings(resource("model.onnx"), MODEL_SHA, 4, 512, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("batchSize");
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-17T09:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
