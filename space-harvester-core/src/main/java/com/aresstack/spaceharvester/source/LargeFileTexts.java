package com.aresstack.spaceharvester.source;

import com.aresstack.spaceharvester.application.assets.Block;
import com.aresstack.spaceharvester.application.assets.BlockSink;
import com.aresstack.spaceharvester.application.assets.DocumentStructure;
import com.aresstack.spaceharvester.application.assets.EmbeddedEntry;
import com.aresstack.spaceharvester.application.assets.ExtractionLimits;
import com.aresstack.spaceharvester.application.assets.PartSink;
import com.aresstack.spaceharvester.application.assets.StructureSink;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Textauszug einer Datei über der Größengrenze des Caches (Angelo, 20.11.2026): Die Quelle lädt das Original in eine
 * temporäre Datei ({@link HarvestSource#downloadOriginal}), die Extraktion für große Dokumente (ADR 0026,
 * {@link DocumentStructure}) liest sie als Strom; übrig bleibt nur der Text, die Datei wird gelöscht. So braucht auch
 * eine Datei von einigen hundert MB nicht mehr Speicher als ihr Text. Dateien ohne lesbaren Text
 * ({@link LocalFilesTextProjector#readable}) lädt die Quelle gar nicht erst.
 */
public final class LargeFileTexts {

    private static final System.Logger LOG = System.getLogger(LargeFileTexts.class.getName());

    private final DocumentStructure structure;
    private final Supplier<ExtractionLimits> limits;
    private final Path temporary;

    /**
     * @param temporary Verzeichnis für die temporären Originale; wird bei Bedarf angelegt
     */
    public LargeFileTexts(DocumentStructure structure, Supplier<ExtractionLimits> limits, Path temporary) {
        this.structure = Objects.requireNonNull(structure, "structure");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.temporary = Objects.requireNonNull(temporary, "temporary");
    }

    /**
     * Der Textauszug der Datei; leer ohne lesbaren Text, wenn die Quelle das Original nicht als Datei liefern kann oder
     * die Extraktion nichts findet.
     *
     * @param maxBytes größte Datei, die dafür geladen wird
     * @throws com.aresstack.spaceharvester.application.sources.SourceAccessException wenn die Quelle nicht liefern kann
     */
    public Optional<String> text(HarvestSource source, PageMetadata metadata, long maxBytes) {
        if (source.readsOriginalInPlace()) {
            return source.readOriginalInPlace(metadata.pageId(), LocalFilesTextProjector::readable, maxBytes,
                    (original, actual, mediaType) -> {
                        requireSameRevision(source, metadata, actual);
                        return extract(source, original, actual, mediaType);
                    }).orElse(Optional.empty());
        }
        return downloadedText(source, metadata, maxBytes);
    }

    private Optional<String> downloadedText(HarvestSource source, PageMetadata metadata, long maxBytes) {
        Path file;
        try {
            Files.createDirectories(temporary);
            file = Files.createTempFile(temporary, "large-file-", ".part");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            Optional<String> mediaType = source.downloadOriginal(metadata.pageId(), file,
                    LocalFilesTextProjector::readable, maxBytes);
            if (mediaType.isEmpty()) {
                return Optional.empty();
            }
            return extract(source, file, metadata, mediaType.get());
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // Nur ein Rest im temporären Verzeichnis.
            }
        }
    }

    private Optional<String> extract(HarvestSource source, Path file, PageMetadata metadata, String mediaType) {
        ExtractionLimits current = limits.get();
        Collector text = new Collector(current.maxChars() > 0 ? current.maxChars()
                : TikaDocumentText.DEFAULT_MAX_CHARS);
        try {
            structure.extract(file, metadata.title(), mediaType, current, text);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.INFO, "Kein Textauszug einer Datei in {0} ({1})", source.id(),
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
        String extracted = text.text();
        return extracted.isBlank() ? Optional.empty() : Optional.of(extracted);
    }

    private static void requireSameRevision(HarvestSource source, PageMetadata expected, PageMetadata actual) {
        if (!expected.pageId().equals(actual.pageId())
                || !Objects.equals(expected.version(), actual.version())
                || !Objects.equals(expected.modifiedAt(), actual.modifiedAt())) {
            throw new com.aresstack.spaceharvester.application.sources.SourceAccessException(source.id(),
                    com.aresstack.spaceharvester.application.sources.SourceAccessException.Reason.UNAVAILABLE,
                    "Datei hat sich seit der Inventur geändert", null);
        }
    }

    /** Sammelt den Text der Blöcke mit leichtem Markdown, höchstens {@code maxChars} Zeichen. */
    private static final class Collector implements StructureSink {

        private final StringBuilder text = new StringBuilder();
        private final long maxChars;

        Collector(long maxChars) {
            this.maxChars = maxChars;
        }

        String text() {
            return text.toString().strip();
        }

        private void append(Block block) {
            if (text.length() >= maxChars) {
                return;
            }
            String line = switch (block) {
                case Block.Heading heading -> "#".repeat(Math.max(1, Math.min(6, heading.level()))) + " "
                        + heading.text();
                case Block.Paragraph paragraph -> paragraph.text();
                case Block.ListItem item -> "- " + item.text();
                case Block.Code code -> code.text();
                case Block.Table table -> table.cells().stream().map(Block.Cell::text)
                        .collect(Collectors.joining(" | "));
            };
            if (line.isBlank()) {
                return;
            }
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append(line, 0, (int) Math.min(line.length(), maxChars - text.length()));
        }

        @Override
        public BlockSink document() {
            return this::append;
        }

        @Override
        public PartSink part(EmbeddedEntry entry) {
            return this::append;
        }

        @Override
        public boolean restart() {
            text.setLength(0);
            return true;
        }
    }
}
