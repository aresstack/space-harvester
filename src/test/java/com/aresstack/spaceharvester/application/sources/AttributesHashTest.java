package com.aresstack.spaceharvester.application.sources;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Vertrag von {@link AttributesHash}: getrennt von {@link MetadataHash}, deterministisch nach Schlüsseln sortiert. */
class AttributesHashTest {

    @Test
    void labelChangeChangesOnlyTheAttributesHash() {
        PageMetadata before = page(Map.of("confluence.labels", "api,architektur"));
        PageMetadata after = page(Map.of("confluence.labels", "api,architektur,veraltet"));

        assertThat(AttributesHash.of("wiki", after)).isNotEqualTo(AttributesHash.of("wiki", before));
        assertThat(MetadataHash.of("wiki", after)).isEqualTo(MetadataHash.of("wiki", before));
    }

    @Test
    void attributeOrderDoesNotMatterAndTheCanonicalFormIsFixed() {
        Map<String, String> forward = new LinkedHashMap<>();
        forward.put("confluence.type", "page");
        forward.put("confluence.labels", "api");
        Map<String, String> backward = new LinkedHashMap<>();
        backward.put("confluence.labels", "api");
        backward.put("confluence.type", "page");

        assertThat(AttributesHash.of("wiki", page(forward))).isEqualTo(AttributesHash.of("wiki", page(backward)))
                .isEqualTo("sha256:" + sha256("{\"schema\":\"page-attributes/v1\",\"sourceId\":\"wiki\","
                        + "\"pageId\":\"101\",\"attributes\":{\"confluence.labels\":\"api\","
                        + "\"confluence.type\":\"page\"}}"));
    }

    @Test
    void missingAttributeDiffersFromEmptyOne() {
        assertThat(AttributesHash.of("wiki", page(Map.of())))
                .isNotEqualTo(AttributesHash.of("wiki", page(Map.of("confluence.labels", ""))))
                .isEqualTo("sha256:" + sha256("{\"schema\":\"page-attributes/v1\",\"sourceId\":\"wiki\","
                        + "\"pageId\":\"101\",\"attributes\":{}}"));
    }

    @Test
    void incompleteAttributesAreRecognisedWithoutChangingTheHashOfCompleteOnes() {
        PageMetadata complete = page(Map.of("confluence.labels", "api"));
        PageMetadata truncated = page(Map.of("confluence.labels", "api", "confluence.labels.complete", "false"));

        assertThat(complete.attributesComplete()).isTrue();
        assertThat(page(Map.of("confluence.labels.complete", "true")).attributesComplete()).isTrue();
        assertThat(truncated.attributesComplete()).isFalse();
        assertThat(AttributesHash.of("wiki", complete)).isEqualTo("sha256:" + sha256(
                "{\"schema\":\"page-attributes/v1\",\"sourceId\":\"wiki\",\"pageId\":\"101\","
                        + "\"attributes\":{\"confluence.labels\":\"api\"}}"));
    }

    @Test
    void metadataChangesDoNotTouchTheAttributesHash() {
        PageMetadata page = page(Map.of("confluence.labels", "api"));
        PageMetadata renamed = new PageMetadata("101", page.resourceId(), "Neuer Titel", "DEV", "100", "4",
                Instant.parse("2026-10-16T08:00:00Z"), null, page.attributes());

        assertThat(AttributesHash.of("wiki", renamed)).isEqualTo(AttributesHash.of("wiki", page));
        assertThat(MetadataHash.of("wiki", renamed)).isNotEqualTo(MetadataHash.of("wiki", page));
    }

    @Test
    void comparatorOfTheSuppliedMapDoesNotChangeTheHash() {
        TreeMap<String, String> reversed = new TreeMap<>(Comparator.reverseOrder());
        reversed.put("confluence.labels", "api");
        reversed.put("confluence.type", "page");
        PageMetadata natural = page(Map.of("confluence.labels", "api", "confluence.type", "page"));
        PageMetadata fromReversed = new PageMetadata("101", natural.resourceId(), natural.title(), "DEV", "100", "3",
                natural.modifiedAt(), null, reversed);

        assertThat(fromReversed.attributes().firstKey()).isEqualTo("confluence.labels");
        assertThat(AttributesHash.of("wiki", fromReversed)).isEqualTo(AttributesHash.of("wiki", natural));
    }

    private static PageMetadata page(Map<String, String> attributes) {
        return new PageMetadata("101", "confluence:wiki.example.org/page/101", "Schnittstellen", "DEV", "100", "3",
                Instant.parse("2026-10-15T10:30:00Z"), null, PageMetadata.sorted(attributes));
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
