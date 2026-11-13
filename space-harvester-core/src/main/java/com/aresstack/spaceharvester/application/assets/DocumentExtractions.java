package com.aresstack.spaceharvester.application.assets;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Liest abgelegte Dokumente strukturiert und legt das Ergebnis in Abschnitten ab (ADR 0026, Stufe 2a, AP3b): Das
 * Original wird als Datei gelesen ({@link AssetStore#file}), nie als Byte-Array, und der Text geht fortlaufend in die
 * {@link ExtractionStore}. Ohne Größengrenze; es gelten die Schutzgrenzen ({@link ExtractionLimits}).
 *
 * <p>Für die heutigen Verbraucher (Volltextindex, Export, REST) entsteht daraus ein {@link AssetText} unter dieser
 * Extraktion, höchstens {@code maxTextChars} Zeichen lang und dann als gekürzt markiert, bis sie abschnittsweise lesen
 * (AP4). Eine Extraktion an einer vorübergehenden Grenze (Zeit, Platz) wird nicht abgelegt, damit ein späterer Lauf
 * sie erneut versucht; jede andere bleibt samt Status liegen und wird nicht bei jeder Ernte erneut gelesen.
 */
public final class DocumentExtractions {

    /** So viele Zeichen führt der Volltextindex je Dokument, bis AP4 abschnittsweise liest. */
    public static final int DEFAULT_MAX_TEXT_CHARS = 1_000_000;
    private static final String PARAGRAPH_BREAK = "\n\n";

    private final DocumentStructure structure;
    private final ExtractionStore extractions;
    private final ExtractionLimits limits;
    private final int maxTextChars;

    public DocumentExtractions(DocumentStructure structure, ExtractionStore extractions, ExtractionLimits limits) {
        this(structure, extractions, limits, DEFAULT_MAX_TEXT_CHARS);
    }

    public DocumentExtractions(DocumentStructure structure, ExtractionStore extractions, ExtractionLimits limits,
                               int maxTextChars) {
        this.structure = Objects.requireNonNull(structure, "structure");
        this.extractions = Objects.requireNonNull(extractions, "extractions");
        this.limits = Objects.requireNonNull(limits, "limits");
        if (maxTextChars < 1) {
            throw new IllegalArgumentException("maxTextChars muss positiv sein");
        }
        this.maxTextChars = maxTextChars;
    }

    /** Kennung der Extraktion, unter der auch der {@link AssetText} liegt. */
    public String id() {
        return structure.id();
    }

    /** Version der Extraktion, unter der auch der {@link AssetText} liegt. */
    public String version() {
        return structure.version();
    }

    /** Der Schlüssel der Extraktion eines Inhalts. */
    public ExtractionKey key(String sha256) {
        return ExtractionKey.of(sha256, structure);
    }

    /** Die abgelegte Extraktion dieser Bytes; liest nichts. */
    public Optional<StoredExtraction> stored(String sha256) {
        return extractions.find(key(sha256));
    }

    /**
     * Die Extraktion eines abgelegten Dokuments: aus der Ablage oder jetzt aus der Datei gelesen.
     *
     * @return die Extraktion samt Status; bei einer vorübergehenden Grenze nicht abgelegt
     * @throws AssetStoreException wenn das Original fehlt oder die Ablage nicht schreiben kann
     */
    public StoredExtraction extract(Asset asset, AssetStore store) {
        ExtractionKey key = key(asset.sha256());
        Optional<StoredExtraction> known = extractions.find(key);
        if (known.isPresent()) {
            return known.get();
        }
        try (ExtractionWriter writer = extractions.writer(key)) {
            StructuredDocument document = read(asset, store, new Target(writer));
            if (transientFailure(document.outcome())) {
                return new StoredExtraction(key, document, 0, document.blocks(), document.chars(), Instant.now());
            }
            return writer.commit(document);
        } catch (UncheckedIOException e) {
            throw new AssetStoreException("Dokument nicht lesbar", e.getCause());
        }
    }

    /** Liest das Original aus der abgelegten Datei oder einer temporären Kopie. */
    private StructuredDocument read(Asset asset, AssetStore store, StructureSink sink) {
        Optional<Path> file = store.file(asset);
        Path temporary = null;
        try {
            Path original;
            if (file.isPresent()) {
                original = file.get();
            } else {
                temporary = Files.createTempFile("document-", ".tmp");
                try (InputStream in = store.open(asset)) {
                    Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
                }
                original = temporary;
            }
            return structure.extract(original, asset.title(), asset.mediaType(), limits, sink);
        } catch (IOException e) {
            throw new AssetStoreException("Dokument nicht lesbar", e);
        } catch (UncheckedIOException e) {
            throw new AssetStoreException("Dokument nicht lesbar", e.getCause());
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    // nichts weiter: eine temporäre Datei
                }
            }
        }
    }

    /**
     * Der Text eines abgelegten Dokuments für die heutigen Verbraucher: Absätze mit Leerzeile getrennt, höchstens
     * {@code maxTextChars} Zeichen; gekürzt, wenn die Extraktion gekürzt ist oder mehr Text hat. Legt Extraktion und
     * Text ab.
     *
     * @throws DocumentTextException wenn das Dokument keinen Text hat, mit dem passenden bisherigen Grund; an einer
     *                               vorübergehenden Grenze {@code TIMEOUT}, ohne etwas abzulegen
     */
    public AssetText text(Asset asset, AssetStore store, Instant now) {
        StoredExtraction extraction = extract(asset, store);
        Outcome outcome = extraction.document().outcome();
        // Vorher prüfen: Eine nicht abgelegte Extraktion hat keine Abschnitte, ihr Text wäre leer.
        if (transientFailure(outcome) || !usable(outcome)) {
            throw failure(outcome);
        }
        TextCollector text = new TextCollector(maxTextChars);
        if (extraction.sections() > 0) {
            try (Stream<TextSection> sections = extractions.sections(extraction.key())) {
                var iterator = sections.iterator();
                while (iterator.hasNext() && text.open()) {
                    iterator.next().blocks().forEach(text::accept);
                }
            }
        }
        AssetText result = text.result(asset, id(), version(), outcome, now);
        store.putText(result);
        return result;
    }

    /**
     * Wie {@link #text}, legt aber nichts ab (Abruf ohne Recht zum Ablegen): eine abgelegte Extraktion wird gelesen,
     * sonst wird die Datei gelesen und der Text nur gesammelt.
     */
    public AssetText peek(Asset asset, AssetStore store, Instant now) {
        Optional<StoredExtraction> known = stored(asset.sha256());
        if (known.isPresent()) {
            Outcome outcome = known.get().document().outcome();
            if (!usable(outcome)) {
                throw failure(outcome);
            }
            TextCollector text = new TextCollector(maxTextChars);
            if (known.get().sections() > 0) {
                try (Stream<TextSection> sections = extractions.sections(known.get().key())) {
                    var iterator = sections.iterator();
                    while (iterator.hasNext() && text.open()) {
                        iterator.next().blocks().forEach(text::accept);
                    }
                }
            }
            return text.result(asset, id(), version(), outcome, now);
        }
        TextCollector text = new TextCollector(maxTextChars);
        StructuredDocument document = read(asset, store, new StructureSink() {
            @Override
            public BlockSink document() {
                return text::accept;
            }

            @Override
            public PartSink part(EmbeddedEntry entry) {
                return block -> {
                };
            }
        });
        if (transientFailure(document.outcome()) && document.chars() == 0 || !usable(document.outcome())) {
            throw failure(document.outcome());
        }
        return text.result(asset, id(), version(), document.outcome(), now);
    }

    private static boolean usable(Outcome outcome) {
        return outcome.status() == ExtractionStatus.OK || outcome.status() == ExtractionStatus.PARTIAL;
    }

    /** Sammelt reinen Text bis zur Grenze. */
    private static final class TextCollector {

        private final int max;
        private final StringBuilder text = new StringBuilder();
        private boolean cut;

        TextCollector(int max) {
            this.max = max;
        }

        boolean open() {
            return !cut;
        }

        void accept(Block block) {
            if (cut) {
                return;
            }
            String piece = plain(block);
            if (piece.isEmpty()) {
                return;
            }
            int separator = text.isEmpty() ? 0 : PARAGRAPH_BREAK.length();
            if (text.length() + separator + piece.length() > max) {
                int room = max - text.length() - separator;
                if (room > 0) {
                    text.append(separator > 0 ? PARAGRAPH_BREAK : "").append(piece, 0, room);
                }
                cut = true;
                return;
            }
            text.append(separator > 0 ? PARAGRAPH_BREAK : "").append(piece);
        }

        AssetText result(Asset asset, String extractor, String version, Outcome outcome, Instant now) {
            return new AssetText(asset.sha256(), extractor, version, text.toString(), cut || outcome.truncated(),
                    now);
        }
    }

    /** Reiner Text eines Blocks; Tabellenzellen je Zeile mit Tabulator. */
    static String plain(Block block) {
        return switch (block) {
            case Block.Heading heading -> heading.text();
            case Block.Paragraph paragraph -> paragraph.text();
            case Block.ListItem item -> item.text();
            case Block.Code code -> code.text();
            case Block.Table table -> {
                StringBuilder rows = new StringBuilder();
                long row = -1;
                for (Block.Cell cell : table.cells()) {
                    if (cell.row() != row) {
                        if (row >= 0) {
                            rows.append('\n');
                        }
                        row = cell.row();
                    } else {
                        rows.append('\t');
                    }
                    rows.append(cell.text());
                }
                yield rows.toString();
            }
        };
    }

    private static boolean transientFailure(Outcome outcome) {
        return outcome.reason().filter(reason -> reason == StatusReason.TIMEOUT
                || reason == StatusReason.TEMP_SPACE_LOW).isPresent();
    }

    /** Übersetzt den Status in die bisherigen Gründe von {@link DocumentText}, für REST und Protokoll. */
    private static DocumentTextException failure(Outcome outcome) {
        StatusReason reason = outcome.reason().orElse(StatusReason.CORRUPT);
        DocumentTextException.Reason old = switch (reason) {
            case UNSUPPORTED_FORMAT, NO_TEXT, EMPTY, MEDIA_TYPE_EXCLUDED -> DocumentTextException.Reason.UNSUPPORTED;
            case TOO_LARGE, MEMORY_LIMIT -> DocumentTextException.Reason.TOO_LARGE;
            case TIMEOUT, TEMP_SPACE_LOW -> DocumentTextException.Reason.TIMEOUT;
            default -> DocumentTextException.Reason.FAILED;
        };
        return new DocumentTextException(old, "Kein Text (" + reason.code() + ")", null);
    }

    /** Der Strom des äußeren Dokuments geht in die Ablage; schließen bestätigt nicht (das macht {@link #extract}). */
    private record Target(ExtractionWriter writer) implements StructureSink {

        @Override
        public BlockSink document() {
            return writer::accept;
        }

        @Override
        public PartSink part(EmbeddedEntry entry) {
            // Enthaltene Dateien liefert der Adapter erst mit AP7.
            return block -> {
            };
        }
    }
}
