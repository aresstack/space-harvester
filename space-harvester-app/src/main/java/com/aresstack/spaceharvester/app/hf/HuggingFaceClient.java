package com.aresstack.spaceharvester.app.hf;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Liest Dateilisten von Hugging Face und lädt Dateien mit Fortsetzen, Wiederholen und Prüfsumme. Übernommen aus
 * askai ({@code com.aresstack.askai.hf.HuggingFaceClient}: {@code listAllFilesDetailed}, {@code resolveRevisionSha},
 * {@code download}); der Netzweg ist hier der {@link Transport} der App (Proxy, PAC, TLS), JSON liest Jackson.
 *
 * <p>Ein Download schreibt in {@code <Ziel>.part}, setzt nach einem Abriss mit {@code Range} fort und gilt erst als
 * fertig, wenn Länge und Prüfsumme stimmen (SHA-256 der LFS-Datei, sonst die Git-Blob-SHA-1). Scheitert er, bleibt die
 * {@code .part} für den nächsten Versuch liegen; eine falsche Prüfsumme verwirft sie. Blockierend.
 */
public final class HuggingFaceClient {

    static final String HOST = "https://huggingface.co";
    static final int MAX_DOWNLOAD_ATTEMPTS = 5;
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /** Der Netzweg der App. */
    public interface Transport {
        /**
         * Holt {@code target}, ab Byte {@code rangeFrom} wenn größer als null; liefert jede Antwort, auch Fehler.
         *
         * @throws IOException wenn keine Antwort kommt
         */
        Response get(URI target, long rangeFrom) throws IOException;
    }

    /**
     * Eine Antwort.
     *
     * @param status        HTTP-Status
     * @param content       Inhalt
     * @param contentLength Länge des Inhalts dieser Antwort, {@code -1} wenn unbekannt
     * @param contentRange  Kopfzeile {@code Content-Range}, {@code null} wenn sie fehlt
     * @param host          Rechner, der geantwortet hat (nach Weiterleitungen)
     */
    public record Response(int status, InputStream content, long contentLength, String contentRange, String host)
            implements Closeable {
        @Override
        public void close() throws IOException {
            content.close();
        }
    }

    /** Fortschritt eines Downloads; {@code total} ist {@code -1}, wenn die Länge unbekannt ist. */
    public interface Progress {
        void bytes(long done, long total);
    }

    private final Transport transport;
    /** Der gerade gelesene Datenstrom; {@link #abort} schließt ihn, damit ein hängender Download endet. */
    private volatile InputStream current;

