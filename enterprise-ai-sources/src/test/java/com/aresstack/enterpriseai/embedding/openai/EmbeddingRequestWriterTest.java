package com.aresstack.enterpriseai.embedding.openai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EmbeddingRequestWriterTest {

    @Test
    public void singleInputSendsOnlyModelAndStringInput() {
        JsonObject body = JsonParser.parseString(
                EmbeddingRequestWriter.singleInput("danielheinz/e5-base-sts-en-de", "Erkläre REST.")).getAsJsonObject();

        assertEquals(2, body.size());
        assertEquals("danielheinz/e5-base-sts-en-de", body.get("model").getAsString());
        assertTrue(body.get("input").isJsonPrimitive());
        assertEquals("Erkläre REST.", body.get("input").getAsString());
    }

    @Test
    public void unverifiedFieldsAreNotSent() {
        String json = EmbeddingRequestWriter.singleInput("m", "x");
        assertFalse(json.contains("encoding_format"));
        assertFalse(json.contains("dimensions"));
        assertFalse(json.contains("\"user\""));
    }

    @Test
    public void arrayInputKeepsOrder() {
        JsonObject body = JsonParser.parseString(
                EmbeddingRequestWriter.arrayInput("m", Arrays.asList("b", "a", "c"))).getAsJsonObject();
        assertEquals(2, body.size());
        assertEquals("[\"b\",\"a\",\"c\"]", body.get("input").toString());
    }

    @Test
    public void escapesControlCharactersQuotesAndKeepsUnicode() {
        String text = "Zeile 1\nZeile \"2\"\t\\ äöüß € 😀";
        String json = EmbeddingRequestWriter.singleInput("m", text);
        assertFalse(json.contains("\n"));
        assertEquals(text, JsonParser.parseString(json).getAsJsonObject().get("input").getAsString());
    }
}
