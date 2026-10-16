package com.aresstack.spaceharvester.application.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.sources.AttributesHash;
import com.aresstack.spaceharvester.application.sources.MetadataHash;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Vertrag von {@link PageSnapshot} und {@link NativePayload}. */
class PageSnapshotTest {

    @Test
    void payloadKeepsItsBytesAndHashesExactlyThem() throws Exception {
        byte[] bytes = SnapshotRepositoryContract.NOT_UTF8.clone();
        NativePayload payload = new NativePayload(bytes, "application/json", "format");
        bytes[0] = 'X';
        byte[] returned = payload.bytes();
        returned[1] = 'Y';

        assertThat(payload.bytes()).isEqualTo(SnapshotRepositoryContract.NOT_UTF8);
        assertThat(payload.rawSha256()).isEqualTo("sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(SnapshotRepositoryContract.NOT_UTF8)));
        assertThat(payload.length()).isEqualTo(6);
    }

    @Test
    void snapshotCarriesTheSameHashesAsTheMetadataListing() {
        PageSnapshot snapshot = SnapshotRepositoryContract.snapshot("wiki", "101", "3",
                SnapshotRepositoryContract.json("{}"));

        assertThat(snapshot.metadataHash()).isEqualTo(MetadataHash.of("wiki", snapshot.metadata()));
        assertThat(snapshot.attributesHash()).isEqualTo(AttributesHash.of("wiki", snapshot.metadata()));
        assertThat(snapshot.key()).isEqualTo(new PageKey("wiki", "101"));
        assertThat(snapshot.revision()).isEqualTo("3");
        assertThat(snapshot.spaceKey()).isEqualTo("DEV");
        assertThat(snapshot.parentPageId()).isEqualTo("100");
    }

    @Test
    void keyAndHeaderMustNameTheSamePage() {
        PageSnapshot snapshot = SnapshotRepositoryContract.snapshot("wiki", "101", "3",
                SnapshotRepositoryContract.json("{}"));

        assertThatThrownBy(() -> new PageSnapshot(new PageKey("wiki", "102"), snapshot.metadata(),
                snapshot.metadataHash(), snapshot.attributesHash(), snapshot.payload(), snapshot.retrievedAt()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageKey("wiki", " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void textFormNeverShowsThePayload() {
        PageMetadata metadata = new PageMetadata("101", "r", "Titel", "DEV", null, "1", null, null, PageMetadata.sorted(Map.of()));
        PageSnapshot snapshot = PageSnapshot.of("wiki", metadata,
                new NativePayload(SnapshotRepositoryContract.json("{\"geheim\":\"Inhalt\"}"), "application/json",
                        "format"), Instant.EPOCH);

        assertThat(snapshot.payload().toString()).doesNotContain("geheim").contains("bytes");
        assertThat(snapshot.toString()).doesNotContain("geheim");
    }
}