    public HuggingFaceClient(Transport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    /**
     * Löst einen Zweig oder Tag zur Commit-SHA auf, damit Liste und Dateien aus demselben Stand kommen ({@code main}
     * kann sich zwischen zwei Abrufen bewegen).
     */
    public String resolveRevision(String repository, String revision) throws IOException {
        JsonNode info = JSON.readTree(text(HOST + "/api/models/" + encodePath(repository) + "/revision/"
                + encodePath(revision) + "?expand[]=sha"));
        String sha = info.path("sha").asString("");
        return sha.isEmpty() ? revision : sha;
    }

    /** Alle Dateien des Repositorys in {@code revision}, mit Länge und Prüfsumme, soweit Hugging Face sie nennt. */
    public List<HuggingFaceFile> listFiles(String repository, String revision) throws IOException {
        return parseTree(repository, text(HOST + "/api/models/" + encodePath(repository) + "/tree/"
                + encodePath(revision) + "?recursive=true"));
    }

    /** Liest die Antwort der Tree-API; für LFS-Dateien zählen Länge und SHA-256 aus dem Block {@code lfs}. */
    static List<HuggingFaceFile> parseTree(String repository, String json) throws IOException {
        JsonNode tree;
        try {
            tree = JSON.readTree(json);
        } catch (RuntimeException e) {
            throw new IOException("Hugging Face lieferte keine lesbare Dateiliste für " + repository, e);
        }
        List<HuggingFaceFile> files = new ArrayList<>();
        for (JsonNode entry : tree) {
            String type = entry.path("type").asString("");
            String path = entry.path("path").asString("");
            if (path.isEmpty() || !(type.isEmpty() || "file".equals(type))) {
                continue;
            }
            long size = entry.path("size").asLong(-1);
            String sha256 = "";
            String gitOid = entry.path("oid").asString("");
            JsonNode lfs = entry.path("lfs");
            if (lfs.isObject()) {
                if (lfs.path("size").asLong(0) > 0) {
                    size = lfs.path("size").asLong();
                }
                String oid = lfs.path("oid").asString("");
                sha256 = oid.toLowerCase(java.util.Locale.ROOT).startsWith("sha256:") ? oid.substring(7) : oid;
                gitOid = "";
            }
            files.add(new HuggingFaceFile(repository, path, size, sha256, gitOid));
        }
        return files;
    }

    /**
     * Lädt {@code file} aus {@code revision} nach {@code target}; liegt dort schon {@code <target>.part}, wird sie
     * fortgesetzt.
     *
     * @throws CancellationException wenn {@code cancelled} wahr wird; die {@code .part} bleibt zum Fortsetzen
     * @throws IOException           nach {@value #MAX_DOWNLOAD_ATTEMPTS} Versuchen ohne vollständige, geprüfte Datei
     */
    public void download(HuggingFaceFile file, String revision, Path target, Progress progress,
                         BooleanSupplier cancelled) throws IOException {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        Files.createDirectories(target.toAbsolutePath().getParent());
        URI url = URI.create(HOST + "/" + encodePath(file.repository()) + "/resolve/" + encodePath(revision) + "/"
                + encodePath(file.path()));
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_DOWNLOAD_ATTEMPTS; attempt++) {
            check(cancelled);
            long resumeFrom = Files.isRegularFile(part) ? Files.size(part) : 0L;
            if (file.size() > 0 && resumeFrom > file.size()) {
                // Mehr als die ganze Datei: veraltet oder beschädigt, von vorn.
                Files.deleteIfExists(part);
                resumeFrom = 0L;
            }
            try {
                Pass pass = downloadOnce(url, part, resumeFrom, file.size(), progress, cancelled);
                long actual = Files.size(part);
                long expected = file.size() > 0 ? file.size() : pass.total();
                if (expected > 0 && actual != expected) {
                    last = new IOException("Unvollständig: " + actual + " von " + expected + " Bytes");
                    continue; // der nächste Versuch setzt fort
                }
                verify(file, part, pass.sha256());
                try {
                    Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (ChecksumMismatch e) {
                // Ein beschädigter Inhalt heilt nicht durch Fortsetzen.
                Files.deleteIfExists(part);
                last = e;
            } catch (IOException e) {
                check(cancelled);
                last = e;
            }
        }
        throw new IOException(file.fileName() + " ließ sich nach " + MAX_DOWNLOAD_ATTEMPTS + " Versuchen nicht laden: "
                + (last == null ? "unbekannter Fehler" : last.getMessage()), last);
    }

    /** Beendet einen laufenden Download sofort (aus einem anderen Thread). */
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

    /** Ergebnis eines Durchgangs: die ganze Länge laut Antwort und die SHA-256 der Datei bis hierher. */
    private record Pass(long total, String sha256) {
    }

    private Pass downloadOnce(URI url, Path part, long resumeFrom, long expectedSize, Progress progress,
                              BooleanSupplier cancelled) throws IOException {
        try (Response response = transport.get(url, resumeFrom)) {
            boolean append;
            if (resumeFrom > 0 && response.status() == 206) {
                append = true;
            } else if (response.status() == 200) {
                append = false; // Range ignoriert oder neuer Anfang: von vorn schreiben
            } else if (response.status() == 416) {
                if (expectedSize > 0 && Files.size(part) == expectedSize) {
                    return new Pass(expectedSize, sha256(part));
                }
                Files.deleteIfExists(part);
                throw new IOException("Fortsetzen abgelehnt (HTTP 416); beginnt neu");
            } else {
                throw new IOException("HTTP " + response.status() + " von " + response.host());
            }
            long base = append ? resumeFrom : 0L;
            MessageDigest digest = newDigest("SHA-256");
            if (append) {
                digestFile(part, digest);
            }
            long total = total(response, base, expectedSize);
            current = response.content();
            check(cancelled);
            try (OutputStream out = append
                    ? Files.newOutputStream(part, StandardOpenOption.APPEND)
                    : Files.newOutputStream(part)) {
                byte[] buffer = new byte[1024 * 1024];
                long done = base;
                progress.bytes(done, total);
                int n;
                while ((n = response.content().read(buffer)) >= 0) {
                    check(cancelled);
                    out.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                    done += n;
                    progress.bytes(done, total);
                }
            } finally {
                current = null;
            }
            return new Pass(total, HexFormat.of().formatHex(digest.digest()));
        }
    }

    /** Ganze Länge aus {@code Content-Range}, sonst aus der Länge der Antwort, sonst die von Hugging Face genannte. */
    private static long total(Response response, long base, long expectedSize) {
        String range = response.contentRange();
        if (range != null) {
            int slash = range.lastIndexOf('/');
            if (slash >= 0) {
                try {
                    return Long.parseLong(range.substring(slash + 1).strip());
                } catch (NumberFormatException ignored) {
                    // weiter mit der Länge der Antwort
                }
            }
        }
        return response.contentLength() >= 0 ? base + response.contentLength() : expectedSize;
    }

    private static void verify(HuggingFaceFile file, Path part, String sha256) throws IOException {
        if (!file.sha256().isEmpty()) {
            if (!file.sha256().equals(sha256)) {
                throw new ChecksumMismatch(file.fileName() + ": SHA-256 " + sha256 + ", erwartet " + file.sha256());
            }
        } else if (!file.gitOid().isEmpty()) {
            String actual = gitBlobSha1(part);
            if (!file.gitOid().equals(actual)) {
                throw new ChecksumMismatch(file.fileName() + ": Git-Prüfsumme " + actual + ", erwartet "
                        + file.gitOid());
            }
        }
    }

    /** Die SHA-1, unter der Git den Inhalt als Blob führt: über {@code "blob <Länge>\0"} und den Inhalt. */
    static String gitBlobSha1(Path file) throws IOException {
        MessageDigest digest = newDigest("SHA-1");
        digest.update(("blob " + Files.size(file) + "\0").getBytes(StandardCharsets.US_ASCII));
        digestFile(file, digest);
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Die SHA-256 einer Datei in Kleinbuchstaben. */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest("SHA-256");
        digestFile(file, digest);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void digestFile(Path file, MessageDigest digest) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, n);
            }
        }
    }

    private static MessageDigest newDigest(String algorithm) throws IOException {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(algorithm + " ist nicht verfügbar", e);
        }
    }

    private String text(String url) throws IOException {
        try (Response response = transport.get(URI.create(url), 0)) {
            if (response.status() / 100 != 2) {
                throw new IOException("HTTP " + response.status() + " von " + response.host());
            }
            return new String(response.content().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Kodiert jeden Abschnitt eines Pfads, die Schrägstriche bleiben. */
    static String encodePath(String path) {
        StringBuilder encoded = new StringBuilder();
        for (String segment : path.split("/", -1)) {
            if (encoded.length() > 0) {
                encoded.append('/');
            }
            encoded.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return encoded.toString();
    }

    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Download abgebrochen");
        }
    }

    private static final class ChecksumMismatch extends IOException {
        ChecksumMismatch(String message) {
            super(message);
        }
    }
}
