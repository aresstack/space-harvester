package com.aresstack.spaceharvester.application.enrichment.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EmbeddingPayloadTest {

    @Test
    void readsTheProcessorFormat() {
        EmbeddingPayload payload = EmbeddingPayload.parse("""
                {"model":"intfloat/multilingual-e5-small@614241f622f5","dimensions":3,"chunks":[
                 {"charStart":0,"charEnd":12,"section":"Betrieb \\"Nacht\\" \\u00e4","vector":[0.5,-1.0E-5,1]},
                 {"charStart":12,"charEnd":30,"section":null,"vector":[0,0,1.25]}]}""");

        assertThat(payload.model()).isEqualTo("intfloat/multilingual-e5-small@614241f622f5");
        assertThat(payload.dimensions()).isEqualTo(3);
        assertThat(payload.chunks()).hasSize(2);
        assertThat(payload.chunks().get(0).vector()).containsExactly(0.5f, -1.0E-5f, 1f);
        assertThat(payload.chunks().get(1).charStart()).isEqualTo(12);
        assertThat(payload.chunks().get(1).charEnd()).isEqualTo(30);
        assertThat(payload.chunks()).extracting(EmbeddingPayload.Chunk::section)
                .containsExactly("Betrieb \"Nacht\" ä", null);
    }

    @Test
    void anEmptyPageHasNoChunks() {
        assertThat(EmbeddingPayload.parse("{\"model\":\"m\",\"dimensions\":384,\"chunks\":[]}").chunks()).isEmpty();
    }

    @Test
    void rejectsWhatTheProcessorNeverWrites() {
        for (String json : new String[] {"", "[]", "{\"model\":\"m\",\"dimensions\":2}",
                "{\"model\":\"m\",\"dimensions\":2,\"chunks\":[{\"charStart\":0,\"charEnd\":1,\"vector\":[1]}]}",
                "{\"model\":\"m\",\"dimensions\":1.5,\"chunks\":[]}",
                "{\"model\":\"m\",\"dimensions\":1,\"chunks\":[{\"charStart\":-1,\"charEnd\":1,\"vector\":[1]}]}",
                "{\"model\":\"m\",\"dimensions\":1,\"chunks\":[{\"charStart\":0,\"charEnd\":1,\"vector\":[1e999]}]}",
                "{\"model\":\"m\",\"dimensions\":1,\"chunks\":[]} x", "{\"model\":\"m\""}) {
            assertThatThrownBy(() -> EmbeddingPayload.parse(json)).as(json)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
