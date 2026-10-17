package com.aresstack.spaceharvester.application.sources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Vertrag von {@link SpaceFingerprint}: nach {@code pageId} sortiert, feste kanonische Form, jede Seite einmal. */
class SpaceFingerprintTest {

    private static final SpaceFingerprint.Page A = new SpaceFingerprint.Page("101", "sha256:m1", "sha256:a1");
    private static final SpaceFingerprint.Page B = new SpaceFingerprint.Page("102", "sha256:m2", "sha256:a2");

    @Test
    void orderDoesNotMatterAndTheCanonicalFormIsFixed() throws Exception {
        String expected = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((
                "{\"schema\":\"space-inventory/v1\",\"sourceId\":\"wiki\",\"spaceKey\":\"DOC\",\"pages\":["
                        + "{\"pageId\":\"101\",\"metadataHash\":\"sha256:m1\",\"attributesHash\":\"sha256:a1\"},"
                        + "{\"pageId\":\"102\",\"metadataHash\":\"sha256:m2\",\"attributesHash\":\"sha256:a2\"}]}")
                .getBytes(StandardCharsets.UTF_8)));

        assertThat(SpaceFingerprint.of("wiki", "DOC", List.of(B, A)))
                .isEqualTo(SpaceFingerprint.of("wiki", "DOC", List.of(A, B)))
                .isEqualTo(expected);
    }

    @Test
    void anyPageFingerprintAndThePageSetChangeIt() {
        String base = SpaceFingerprint.of("wiki", "DOC", List.of(A, B));

        assertThat(SpaceFingerprint.of("wiki", "DOC", List.of(A,
                new SpaceFingerprint.Page("102", "sha256:m2", "sha256:labels-geaendert")))).isNotEqualTo(base);
        assertThat(SpaceFingerprint.of("wiki", "DOC", List.of(A))).isNotEqualTo(base);
        assertThat(SpaceFingerprint.of("wiki", "OPS", List.of(A, B))).isNotEqualTo(base);
        assertThat(SpaceFingerprint.of("wiki", "DOC", List.of())).isNotEqualTo(base);
    }

    @Test
    void aPageMayAppearOnlyOnce() {
        assertThatThrownBy(() -> SpaceFingerprint.of("wiki", "DOC", List.of(A, A)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
