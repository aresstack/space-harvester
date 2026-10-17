package com.aresstack.spaceharvester.application.sources;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Vertrag von {@link MetadataHash}: deterministisch, sensibel für die Hash-Felder, blind für alles andere. */
class MetadataHashTest {

    private static final PageMetadata PAGE = new PageMetadata("101", "confluence:wiki.example.org/page/101",
            "Schnittstellen", "DEV", "100", "3", Instant.parse("2026-10-15T10:30:00Z"),
            URI.create("https://wiki.example.org/pages/101"), PageMetadata.sorted(Map.of("confluence.labels", "api")));

    @Test
    void sameMetadataGivesTheSameDocumentedHash() {
        assertThat(MetadataHash.of("wiki", PAGE)).isEqualTo(MetadataHash.of("wiki", copy(PAGE)));
        // Festgeschriebene kanonische Form: ändert sich nur mit einem neuen Schema.
        assertThat(MetadataHash.of("wiki", PAGE))
                .isEqualTo("sha256:" + sha256("{\"schema\":\"page-metadata/v1\",\"sourceId\":\"wiki\","
                        + "\"pageId\":\"101\",\"spaceKey\":\"DEV\",\"title\":\"Schnittstellen\","
                        + "\"parentPageId\":\"100\",\"version\":\"3\",\"modifiedAt\":\"2026-10-15T10:30:00Z\"}"));
    }

    @Test
    void versionTitleParentSpaceAndSourceChangeTheHash() {
        String hash = MetadataHash.of("wiki", PAGE);

        assertThat(MetadataHash.of("wiki", with(PAGE, "Schnittstellen", "100", "4", "DEV"))).isNotEqualTo(hash);
        assertThat(MetadataHash.of("wiki", with(PAGE, "Schnittstellen neu", "100", "3", "DEV"))).isNotEqualTo(hash);
        assertThat(MetadataHash.of("wiki", with(PAGE, "Schnittstellen", "200", "3", "DEV"))).isNotEqualTo(hash);
        assertThat(MetadataHash.of("wiki", with(PAGE, "Schnittstellen", null, "3", "DEV"))).isNotEqualTo(hash);
        assertThat(MetadataHash.of("wiki", with(PAGE, "Schnittstellen", "100", "3", "OPS"))).isNotEqualTo(hash);
        assertThat(MetadataHash.of("other-wiki", PAGE)).isNotEqualTo(hash);
    }

    @Test
    void urlAndAttributesAreNotPartOfTheHash() {
        PageMetadata moved = new PageMetadata(PAGE.pageId(), PAGE.resourceId(), PAGE.title(), PAGE.partitionKey(),
                PAGE.parentPageId(), PAGE.version(), PAGE.modifiedAt(), URI.create("https://other.example.org/x"),
                PageMetadata.sorted(Map.of("confluence.labels", "api,neu")));

        assertThat(MetadataHash.of("wiki", moved)).isEqualTo(MetadataHash.of("wiki", PAGE));
    }

    @Test
    void unknownValuesAreNullAndStringsAreEscaped() {
        PageMetadata sparse = new PageMetadata("7", "x:7", "Zitat \"A\\B\"\n", "DEV", null, null, null, null, null);

        assertThat(MetadataHash.of("wiki", sparse)).isEqualTo("sha256:" + sha256(
                "{\"schema\":\"page-metadata/v1\",\"sourceId\":\"wiki\",\"pageId\":\"7\",\"spaceKey\":\"DEV\","
                        + "\"title\":\"Zitat \\\"A\\\\B\\\"\\u000a\",\"parentPageId\":null,\"version\":null,"
                        + "\"modifiedAt\":null}"));
    }

    private static PageMetadata copy(PageMetadata page) {
        return with(page, page.title(), page.parentPageId(), page.version(), page.partitionKey());
    }

    private static PageMetadata with(PageMetadata page, String title, String parent, String version, String space) {
        return new PageMetadata(page.pageId(), page.resourceId(), title, space, parent, version, page.modifiedAt(),
                page.url(), page.attributes());
    }

    private static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
