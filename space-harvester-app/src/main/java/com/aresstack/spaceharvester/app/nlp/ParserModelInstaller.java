package com.aresstack.spaceharvester.app.nlp;

import com.aresstack.enterpriseai.proasteion.platform.network.ResumableDownload;
import com.aresstack.spaceharvester.nlp.malt.GermanParserModelPin;
import com.aresstack.spaceharvester.nlp.malt.MaltModelLoader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installiert das freigegebene deutsche MaltParser-Modell auf Klick (ADR 0024, Muster aus askai
 * {@code LocalModelInstaller}): ZIP aus dem GitHub-Release laden, SHA-256 gegen den Pin prüfen, in ein
 * Zwischenverzeichnis neben dem Ziel entpacken, Vollständigkeit prüfen ({@code model-manifest.json} und die
 * {@code .mco}), dann das Verzeichnis als Ganzes an seinen Platz schieben. Nichts Halbfertiges wird sichtbar; ein
 * Abbruch oder Fehler räumt das Zwischenverzeichnis weg und lässt ein vorhandenes Modell unberührt. Trainiert nie.
 *
 * <p>Läuft blockierend; der Aufrufer ruft {@link #install} im Hintergrund auf.
 */
public final class ParserModelInstaller {

    /** Datei mit der SHA-256 der installierten ZIP (wie beim Gradle-Task {@code downloadGermanParserModel}). */
    static final String MARKER = ".sha256";

    /** Holt die ZIP; liefert den Inhalt und, falls bekannt, die Länge in Bytes (sonst {@code -1}). */
    public interface Download {
        Body open(URI target) throws IOException;

        /**
         * Der Rest ab Byte {@code from} nach einem Abriss ({@code Range}, mit {@code validator} auch {@code If-Range});
         * ohne Unterstützung scheitert der Download wie bisher.
         */
        default ResumableDownload.Response rest(URI target, long from, String validator) throws IOException {
            throw new IOException("Fortsetzen des Downloads nicht unterstützt");
        }
    }

    /**
     * Antwort eines {@link Download}.
     *
     * @param validator {@code ETag}, sonst {@code Last-Modified}, oder {@code null}; damit kommt beim Fortsetzen nie ein
     *                  Rest einer anderen Fassung
     */
    public record Body(InputStream content, long length, String validator) {

        public Body(InputStream content, long length) {
            this(content, length, null);
        }
    }

    /** Fortschritt für die Oberfläche. */
    public interface Progress {
        /** Schritt in Worten, etwa „Wird entpackt …“. */
        void step(String text);

        /** Geladene Bytes; {@code total} ist {@code -1}, wenn die Länge unbekannt ist. */
        void bytes(long done, long total);
    }

    private final Path directory;
    private final GermanParserModelPin.Release release;
    private final Download download;
    private final int resumes;
    /** Der gerade gelesene Datenstrom; {@link #abort} schließt ihn, damit ein hängender Download endet. */
    private volatile InputStream current;

    /**
     * @param directory Zielverzeichnis, siehe {@link MaltStatementAnalyzer#defaultDirectory}
     * @param release   das freigegebene Release aus dem Pin
     * @param download  holt die ZIP über den HTTP-Weg der App (Proxy, TLS)
     */
    public ParserModelInstaller(Path directory, GermanParserModelPin.Release release, Download download) {
        this(directory, release, download, 0);
    }

    /**
     * @param resumes wie oft ein abgerissener Download ab dem letzten Byte fortgesetzt wird (in der App
     *                {@code jobs.retry.max-attempts}), 0 für nie (Angelo 26.11.2026, WLAN)
     */
    public ParserModelInstaller(Path directory, GermanParserModelPin.Release release, Download download,
                                int resumes) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.release = Objects.requireNonNull(release, "release");
        this.download = Objects.requireNonNull(download, "download");
        if (resumes < 0) {
            throw new IllegalArgumentException("resumes darf nicht negativ sein");
        }
        this.resumes = resumes;
    }

    public Path directory() {
        return directory;
    }

    public GermanParserModelPin.Release release() {
        return release;
    }

    /** Ob das Modell vollständig im Zielverzeichnis liegt (Manifest und mindestens eine {@code .mco}). */
    public boolean installed() {
        return complete(directory);
    }

    /**
     * Lädt, prüft, entpackt und aktiviert das Modell.
     *
     * @throws CancellationException wenn {@code cancelled} während des Laufs wahr wird
     * @throws IOException           bei Netz-, Prüfsummen- oder Dateifehlern; ein vorhandenes Modell bleibt
     */
    public void install(Progress progress, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(cancelled, "cancelled");
        Path parent = directory.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path staging = Files.createTempDirectory(parent, ".staging-" + release.tag() + "-");
        try {
            Path zip = staging.resolve(release.asset());
            progress.step("Wird heruntergeladen …");
            String actual = fetch(zip, progress, cancelled);
            if (!actual.equals(release.sha256())) {
                throw new IOException("Prüfsumme der heruntergeladenen Datei passt nicht (SHA-256 " + actual
                        + ", erwartet " + release.sha256() + "); nichts installiert.");
            }
            progress.step("Wird entpackt …");
            Path unpacked = staging.resolve(directory.getFileName().toString());
            unzip(zip, unpacked, cancelled);
            if (!complete(unpacked)) {
                throw new IOException("Das Modellarchiv ist unvollständig (" + MaltModelLoader.MANIFEST
                        + " oder .mco fehlt); nichts installiert.");
            }
            Files.writeString(unpacked.resolve(MARKER), release.sha256(), StandardCharsets.US_ASCII);
            check(cancelled);
            progress.step("Wird eingerichtet …");
            activate(unpacked);
        } finally {
            deleteRecursively(staging);
        }
    }

    private String fetch(Path zip, Progress progress, BooleanSupplier cancelled) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        URI target = URI.create(release.download());
        Body body = download.open(target);
        current = body.content();
        if (cancelled.getAsBoolean()) {
            body.content().close();
        }
        long total = body.length();
        progress.bytes(0, total);
        try (java.nio.channels.FileChannel out = java.nio.channels.FileChannel.open(zip,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
            ResumableDownload.Sink file = ResumableDownload.sink(out);
            ResumableDownload.copy(new ResumableDownload.Response(200, null, body.validator(), body.content(), total),
                    (from, validator) -> {
                        // Nach einem Abbruch (abort schließt den Strom) nie fortsetzen.
                        check(cancelled);
                        ResumableDownload.Response rest = download.rest(target, from, validator);
                        current = rest.body();
                        return rest;
                    }, new ResumableDownload.Sink() {
                        private long done;

                        @Override
                        public void write(byte[] buffer, int offset, int length) throws IOException {
                            check(cancelled);
                            file.write(buffer, offset, length);
                            digest.update(buffer, offset, length);
                            done += length;
                            progress.bytes(done, total);
                        }

                        @Override
                        public void restart() throws IOException {
                            file.restart();
                            digest.reset();
                            done = 0;
                            progress.bytes(0, total);
                        }
                    }, Long.MAX_VALUE, resumes);
        } catch (IOException e) {
            check(cancelled);
            throw e;
        } finally {
            current = null;
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Beendet einen laufenden Download sofort (aus einem anderen Thread); {@link #install} meldet dann den Abbruch. */
    public void abort() {
        InputStream stream = current;
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // Der Abbruch zählt trotzdem; das Lesen endet mit einem Fehler.
            }
        }
    }

    private static void unzip(Path zip, Path target, BooleanSupplier cancelled) throws IOException {
        Files.createDirectories(target);
        Path root = target.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                check(cancelled);
                Path out = root.resolve(entry.getName()).normalize();
                if (!out.startsWith(root) || out.equals(root)) {
                    throw new IOException("Unzulässiger Pfad im Modellarchiv; nichts installiert.");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        // Liegt der Inhalt in genau einem Unterordner, wird dieser zum Modellverzeichnis.
        if (!complete(target)) {
            try (Stream<Path> children = Files.list(target)) {
                Path[] all = children.toArray(Path[]::new);
                if (all.length == 1 && Files.isDirectory(all[0]) && complete(all[0])) {
                    Path lifted = target.resolveSibling(target.getFileName() + ".lifted");
                    Files.move(all[0], lifted);
                    Files.delete(target);
                    Files.move(lifted, target);
                }
            }
        }
    }

    private void activate(Path unpacked) throws IOException {
        Path old = null;
        if (Files.exists(directory)) {
            old = directory.resolveSibling(directory.getFileName() + ".old-" + System.nanoTime());
            Files.move(directory, old);
        }
        try {
            try {
                Files.move(unpacked, directory, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(unpacked, directory);
            }
        } catch (IOException e) {
            if (old != null && !Files.exists(directory)) {
                Files.move(old, directory);
            }
            throw e;
        }
        if (old != null) {
            deleteRecursively(old);
        }
    }

    static boolean complete(Path candidate) {
        if (!Files.isRegularFile(candidate.resolve(MaltModelLoader.MANIFEST))) {
            return false;
        }
        try (Stream<Path> files = Files.list(candidate)) {
            return files.anyMatch(file -> file.getFileName().toString().endsWith(".mco") && Files.isRegularFile(file));
        } catch (IOException e) {
            return false;
        }
    }

    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Installation abgebrochen");
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    path.toFile().deleteOnExit();
                }
            });
        } catch (IOException ignored) {
            // Reste eines Zwischenverzeichnisses stören nicht; sie beginnen mit einem Punkt.
        }
    }
}
