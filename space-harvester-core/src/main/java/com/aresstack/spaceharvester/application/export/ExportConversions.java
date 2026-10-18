package com.aresstack.spaceharvester.application.export;

import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ConversionLimitExceededException;
import com.aresstack.spaceharvester.application.convert.ExportAssets;
import com.aresstack.spaceharvester.application.convert.ExportConverter;
import com.aresstack.spaceharvester.application.convert.ExportConverters;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Einmal beschaffen, beliebig oft konvertieren: erzeugt aus dem gespeicherten Datenbestand eines abgeschlossenen
 * Exports ({@code pages.ndjson}, {@code raw/}, {@code tree.json}) eine Datei in einem Zielformat, ohne Cache oder
 * Quelle zu lesen und ohne den Export neu zu starten. Dieselben Converter wie beim Export
 * ({@link ExportConverters}); der Datenbestand bleibt im Datenverzeichnis, die Datei landet im Ordner, den der
 * Aufrufer nennt (App: Ausgabeordner, Standard Downloads), oder im Strom des Aufrufers (REST).
 *
 * <p>Wie bei jeder Auslieferung eines Exports prüft der Aufrufer vorher die Leserechte je Seite
 * ({@link Exports#pageIds}); diese Klasse kennt nur den Auftraggeber.
 */
public class ExportConversions {

    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9_-]+");

    private final Jobs jobs;
    private final ExportArtifactWriter writer;
    private final ExportConverters converters;
    private final ExportAssetCollection images;

    public ExportConversions(Jobs jobs, ExportArtifactWriter writer, ExportConverters converters) {
        this(jobs, writer, converters, null);
    }

    /** @param images Bilder der Datenbestände oder {@code null}, dann ohne Bilder */
    public ExportConversions(Jobs jobs, ExportArtifactWriter writer, ExportConverters converters,
                             ExportAssetCollection images) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.converters = Objects.requireNonNull(converters, "converters");
        this.images = images;
    }

    /** Zielformate, die {@link #convert} kennt, etwa {@code json}, {@code markdown}, {@code docx}, {@code pdf}. */
    public List<String> formats() {
        return converters.formats().stream().sorted().toList();
    }

    /**
     * Ob und welcher Datenbestand zum eigenen Export vorliegt; nur dann bietet eine Oberfläche „Exportieren als …“ an.
     *
     * @return leer, solange der Export nicht abgeschlossen ist oder kein Datenbestand (mehr) vorliegt
     * @throws UnknownJobException wenn es keinen Export mit dieser ID für diesen Client gibt
     */
    public Optional<ExportDatasetInfo> dataset(String exportId, String owner) {
        JobStatus status = ownExport(exportId, owner);
        if (status.state() != JobState.COMPLETED) {
            return Optional.empty();
        }
        Optional<ExportArtifact> artifact = writer.find(exportId);
        Optional<List<String>> pageIds = writer.pageIds(exportId);
        if (artifact.isEmpty() || pageIds.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ExportDatasetInfo(exportId, status.sourceId(), status.partitionKey(),
                pageIds.get().size(), artifact.get().complete(),
                images == null ? ExportDatasetInfo.Images.NOT_COLLECTED : images.images(exportId), formats()));
    }

    /**
     * Schreibt die Datei in {@code directory}. Eine vorhandene Datei wird nie überschrieben; der Name bekommt dann
     * einen Zähler. Die Datei entsteht erst als {@code .part} und erscheint vollständig oder gar nicht.
     *
     * @throws UnknownJobException             wenn es keinen Export mit dieser ID für diesen Client gibt
     * @throws ExportNotConvertibleException   wenn der Export (noch) keinen Datenbestand hat
     * @throws ExportDatasetDamagedException   wenn der Datenbestand beschädigt ist
     * @throws com.aresstack.spaceharvester.application.sources.InvalidQueryException bei einem unbekannten Format
     */
    public ConvertedFile convert(String exportId, String owner, String format, Path directory) {
        Objects.requireNonNull(directory, "directory");
        ConversionInput input = input(exportId, owner);
        ExportConverter converter = converter(format);
        try {
            Files.createDirectories(directory);
            String base = UNSAFE.matcher(input.spaceKey()).replaceAll("_") + "-" + exportId.substring(0,
                    Math.min(8, exportId.length()));
            String extension = extension(converter.fileName());
            Path part = Files.createTempFile(directory, "." + base + "-", ".part");
            Hashing hashing;
            try {
                try (OutputStream file = Files.newOutputStream(part, StandardOpenOption.TRUNCATE_EXISTING)) {
                    hashing = new Hashing(file);
                    write(converter, input, hashing);
                }
                for (int attempt = 1; ; attempt++) {
                    Path target = directory.resolve(base + (attempt == 1 ? "" : "-" + attempt) + extension);
                    if (Files.exists(target)) {
                        continue;
                    }
                    try {
                        // Umbenennen im selben Ordner; ohne REPLACE_EXISTING überschreibt es nie.
                        Files.move(part, target);
                    } catch (FileAlreadyExistsException e) {
                        continue;
                    }
                    return new ConvertedFile(exportId, converter.format(), converter.mediaType(), target,
                            hashing.count, hashing.sha256());
                }
            } finally {
                Files.deleteIfExists(part);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Konvertierung des Exports " + exportId + " nach " + format
                    + " nicht schreibbar", e);
        }
    }

    /**
     * Schreibt die konvertierte Datei in {@code out}, etwa als Antwort eines REST-Aufrufs; der Aufrufer schließt den
     * Strom.
     *
     * @return Medientyp und Dateiname der Datei
     */
    public ExportConverter convert(String exportId, String owner, String format, OutputStream out) throws IOException {
        ConversionInput input = input(exportId, owner);
        ExportConverter converter = converter(format);
        write(converter, input, out);
        return converter;
    }

    /** Converter zu einem Format, etwa für Medientyp und Dateinamen vor dem Schreiben. */
    public ExportConverter converter(String format) {
        return converters.select(List.of(format)).get(0);
    }

    private ConversionInput input(String exportId, String owner) {
        JobStatus status = ownExport(exportId, owner);
        if (status.state() != JobState.COMPLETED) {
            throw new ExportNotConvertibleException(exportId, "Export ist nicht abgeschlossen");
        }
        ConversionInput dataset = writer.dataset(exportId).orElseThrow(() -> new ExportNotConvertibleException(
                exportId, "kein Datenbestand vorhanden"));
        if (images == null) {
            return dataset;
        }
        ExportAssets assets = images.assets(exportId);
        return new ConversionInput() {
            @Override
            public String sourceId() {
                return dataset.sourceId();
            }

            @Override
            public String spaceKey() {
                return dataset.spaceKey();
            }

            @Override
            public com.aresstack.spaceharvester.application.tree.PageTree tree() {
                return dataset.tree();
            }

            @Override
            public List<com.aresstack.spaceharvester.application.snapshot.PageHeader> headers() {
                return dataset.headers();
            }

            @Override
            public void forEachPage(java.util.function.Consumer<
                    com.aresstack.spaceharvester.application.convert.ConvertiblePage> action) {
                dataset.forEachPage(action);
            }

            @Override
            public ExportAssets assets() {
                return assets;
            }
        };
    }

    private void write(ExportConverter converter, ConversionInput input, OutputStream out) throws IOException {
        converter.convert(input, new Limited(out, converters.maxBytes()));
    }

    private JobStatus ownExport(String exportId, String owner) {
        JobStatus status = jobs.status(exportId, owner);
        if (status.type() != JobType.EXPORT) {
            throw new UnknownJobException(exportId);
        }
        return status;
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot);
    }

    /**
     * Eine erzeugte Datei.
     *
     * @param sha256 {@code sha256:<hex>} über die Datei
     */
    public record ConvertedFile(String exportId, String format, String mediaType, Path path, long bytes,
                                String sha256) {
    }

    private static final class Hashing extends DigestOutputStream {

        private long count;

        Hashing(OutputStream out) {
            super(out, sha256Digest());
        }

        @Override
        public void write(int b) throws IOException {
            super.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            super.write(b, off, len);
            count += len;
        }

        String sha256() {
            return "sha256:" + HexFormat.of().formatHex(getMessageDigest().digest());
        }

        private static MessageDigest sha256Digest() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Bricht ab, sobald mehr als erlaubt geschrieben würde; nichts wird abgeschnitten. */
    private static final class Limited extends FilterOutputStream {

        private final long maxBytes;
        private long written;

        Limited(OutputStream out, long maxBytes) {
            super(out);
            this.maxBytes = maxBytes;
        }

        @Override
        public void write(int b) throws IOException {
            require(1);
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            require(len);
            out.write(b, off, len);
        }

        @Override
        public void close() {
            // Der Aufrufer schließt den Strom.
        }

        private void require(long bytes) throws ConversionLimitExceededException {
            if (written + bytes > maxBytes) {
                throw new ConversionLimitExceededException("Datei", maxBytes);
            }
            written += bytes;
        }
    }
}
