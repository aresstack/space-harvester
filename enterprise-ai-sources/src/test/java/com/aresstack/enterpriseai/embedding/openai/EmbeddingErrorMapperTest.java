package com.aresstack.enterpriseai.embedding.openai;

import com.aresstack.enterpriseai.embedding.api.EmbeddingException;
import com.aresstack.enterpriseai.embedding.api.EmbeddingFailureKind;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class EmbeddingErrorMapperTest {

    @Test
    public void mapsStatusCodes() {
        assertEquals(EmbeddingFailureKind.AUTHENTICATION, EmbeddingErrorMapper.kindOf(401));
        assertEquals(EmbeddingFailureKind.AUTHENTICATION, EmbeddingErrorMapper.kindOf(403));
        assertEquals(EmbeddingFailureKind.RATE_LIMITED, EmbeddingErrorMapper.kindOf(429));
        assertEquals(EmbeddingFailureKind.UNAVAILABLE, EmbeddingErrorMapper.kindOf(408));
        assertEquals(EmbeddingFailureKind.REJECTED, EmbeddingErrorMapper.kindOf(400));
        assertEquals(EmbeddingFailureKind.REJECTED, EmbeddingErrorMapper.kindOf(404));
        assertEquals(EmbeddingFailureKind.REJECTED, EmbeddingErrorMapper.kindOf(422));
        assertEquals(EmbeddingFailureKind.PROVIDER_ERROR, EmbeddingErrorMapper.kindOf(500));
        assertEquals(EmbeddingFailureKind.PROVIDER_ERROR, EmbeddingErrorMapper.kindOf(503));
        assertEquals(EmbeddingFailureKind.INVALID_RESPONSE, EmbeddingErrorMapper.kindOf(302));
        assertEquals(EmbeddingFailureKind.INVALID_RESPONSE, EmbeddingErrorMapper.kindOf(204));
    }

    @Test
    public void messageCarriesStatusAndErrorCodeOnly() {
        EmbeddingException ex = EmbeddingErrorMapper.fromStatus("https://ai/v1/embeddings", 500,
                "{\"error\":{\"message\":\"failed on: geheimer Vertragstext\",\"code\":\"internal_error\"}}");
        assertEquals(EmbeddingFailureKind.PROVIDER_ERROR, ex.kind());
        assertEquals("embedding endpoint https://ai/v1/embeddings returned HTTP 500 (internal_error)", ex.getMessage());
    }

    @Test
    public void echoedInputInValidationErrorsIsNeverCopied() {
        String body = "{\"detail\":[{\"type\":\"string_type\",\"msg\":\"Input should be a valid string\","
                + "\"input\":[\"geheimer Vertragstext\"]}]}";
        EmbeddingException ex = EmbeddingErrorMapper.fromStatus("e", 422, body);
        assertEquals("embedding endpoint e returned HTTP 422", ex.getMessage());
        assertFalse(EmbeddingErrorMapper.fromStatus("e", 400, "geheimer Vertragstext").getMessage().contains("geheim"));
        assertFalse(EmbeddingErrorMapper.fromStatus("e", 400, "{\"error\":\"geheimer Vertragstext\"}")
                .getMessage().contains("geheim"));
    }
}
