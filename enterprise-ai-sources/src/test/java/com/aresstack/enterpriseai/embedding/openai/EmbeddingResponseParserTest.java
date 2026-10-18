package com.aresstack.enterpriseai.embedding.openai;

import com.aresstack.enterpriseai.embedding.api.EmbeddingException;
import com.aresstack.enterpriseai.embedding.api.EmbeddingFailureKind;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class EmbeddingResponseParserTest {

    @Test
    public void singleEntryResponse() {
        String json = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,"
                + "\"embedding\":[0.25,-1.5,3e-2]}],\"model\":\"m\","
                + "\"usage\":{\"prompt_tokens\":0,\"total_tokens\":0}}";
        List<float[]> vectors = EmbeddingResponseParser.parse(json, 1);
        assertEquals(1, vectors.size());
        assertArrayEquals(new float[] {0.25f, -1.5f, 0.03f}, vectors.get(0), 0f);
    }

    @Test
    public void singleEntryWithoutIndex() {
        List<float[]> vectors = EmbeddingResponseParser.parse("{\"data\":[{\"embedding\":[1,2]}]}", 1);
        assertArrayEquals(new float[] {1f, 2f}, vectors.get(0), 0f);
    }

    @Test
    public void multipleEntriesAreOrderedByIndex() {
        String json = "{\"data\":["
                + "{\"index\":2,\"embedding\":[3,3]},"
                + "{\"index\":0,\"embedding\":[1,1]},"
                + "{\"index\":1,\"embedding\":[2,2]}]}";
        List<float[]> vectors = EmbeddingResponseParser.parse(json, 3);
        assertArrayEquals(new float[] {1f, 1f}, vectors.get(0), 0f);
        assertArrayEquals(new float[] {2f, 2f}, vectors.get(1), 0f);
        assertArrayEquals(new float[] {3f, 3f}, vectors.get(2), 0f);
    }

    @Test
    public void multipleEntriesWithoutIndexKeepListOrder() {
        String json = "{\"data\":[{\"embedding\":[1]},{\"embedding\":[2]}]}";
        List<float[]> vectors = EmbeddingResponseParser.parse(json, 2);
        assertEquals(1f, vectors.get(0)[0], 0f);
        assertEquals(2f, vectors.get(1)[0], 0f);
    }

    @Test
    public void rejectsCountMismatch() {
        assertInvalid("{\"data\":[{\"index\":0,\"embedding\":[1]}]}", 2);
        assertInvalid("{\"data\":[]}", 1);
        assertInvalid("{\"data\":[{\"embedding\":[1]},{\"embedding\":[2]}]}", 1);
    }

    @Test
    public void rejectsBrokenIndices() {
        assertInvalid("{\"data\":[{\"index\":0,\"embedding\":[1]},{\"index\":0,\"embedding\":[2]}]}", 2);
        assertInvalid("{\"data\":[{\"index\":0,\"embedding\":[1]},{\"index\":5,\"embedding\":[2]}]}", 2);
        assertInvalid("{\"data\":[{\"index\":-1,\"embedding\":[1]}]}", 1);
        assertInvalid("{\"data\":[{\"index\":0.5,\"embedding\":[1]}]}", 1);
        assertInvalid("{\"data\":[{\"index\":\"0\",\"embedding\":[1]}]}", 1);
        assertInvalid("{\"data\":[{\"index\":0,\"embedding\":[1]},{\"embedding\":[2]}]}", 2);
    }

    @Test
    public void rejectsBase64AndNonNumericEmbeddings() {
        assertInvalid("{\"data\":[{\"index\":0,\"embedding\":\"AACAPwAAAEA=\"}]}", 1);
        assertInvalid("{\"data\":[{\"index\":0,\"embedding\":[1,\"x\"]}]}", 1);
        assertInvalid("{\"data\":[{\"index\":0,\"embedding\":[1,null]}]}", 1);
        assertInvalid("{\"data\":[{\"index\":0}]}", 1);
        assertInvalid("{\"data\":[{\"index\":0,\"embedding\":null}]}", 1);
    }

    @Test
    public void rejectsMalformedDocuments() {
        assertInvalid("", 1);
        assertInvalid("not json", 1);
        assertInvalid("[1,2]", 1);
        assertInvalid("{\"object\":\"list\"}", 1);
        assertInvalid("{\"data\":{}}", 1);
        assertInvalid("{\"data\":[1]}", 1);
        assertInvalid("{\"data\":[{\"embedding\":[1,2]", 1);
    }

    @Test
    public void nonFiniteNumbersSurviveParsingForLaterRejection() {
        // Dimension und Endlichkeit prüft EmbeddingVector im Adapter; hier nur: kein stilles Verwerfen.
        List<float[]> vectors = EmbeddingResponseParser.parse("{\"data\":[{\"embedding\":[1e39, 0]}]}", 1);
        assertEquals(Float.POSITIVE_INFINITY, vectors.get(0)[0], 0f);
    }

    @Test
    public void extractsOnlyMachineReadableErrorCodes() {
        assertEquals("model_not_found", EmbeddingResponseParser.errorCode(
                "{\"error\":{\"message\":\"model x not found\",\"code\":\"model_not_found\"}}"));
        assertEquals("invalid_request_error", EmbeddingResponseParser.errorCode(
                "{\"error\":{\"message\":\"bad\",\"type\":\"invalid_request_error\"}}"));
        assertEquals("internal_error", EmbeddingResponseParser.errorCode("{\"error\":\"internal_error\"}"));
        assertNull(EmbeddingResponseParser.errorCode("{\"error\":\"free text with spaces\"}"));
        assertNull(EmbeddingResponseParser.errorCode("{\"detail\":\"Input should be a valid string\"}"));
        assertNull(EmbeddingResponseParser.errorCode("<html>Bad Gateway</html>"));
        assertNull(EmbeddingResponseParser.errorCode(""));
    }

    private static void assertInvalid(String json, int expectedCount) {
        try {
            EmbeddingResponseParser.parse(json, expectedCount);
            fail("expected INVALID_RESPONSE for " + json);
        } catch (EmbeddingException expected) {
            assertEquals(json, EmbeddingFailureKind.INVALID_RESPONSE, expected.kind());
        }
    }
}
