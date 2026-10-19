package com.aresstack.spaceharvester.application.passages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Stabile Passage-IDs wie {@code KnowledgePassageId} im Enterprise AI Client (EAC-Lücke 5). */
class PassageIdTest {

    static final String CAPTURE = "3fa9c2d1".repeat(8);

    @Test
    void hasTheFormOfTheClientAndReadsBack() {
        PassageId id = PassageId.of("confluence", "47110815", CAPTURE, 3, "9f2c41ab", "5e07d1c3");

        assertThat(id.value()).isEqualTo("confluence:47110815@" + CAPTURE + "#p3@9f2c41ab-5e07d1c3");
        assertThat(PassageId.parse(id.value())).isEqualTo(id).satisfies(read -> {
            assertThat(read.sourceId()).isEqualTo("confluence");
            assertThat(read.pageId()).isEqualTo("47110815");
            assertThat(read.capture()).isEqualTo(CAPTURE);
            assertThat(read.position()).isEqualTo(3);
            assertThat(read.segmentation()).isEqualTo("9f2c41ab");
            assertThat(read.embedding()).isEqualTo("5e07d1c3");
        });
    }

    @Test
    void pageIdsMayContainTheSeparators() {
        String pageId = "docs/a@b#p1:c.md";
        PassageId id = PassageId.of("files", pageId, CAPTURE, 0, "aa", "bb");

        assertThat(PassageId.parse(id.value()).pageId()).isEqualTo(pageId);
        assertThat(PassageId.parse(id.value()).sourceId()).isEqualTo("files");
    }

    @Test
    void anythingElseIsNoPassageIdAndTheMessageDoesNotNameIt() {
        assertThat(PassageId.looksLike(null)).isFalse();
        assertThat(PassageId.looksLike("confluence:47110815")).isFalse();
        assertThat(PassageId.looksLike(":4711@" + CAPTURE + "#p0@aa-bb")).isFalse();
        assertThat(PassageId.looksLike("wiki:@" + CAPTURE + "#p0@aa-bb")).isFalse();
        assertThat(PassageId.looksLike("wiki:4711@" + CAPTURE.substring(1) + "#p0@aa-bb")).isFalse();
        assertThat(PassageId.looksLike("wiki:4711@" + CAPTURE + "#p-1@aa-bb")).isFalse();
        assertThat(PassageId.looksLike("wiki:4711@" + CAPTURE + "#p0@AA-bb")).isFalse();
        assertThat(PassageId.looksLike("wiki:4711@" + CAPTURE + "#p0@aa")).isFalse();
        assertThatThrownBy(() -> PassageId.parse("wiki:geheim@kaputt#p0@aa-bb"))
                .isInstanceOf(IllegalArgumentException.class).message().doesNotContain("geheim");
    }

    @Test
    void captureIsTheWholeTextHashAndTagsAreEightHex() {
        String hash = PassagePayload.textSha256("Text");

        assertThat(PassageId.captureOf(hash)).hasSize(PassageId.CAPTURE_LENGTH).isEqualTo(hash.substring(7));
        assertThat(PassageId.captureOf(hash.substring(7).toUpperCase())).isEqualTo(hash.substring(7));
        assertThatThrownBy(() -> PassageId.captureOf(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(PassageId.versionTag("chunker;x")).matches("[0-9a-f]{8}")
                .isEqualTo(PassageId.versionTag("chunker;x")).isNotEqualTo(PassageId.versionTag("chunker;y"));
    }
}
