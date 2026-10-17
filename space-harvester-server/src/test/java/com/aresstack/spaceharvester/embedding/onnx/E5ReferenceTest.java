package com.aresstack.spaceharvester.embedding.onnx;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Der ONNX-Provider gegen die Referenzvektoren von {@code intfloat/multilingual-e5-small} aus der
 * PyTorch-Implementierung der Modellkarte ({@code tools/embedding/e5_reference.py},
 * {@code src/test/resources/embedding/multilingual-e5-small/reference.json}). Läuft nur, wenn
 * {@code SPACE_HARVESTER_E5_DIR} auf die gepinnte Revision zeigt (Workflow {@code embedding-model.yml}, oder lokal);
 * die Modelldateien liegen nie im Repository.
 */
@EnabledIfEnvironmentVariable(named = "SPACE_HARVESTER_E5_DIR", matches = ".+")
class E5ReferenceTest {

    private static final String QUERY = "query: ";
    private static final String PASSAGE = "passage: ";

    private static OnnxEmbeddingProvider provider() {
        Path dir = Path.of(System.getenv("SPACE_HARVESTER_E5_DIR"));
        return new OnnxEmbeddingProvider(new OnnxEmbeddingSettings(dir.resolve(E5Defaults.MODEL_FILE),
                dir.resolve(E5Defaults.TOKENIZER_FILE), E5Defaults.MODEL_SHA256, E5Defaults.TOKENIZER_SHA256,
                E5Defaults.MODEL_ID, E5Defaults.DIMENSIONS, E5Defaults.MAX_TOKENS, 4, 2, 1, Duration.ofMinutes(1),
                Duration.ofMinutes(5), PASSAGE, QUERY, E5Defaults.PAD_TOKEN_ID));
    }

    private static JsonNode reference() throws IOException {
        try (InputStream in = E5ReferenceTest.class.getResourceAsStream(
                "/embedding/multilingual-e5-small/reference.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    @Test
    void vectorsAndTokensMatchThePyTorchReference() throws IOException {
        JsonNode cases = reference().get("cases");
        assertThat(cases.size()).isGreaterThanOrEqualTo(6);
        try (OnnxEmbeddingProvider provider = provider()) {
            for (JsonNode reference : cases) {
                String text = reference.get("text").asString();
                boolean query = text.startsWith(QUERY);
                String body = text.substring(query ? QUERY.length() : PASSAGE.length());
                float[] vector = (query ? provider.embedQueries(List.of(body)) : provider.embed(List.of(body))).get(0);
                float[] expected = floats(reference.get("vector"));
                assertThat(vector).as(text).hasSize(expected.length);
                double cosine = 0;
                double maxDiff = 0;
                for (int i = 0; i < expected.length; i++) {
                    cosine += vector[i] * expected[i];
                    maxDiff = Math.max(maxDiff, Math.abs(vector[i] - expected[i]));
                }
                assertThat(cosine).as("Kosinus " + text).isGreaterThan(0.9999);
                assertThat(maxDiff).as("größte Abweichung " + text).isLessThan(2e-3);
                if (!query) {
                    long[] ids = provider.passageTokenIds(body);
                    assertThat(ids).as("Token " + text).containsExactly(longs(reference.get("inputIds")));
                }
            }
        }
    }

    @Test
    void aGermanQueryIsCloserToTheMatchingPassageInBothLanguages() {
        try (OnnxEmbeddingProvider provider = provider()) {
            float[] query = provider.embedQueries(List.of("Wie läuft der nächtliche Betrieb?")).get(0);
            List<float[]> passages = provider.embed(List.of(
                    "Der nächtliche Betrieb startet um 2 Uhr mit dem Abgleich aller Spaces.",
                    "The nightly job starts at 2 a.m. and synchronises every space.",
                    "Die Kantine bietet montags vegetarische Gerichte an."));
            assertThat(dot(query, passages.get(0))).isGreaterThan(dot(query, passages.get(2)));
            assertThat(dot(query, passages.get(1))).isGreaterThan(dot(query, passages.get(2)));
        }
    }

    private static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    private static float[] floats(JsonNode array) {
        float[] values = new float[array.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = (float) array.get(i).asDouble();
        }
        return values;
    }

    private static long[] longs(JsonNode array) {
        long[] values = new long[array.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = array.get(i).asLong();
        }
        return values;
    }
}
