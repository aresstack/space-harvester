package com.aresstack.spaceharvester.persistence.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.assets.Asset;
import com.aresstack.spaceharvester.application.assets.AssetKind;
import com.aresstack.spaceharvester.application.assets.AssetStoreException;
import com.aresstack.spaceharvester.application.assets.AssetStoreFullException;
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
    void theTotalLimitRefusesNewContentButNotKnownContentAndCountsWhatIsAlreadyThere() {
        byte[] first = bytes("1234567890");
        new FileSystemAssetStore(root, 15).put(asset("att1", "1", first), first);
        FileSystemAssetStore store = new FileSystemAssetStore(root, 15);
        byte[] second = bytes("abcdef");

        assertThatThrownBy(() -> store.put(asset("att2", "1", second), second))
                .isInstanceOf(AssetStoreFullException.class);
        assertThat(store.find("wiki", "att2", "1")).isEmpty();
        store.put(asset("att3", "1", first), first);
        assertThat(store.find("wiki", "att3", "1")).isPresent();
        byte[] small = bytes("abcde");
        store.put(asset("att4", "1", small), small);
        assertThat(store.read(asset("att4", "1", small))).isEqualTo(small);
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
    void aTamperedEntryNeverPointsOutsideTheBlobs() throws Exception {
        FileSystemAssetStore store = new FileSystemAssetStore(root.resolve("store"));
        byte[] content = bytes("x");
        store.put(asset("att1", "1", content), content);
        Path entry;
        try (Stream<Path> files = Files.walk(root.resolve("store"))) {
            entry = files.filter(Files::isRegularFile).filter(p -> !p.startsWith(root.resolve("store/blobs")))
                    .findFirst().orElseThrow();
        }
        Files.writeString(root.resolve("secret"), "x");
        Files.writeString(entry, Files.readString(entry).replaceAll("sha256=.*", "sha256=sha256:../secret"));

        assertThat(store.find("wiki", "att1", "1")).isEmpty();
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

    @Test
    void removingASourceDropsItsEntriesAndContentNoLongerNeeded() {
        FileSystemAssetStore store = new FileSystemAssetStore(root);
        byte[] own = bytes("nur wiki");
        byte[] shared = bytes("in beiden");
        byte[] exported = bytes("im Export");
        byte[] cached = bytes("ohne Eintrag");
        store.put(asset("att1", "1", own), own);
        store.put(asset("att2", "1", shared), shared);
        store.put(asset("att3", "1", exported), exported);
        store.put(asset("att4", null, cached), cached);
        Asset other = new Asset("andere", "201", "att9", "1", "datei", "text/plain", AssetKind.OTHER, shared.length,
                sha256(shared), Instant.parse("2026-10-17T00:00:00Z"));
        store.put(other, shared);
        Instant later = Instant.now().plusSeconds(60);

        int removed = store.removeSource("wiki", sha256(exported)::equals, later);

        // Eigenes und Zwischengespeichertes ohne Eintrag gehen; was eine andere Quelle oder ein Export braucht, bleibt.
        assertThat(removed).isEqualTo(2);
        assertThat(store.find("wiki", "att2", "1")).isEmpty();
        assertThat(store.find("andere", "att9", "1")).isPresent();
        assertThat(store.read(other)).isEqualTo(shared);
        assertThat(store.read(asset("att3", "1", exported))).isEqualTo(exported);
        assertThatThrownBy(() -> store.read(asset("att1", "1", own))).isInstanceOf(AssetStoreException.class);
        // Was nach Beginn des Entfernens geschrieben wurde, bleibt.
        store.put(asset("att5", "1", own), own);
        assertThat(store.removeSource("wiki", sha -> false, Instant.parse("2026-01-01T00:00:00Z"))).isZero();
        assertThat(store.read(asset("att5", "1", own))).isEqualTo(own);
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
