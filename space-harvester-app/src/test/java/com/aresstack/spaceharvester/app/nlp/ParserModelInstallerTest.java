package com.aresstack.spaceharvester.app.nlp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.nlp.malt.GermanParserModelPin;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParserModelInstallerTest {

    @TempDir
    Path data;

    @Test
    void downloadsVerifiesAndUnpacksIntoTheModelDirectory() throws Exception {
        byte[] zip = zip("model-manifest.json", "{}", "maltparser-de-gsd.mco", "mco");
        List<URI> requested = new ArrayList<>();
        List<Long> progress = new ArrayList<>();
        ParserModelInstaller installer = installer(zip, sha(zip), requested);

        assertThat(installer.installed()).isFalse();
        installer.install(progress((done, total) -> progress.add(done)), () -> false);

        Path target = installer.directory();
        assertThat(installer.installed()).isTrue();
        assertThat(target.resolve("maltparser-de-gsd.mco")).hasContent("mco");
        assertThat(target.resolve(ParserModelInstaller.MARKER)).hasContent(sha(zip));
        assertThat(requested).containsExactly(URI.create(
                "https://github.com/aresstack/space-harvester/releases/download/maltparser-de-gsd-v1.0.0/"
                        + "maltparser-de-gsd-v1.0.0.zip"));
        assertThat(progress).last().isEqualTo((long) zip.length);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void wrongChecksumInstallsNothing() throws Exception {
        byte[] zip = zip("model-manifest.json", "{}", "maltparser-de-gsd.mco", "mco");
        ParserModelInstaller installer = installer(zip, "0".repeat(64), new ArrayList<>());

        assertThatThrownBy(() -> installer.install(progress((d, t) -> { }), () -> false))
                .isInstanceOf(IOException.class).hasMessageContaining("Prüfsumme");
        assertThat(installer.installed()).isFalse();
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void incompleteArchiveInstallsNothing() throws Exception {
        byte[] zip = zip("model-manifest.json", "{}", "readme.txt", "x");
        ParserModelInstaller installer = installer(zip, sha(zip), new ArrayList<>());

        assertThatThrownBy(() -> installer.install(progress((d, t) -> { }), () -> false))
                .isInstanceOf(IOException.class).hasMessageContaining("unvollständig");
        assertThat(installer.directory()).doesNotExist();
    }

    @Test
    void pathsOutsideTheModelDirectoryAreRejected() throws Exception {
        byte[] zip = zip("../evil.txt", "x", "model-manifest.json", "{}");
        ParserModelInstaller installer = installer(zip, sha(zip), new ArrayList<>());

        assertThatThrownBy(() -> installer.install(progress((d, t) -> { }), () -> false))
                .isInstanceOf(IOException.class).hasMessageContaining("Unzulässiger Pfad");
        assertThat(data.resolve("nlp-model").resolve("evil.txt")).doesNotExist();
    }

    @Test
    void cancellingKeepsAnExistingModel() throws Exception {
        byte[] zip = zip("model-manifest.json", "{}", "maltparser-de-gsd.mco", "new");
        ParserModelInstaller installer = installer(zip, sha(zip), new ArrayList<>());
        Files.createDirectories(installer.directory());
        Files.writeString(installer.directory().resolve("model-manifest.json"), "{}");
        Files.writeString(installer.directory().resolve("maltparser-de-gsd.mco"), "old");

        assertThatThrownBy(() -> installer.install(progress((d, t) -> { }), () -> true))
                .isInstanceOf(CancellationException.class);
        assertThat(installer.directory().resolve("maltparser-de-gsd.mco")).hasContent("old");
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void abortEndsAStalledDownload() throws Exception {
        // Wie der Datenstrom des HttpClient: read() hängt, bis close() ihn mit einer IOException beendet.
        java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
        java.io.InputStream stalled = new java.io.InputStream() {
            @Override
            public int read() throws IOException {
                try {
                    closed.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("closed");
            }

            @Override
            public void close() {
                closed.countDown();
            }
        };
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        GermanParserModelPin.Release release = new GermanParserModelPin.Release("aresstack/space-harvester",
                "maltparser-de-gsd-v1.0.0", "maltparser-de-gsd-v1.0.0.zip", "0".repeat(64));
        java.util.concurrent.CountDownLatch opened = new java.util.concurrent.CountDownLatch(1);
        ParserModelInstaller installer = new ParserModelInstaller(
                data.resolve("nlp-model").resolve("maltparser-de-gsd-v1.0.0"), release, target -> {
                    opened.countDown();
                    return new ParserModelInstaller.Body(stalled, -1);
                });
        java.util.concurrent.CompletableFuture<Throwable> result = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> {
                    try {
                        installer.install(progress((d, t) -> { }), cancelled::get);
                        return null;
                    } catch (Throwable e) {
                        return e;
                    }
                });
        assertThat(opened.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100);
        cancelled.set(true);
        installer.abort();

        assertThat(result.get(5, java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(CancellationException.class);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void reinstallReplacesTheModel() throws Exception {
        byte[] zip = zip("model-manifest.json", "{}", "maltparser-de-gsd.mco", "new");
        ParserModelInstaller installer = installer(zip, sha(zip), new ArrayList<>());
        Files.createDirectories(installer.directory());
        Files.writeString(installer.directory().resolve("stale.txt"), "old");

        installer.install(progress((d, t) -> { }), () -> false);

        assertThat(installer.directory().resolve("maltparser-de-gsd.mco")).hasContent("new");
        assertThat(installer.directory().resolve("stale.txt")).doesNotExist();
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void pinnedReleasePointsAtTheGitHubRelease() {
        GermanParserModelPin.Release release = GermanParserModelPin.release().orElseThrow();
        assertThat(release.page())
                .isEqualTo("https://github.com/aresstack/space-harvester/releases/tag/maltparser-de-gsd-v1.0.0");
        assertThat(release.sha256()).hasSize(64);
    }

    private ParserModelInstaller installer(byte[] zip, String sha256, List<URI> requested) {
        GermanParserModelPin.Release release = new GermanParserModelPin.Release("aresstack/space-harvester",
                "maltparser-de-gsd-v1.0.0", "maltparser-de-gsd-v1.0.0.zip", sha256);
        return new ParserModelInstaller(data.resolve("nlp-model").resolve("maltparser-de-gsd-v1.0.0"), release,
                target -> {
                    requested.add(target);
                    return new ParserModelInstaller.Body(new ByteArrayInputStream(zip), zip.length);
                });
    }

    /** Alles unter nlp-model außer dem Modellverzeichnis (Zwischenverzeichnisse, alte Fassungen). */
    private List<Path> leftovers() throws IOException {
        Path root = data.resolve("nlp-model");
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(root)) {
            return children.filter(path -> !path.getFileName().toString().equals("maltparser-de-gsd-v1.0.0"))
                    .toList();
        }
    }

    private interface Bytes {
        void accept(long done, long total);
    }

    private static ParserModelInstaller.Progress progress(Bytes bytes) {
        return new ParserModelInstaller.Progress() {
            @Override
            public void step(String text) {
            }

            @Override
            public void bytes(long done, long total) {
                bytes.accept(done, total);
            }
        };
    }

    private static byte[] zip(String... namesAndContents) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(raw)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                out.putNextEntry(new ZipEntry(namesAndContents[i]));
                out.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return raw.toByteArray();
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
