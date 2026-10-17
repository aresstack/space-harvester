package com.aresstack.spaceharvester.persistence.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.assets.Asset;
import com.aresstack.spaceharvester.application.assets.AssetKind;
import com.aresstack.spaceharvester.application.assets.AssetStoreException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemAssetStoreTest {

    @TempDir
    Path root;

    @Test
    void storesFindsAndReadsByVersion() {
        FileSystemAssetStore store = new FileSystemAssetStore(root);
        byte[] content = bytes("<mxfile/>");
        Asset asset = asset("att1", "3", content);

        store.put(asset, content);

        assertThat(store.find("wiki", "att1", "3")).contains(asset);
        assertThat(store.find("wiki", "att1", "4")).isEmpty();
        assertThat(store.find("andere", "att1", "3")).isEmpty();
        assertThat(store.read(asset)).isEqualTo(content);
    }

    @Test
    void sameContentIsStoredOnce() throws Exception {
        FileSystemAssetStore store = new FileSystemAssetStore(root);
        byte[] content = bytes("gleich");

        store.put(asset("att1", "1", content), content);
        store.put(asset("att2", "1", content), content);

        try (Stream<Path> blobs = Files.walk(root.resolve("blobs"))) {
            assertThat(blobs.filter(Files::isRegularFile).count()).isEqualTo(1);
        }
    }

    @Test
    void changedContentIsDetected() throws Exception {
        FileSystemAssetStore store = new FileSystemAssetStore(root);
        byte[] content = bytes("original");
        Asset asset = asset("att1", "1", content);
        store.put(asset, content);
        try (Stream<Path> blobs = Files.walk(root.resolve("blobs"))) {
            Files.writeString(blobs.filter(Files::isRegularFile).findFirst().orElseThrow(), "verändert");
        }

        assertThatThrownBy(() -> store.read(asset)).isInstanceOf(AssetStoreException.class);
    }

    @Test
    void contentMustMatchItsHash() {
        FileSystemAssetStore store = new FileSystemAssetStore(root);

        assertThatThrownBy(() -> store.put(asset("att1", "1", bytes("a")), bytes("b")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void idsNeverBecomePathsOutsideTheStore() throws Exception {
        FileSystemAssetStore store = new FileSystemAssetStore(root.resolve("store"));
        byte[] content = bytes("x");
        Asset asset = new Asset("../../wiki", "1", "../att1", "../../2", "t", "text/plain", AssetKind.OTHER, 1,
                sha256(content), Instant.EPOCH);

        store.put(asset, content);

        assertThat(store.find("../../wiki", "../att1", "../../2")).contains(asset);
        try (Stream<Path> files = Files.walk(root)) {
            assertThat(files.filter(Files::isRegularFile)).allMatch(p -> p.startsWith(root.resolve("store")));
        }
        assertThat(FileSystemAssetStore.name("att1")).isEqualTo("patt1");
        assertThat(FileSystemAssetStore.name("..")).isEqualTo("x2e2e");
        assertThat(FileSystemAssetStore.name("x".repeat(200))).startsWith("h").hasSize(65);
    }

    @Test
    void withoutVersionOnlyTheContentIsKept() {
        FileSystemAssetStore store = new FileSystemAssetStore(root);
        byte[] content = bytes("x");
        Asset asset = asset("att1", null, content);

        store.put(asset, content);

        assertThat(store.find("wiki", "att1", null)).isEmpty();
        assertThat(store.read(asset)).isEqualTo(content);
    }

    private static Asset asset(String id, String version, byte[] content) {
        return new Asset("wiki", "101", id, version, "datei", "text/plain", AssetKind.OTHER, content.length,
                sha256(content), Instant.parse("2026-10-17T00:00:00Z"));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] content) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
