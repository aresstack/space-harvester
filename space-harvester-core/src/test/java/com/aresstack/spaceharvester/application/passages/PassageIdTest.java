package com.aresstack.spaceharvester.application.passages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Instant;
import java.util.List;
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

    @Test
    void onlyTheCanonicalPositionIsAPassageId() {
        String id = PassageId.of("wiki", "1", "a".repeat(64), 7, "aa", "bb").value();

        assertThat(PassageId.parse(id).value()).isEqualTo(id);
        assertThat(PassageId.looksLike(id.replace("#p7@", "#p007@"))).isFalse();
        assertThat(PassageId.looksLike(id.replace("#p7@", "#p0@"))).isTrue();
    }

    /** Fester Text und festes Ergebnis, ID Zeichen für Zeichen: Hash, Fingerabdrücke und Form ändern sich nie still. */
    @Test
    void aStoredResultAlwaysGivesTheSameLiteralIds() {
        PageText text = new PageText("confluence-storage-text/v1", "Erster Absatz.\nZweiter Absatz.", List.of());
        String payload = "{\"mode\":\"semantic\",\"reason\":null,\"model\":\"topics@1\","
                + "\"segmentation\":\"passages;eac;3;0.35;2;8;350\",\"sentences\":2,\"textSha256\":null,"
                + "\"passages\":[{\"start\":0,\"end\":14,\"firstSentence\":0,\"sentences\":1,\"section\":null,"
                + "\"kind\":\"paragraph\",\"boundary\":\"structure\",\"similarity\":null},{\"start\":15,"
                + "\"end\":30,\"firstSentence\":1,\"sentences\":1,\"section\":null,\"kind\":\"paragraph\","
                + "\"boundary\":\"structure\",\"similarity\":null}]}";
        PageKey key = new PageKey("confluence", "47110815");
        Enrichment stored = new Enrichment(key, PassageProcessor.ID, "v", text.inputSha256(), "1",
                Enrichment.Status.OK, payload, List.of(), null, Instant.EPOCH);

        List<String> ids = PagePassages.read(key, List.of(new Enrichments.View(stored, Enrichment.Freshness.CURRENT)),
                text).passages().stream().map(PagePassages.Passage::id).toList();

        // Mit sha256sum nachgerechnet: Eingabe "confluence-storage-text/v1\n\n" + Text (inputSha256, ohne
        // Abschnitte), "passages;eac;3;0.35;2;8;350" → 1a796cb0, "topics@1" → 2c747974.
        assertThat(ids).containsExactly(
                "confluence:47110815@" + "aa2b0cb3ca6bb8ad0e309c43640cf47aa475e4b4f0f4391fe1023f97cb977436"
                        + "#p0@1a796cb0-2c747974",
                "confluence:47110815@" + "aa2b0cb3ca6bb8ad0e309c43640cf47aa475e4b4f0f4391fe1023f97cb977436"
                        + "#p1@1a796cb0-2c747974");
    }
}
