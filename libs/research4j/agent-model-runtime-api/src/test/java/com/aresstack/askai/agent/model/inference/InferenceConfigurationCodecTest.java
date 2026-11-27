package com.aresstack.askai.agent.model.inference;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The inference descriptor codec: exact wire shape, round-trip, and STRICT rejection of bad input. */
public class InferenceConfigurationCodecTest {

    @Test
    public void encodesTheFlatWireShape() {
        String json = InferenceConfigurationCodec.toJson(InferenceConfigurationDocument.current(3L,
                new InferenceEndpointDescriptor("local/qwen:latest", "http://127.0.0.1:51000",
                        "/api/chat", 120000L)));
        assertTrue(json, json.contains("\"formatVersion\":1"));
        assertTrue(json, json.contains("\"configurationRevision\":3"));
        assertTrue(json, json.contains("\"model\":\"local/qwen:latest\""));
        assertTrue(json, json.contains("\"baseUrl\":\"http://127.0.0.1:51000\""));
        assertTrue(json, json.contains("\"chatPath\":\"/api/chat\""));
        assertTrue(json, json.contains("\"timeoutMillis\":120000"));
    }

    @Test
    public void roundTripsAValidDocument() {
        InferenceConfigurationDocument original = InferenceConfigurationDocument.current(7L,
                new InferenceEndpointDescriptor("qwen2.5:latest", "http://remote:11434", "/api/chat", 90000L));
        InferenceConfigurationValidationResult result =
                InferenceConfigurationCodec.parse(InferenceConfigurationCodec.toJson(original));
        assertTrue(result.describe(), result.valid);
        assertEquals(7L, result.document.configurationRevision);
        assertEquals("qwen2.5:latest", result.document.descriptor.model);
        assertEquals("http://remote:11434", result.document.descriptor.baseUrl);
        assertEquals("/api/chat", result.document.descriptor.chatPath);
        assertEquals(90000L, result.document.descriptor.timeoutMillis);
    }

    @Test
    public void rejectsMalformedJson() {
        assertFalse(InferenceConfigurationCodec.parse("{not json").valid);
    }

    @Test
    public void rejectsAMissingModel() {
        InferenceConfigurationValidationResult r = InferenceConfigurationCodec.parse(
                "{\"formatVersion\":1,\"configurationRevision\":1,\"baseUrl\":\"http://x\","
                        + "\"chatPath\":\"/api/chat\",\"timeoutMillis\":1000}");
        assertFalse(r.valid);
        assertTrue(r.describe(), r.describe().contains("model"));
    }

    @Test
    public void rejectsANonHttpBaseUrlAndNonPositiveTimeout() {
        InferenceConfigurationValidationResult r = InferenceConfigurationCodec.parse(
                "{\"formatVersion\":1,\"configurationRevision\":1,\"model\":\"m\",\"baseUrl\":\"ftp://x\","
                        + "\"chatPath\":\"/api/chat\",\"timeoutMillis\":0}");
        assertFalse(r.valid);
        assertTrue(r.describe(), r.describe().contains("baseUrl"));
        assertTrue(r.describe(), r.describe().contains("timeoutMillis"));
    }
}
