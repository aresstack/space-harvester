package com.aresstack.spaceharvester.persistence.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.assets.Asset;
import com.aresstack.spaceharvester.application.assets.AssetKind;
import com.aresstack.spaceharvester.application.assets.AssetStoreException;
import com.aresstack.spaceharvester.application.assets.AssetStoreFullException;
import com.aresstack.spaceharvester.application.assets.AssetText;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemAssetStoreTest {

    @TempDir
    Path root;

    /** ADR 0026, Punkt 11: Bis zum Neulesen bleibt der jüngste Text anderer Extraktion oder Version lieferbar. */
    @Test
    void theLatestTextIsTheNewestOfAnyExtractionAndVersionOfTheSameBytes() throws Exception {
        FileSystemAssetStore store = new FileSystemAssetStore(root);
        String sha = "sha256:" + "ab".repeat(32);
        store.putText(new AssetText(sha, "tika", "3.3-1", "alt", false, Instant.parse("2026-11-01T00:00:00Z")));
        store.putText(new AssetText(sha, "import", "unbekannt", "mitgebracht", true,
                Instant.parse("2026-11-02T00:00:00Z")));
        store.putText(new AssetText("sha256:" + "cd".repeat(32), "tika", "3.3-2", "fremd", false,
                Instant.parse("2026-11-03T00:00:00Z")));

        AssetText latest = store.latestText(sha).orElseThrow();
        assertThat(latest.text()).isEqualTo("mitgebracht");
        assertThat(latest.extractor()).isEqualTo("import");
        assertThat(latest.truncated()).isTrue();
        assertThat(store.latestText("sha256:" + "ef".repeat(32))).isEmpty();
        assertThat(store.latestText("kein Hash")).isEmpty();

        // Eine beschädigte Datei zählt nicht; der nächstjüngere Text bleibt.
        try (Stream<Path> files = Files.walk(root.resolve("texts"))) {
            Path imported = files.filter(path -> path.toString().contains("pimport")).filter(Files::isRegularFile)
                    .findFirst().orElseThrow();
            Files.writeString(imported, "createdAt=kaputt\n");
            assertThat(store.latestText(sha)).map(AssetText::text).contains("alt");
            // Auch eine fehlerhafte Escape-Folge sperrt den Rückfall nicht.
            Files.writeString(imported, "text=\\u12\n");
        }
        assertThat(store.latestText(sha)).map(AssetText::text).contains("alt");
        assertThatThrownBy(() -> store.text(sha, "import", "unbekannt")).isInstanceOf(AssetStoreException.class);
    }

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

    /** Streaming (Angelo 11.11.2026: kein Größenlimit): Die Datei wird verschoben, nie ganz in den Speicher gelesen. */
    @Test
    void aStagedFileIsMovedIntoTheStoreAndCheckedAgainstHashAndSize() throws Exception {
        FileSystemAssetStore store = new FileSystemAssetStore(root);
        byte[] content = bytes("%PDF-sehr-groß");
        Path staged = store.stagingFile();
        java.nio.file.Files.write(staged, content);
        Asset asset = asset("att1", "1", content);

        store.putFile(asset, staged);

        assertThat(staged).doesNotExist();
        assertThat(store.find("wiki", "att1", "1")).contains(asset);
        assertThat(store.read(asset)).isEqualTo(content);

        Path wrong = store.stagingFile();
        java.nio.file.Files.write(wrong, bytes("anders"));
        assertThatThrownBy(() -> store.putFile(asset("att2", "1", content), wrong))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(wrong).doesNotExist();
        assertThat(store.find("wiki", "att2", "1")).isEmpty();
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

    /**
     * Audit 13.11.2026: Prüfen und Reservieren liefen nicht zusammen, gleichzeitige Ablagen desselben Inhalts zählten
     * ihn mehrfach und scheiterten an der Grenze. Die Grenze fasst hier genau einen Inhalt, jede Doppelzählung fällt
     * also auf; danach muss noch genau der Rest frei sein.
     */
    @Test
    void theSameContentStoredConcurrentlyCountsOnceAgainstTheTotalLimit() throws Exception {
        int writers = 16;
        byte[] content = new byte[256 * 1024];
        byte[] rest = bytes("rest");
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            for (int round = 0; round < 8; round++) {
                content[0] = (byte) round;
                byte[] shared = content.clone();
                FileSystemAssetStore store = new FileSystemAssetStore(root.resolve("runde" + round),
                        shared.length + rest.length);
                CyclicBarrier start = new CyclicBarrier(writers);
                List<Future<?>> results = new ArrayList<>();
                for (int writer = 0; writer < writers; writer++) {
                    String id = "att" + writer;
                    boolean staged = writer % 2 == 1;
                    results.add(pool.submit(() -> {
                        Asset asset = asset(id, "1", shared);
                        Path file = null;
                        if (staged) {
                            file = store.stagingFile();
                            Files.write(file, shared);
                        }
                        start.await(30, TimeUnit.SECONDS);
                        if (staged) {
                            store.putFile(asset, file);
                        } else {
                            store.put(asset, shared);
                        }
                        return null;
                    }));
                }
                for (Future<?> result : results) {
                    result.get(60, TimeUnit.SECONDS);
                }
                for (int writer = 0; writer < writers; writer++) {
                    assertThat(store.find("wiki", "att" + writer, "1")).isPresent();
                }
                store.put(asset("rest", "1", rest), rest);
            }
        } finally {
            pool.shutdownNow();
        }
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
    void aStreamReadsTheContentAndReportsAChangeAtItsEnd() throws Exception {
        FileSystemAssetStore store = new FileSystemAssetStore(root);
        byte[] content = bytes("original");
        Asset asset = asset("att1", "1", content);
        store.put(asset, content);
        try (java.io.InputStream in = store.open(asset)) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
        try (Stream<Path> blobs = Files.walk(root.resolve("blobs"))) {
            Files.writeString(blobs.filter(Files::isRegularFile).findFirst().orElseThrow(), "verändert");
        }

        try (java.io.InputStream in = store.open(asset)) {
            assertThatThrownBy(in::readAllBytes).isInstanceOf(java.io.IOException.class);
        }
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

    @Test
    void documentTextsAreKeptPerContentAndExtractionAndGoWithTheirContent() {
        FileSystemAssetStore store = new FileSystemAssetStore(root);
        byte[] content = bytes("%PDF-Bericht");
        store.put(asset("att1", "1", content), content);
        AssetText text = new AssetText(sha256(content), "tika", "3.3-1", "Zeile eins\nÄnderung = zwei", true,
                Instant.parse("2026-10-18T00:00:00Z"));

        store.putText(text);

        assertThat(store.text(sha256(content), "tika", "3.3-1")).contains(text);
        assertThat(store.text(sha256(content), "tika", "3.3-2")).isEmpty();
        assertThat(store.text(sha256(bytes("anders")), "tika", "3.3-1")).isEmpty();
        assertThat(store.removeSource("wiki", sha -> false, Instant.now().plusSeconds(60))).isEqualTo(1);
        assertThat(store.text(sha256(content), "tika", "3.3-1")).isEmpty();
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
