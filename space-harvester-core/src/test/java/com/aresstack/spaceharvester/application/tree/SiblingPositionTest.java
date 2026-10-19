package com.aresstack.spaceharvester.application.tree;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SiblingPositionTest {

    private static NativePayload json(String text) {
        return new NativePayload(text.getBytes(StandardCharsets.UTF_8), "application/json", "confluence");
    }

    @Test
    void readsThePositionOfAManuallyOrderedPage() {
        assertThat(SiblingPosition.of(json("{\"id\":\"1\",\"position\":3}"))).isEqualTo(3L);
        assertThat(SiblingPosition.of(json("{\"id\":\"1\",\"extensions\":{\"position\":7}}"))).isEqualTo(7L);
        assertThat(SiblingPosition.of(json("{\"id\":\"1\",\"extensions\":{\"position\":\"12\"}}"))).isEqualTo(12L);
    }

    @Test
    void withoutAPositionThePageIsOrderedByTitle() {
        assertThat(SiblingPosition.of(json("{\"id\":\"1\",\"extensions\":{\"position\":\"none\"}}"))).isNull();
        assertThat(SiblingPosition.of(json("{\"id\":\"1\"}"))).isNull();
        assertThat(SiblingPosition.of(json("kein json"))).isNull();
        assertThat(SiblingPosition.of(new NativePayload("Text".getBytes(StandardCharsets.UTF_8), "text/plain",
                "knowledge-document"))).isNull();
    }
}
