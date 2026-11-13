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
    private volatile PartRules rules = PartRules.ALL;

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

    /**
     * Welche am Inhalt erkannten Typen enthaltener Dateien eigene Dokumente werden ({@code export.attachments.
     * media-types}); ohne Angabe alle.
     */
    public DocumentExtractions documentTypes(java.util.function.Predicate<String> documentTypes) {
        this.rules = new PartRules(java.util.Optional.empty(), documentTypes);
        return this;
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
            Target target = new Target(writer);
            StructuredDocument document = read(asset, store, target);
            // Auch ein Teil an einer vorübergehenden Grenze verhindert das Ablegen: Behälter und Teile werden nur
            // zusammen neu gelesen (Punkt 5, Verzeichnis als Ganzes), der Teil würde sonst nie erneut gelesen.
            if (transientFailure(document).isPresent()) {
                return new StoredExtraction(key, document, 0, document.blocks(), document.chars(), Instant.now());
            }
            // Behälter und Teile werden zusammen gültig (AP7b).
            return writer.commit(new StructuredDocument(document.head(), document.outcome(), document.directory(),
                    target.blocks, target.chars));
        } catch (UncheckedIOException e) {
            throw new AssetStoreException("Dokument nicht lesbar", e.getCause());
        }
    }

    /** Liest das Original aus der abgelegten Datei oder einer temporären Kopie. */
    private StructuredDocument read(Asset asset, AssetStore store, StructureSink sink) {
        PartRules forAsset = rules.forContainer(asset.sha256());
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
            return structure.extract(original, asset.title(), asset.mediaType(), limits, sink, forAsset);
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
        Optional<Outcome> transientPart = transientFailure(extraction.document());
        if (transientPart.isPresent()) {
            throw failure(transientPart.get());
        }
        if (!usable(outcome)) {
            throw failure(outcome);
        }
        TextCollector text = new TextCollector(maxTextChars);
        collect(extraction, text);
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
            collect(known.get(), text);
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
                // Bis AP4 gehört der Text der Teile zum Text des Behälters (Suche wie bisher).
                return text::accept;
            }
        });
        if (transientFailure(document.outcome()) && document.chars() == 0
                || !usable(document.outcome())) {
            throw failure(document.outcome());
        }
        return text.result(asset, id(), version(), document.outcome(), now);
    }

    /**
     * Die Markdown-Ansicht eines abgelegten Dokuments ({@link MarkdownView}), fortlaufend aus den Abschnitten der
     * Extraktion geschrieben, ohne Längengrenze: aus derselben Extraktion wie {@link #text}. Liest das Original nur,
     * wenn noch keine Extraktion abgelegt ist. Nur das Dokument selbst; enthaltene Dateien sind eigene Dokumente mit
     * eigener Ansicht (AP4).
     *
     * @return der Status der Extraktion ({@code PARTIAL}: die Ansicht ist gekürzt)
     * @throws DocumentTextException wenn das Dokument keinen Text hat, wie bei {@link #text}
     */
    public Outcome markdown(Asset asset, AssetStore store, Appendable out) {
        StoredExtraction extraction = extract(asset, store);
        Outcome outcome = extraction.document().outcome();
        Optional<Outcome> transientPart = transientFailure(extraction.document());
        if (transientPart.isPresent()) {
            throw failure(transientPart.get());
        }
        if (!usable(outcome)) {
            throw failure(outcome);
        }
        MarkdownView view = new MarkdownView(out);
        if (extraction.sections() > 0) {
            try (Stream<TextSection> sections = extractions.sections(extraction.key())) {
                sections.forEach(section -> section.blocks().forEach(view::accept));
            }
        }
        view.finish();
        return outcome;
    }

    /**
     * Sammelt den Text des Behälters und danach den seiner Teile in der Reihenfolge des Verzeichnisses; inhaltsgleiche
     * Teile einmal. Übergang bis AP4 (Architekt 13.11.2026): Bis Index und Export die Teile als eigene Einträge
     * führen, enthält der Text des Behälters den Text seiner Teile, damit die Suche nichts verliert.
     */
    private void collect(StoredExtraction extraction, TextCollector text) {
        if (extraction.sections() > 0) {
            try (Stream<TextSection> sections = extractions.sections(extraction.key())) {
                var iterator = sections.iterator();
                while (iterator.hasNext() && text.open()) {
                    iterator.next().blocks().forEach(text::accept);
                }
            }
        }
        if (extraction.document().directory().isEmpty() || !text.open()) {
            return;
        }
        java.util.Set<String> stored = new java.util.HashSet<>();
        extractions.parts(extraction.key()).forEach(part -> stored.add(part.key()));
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (EmbeddedEntry entry : extraction.document().directory()) {
            String partKey = entry.id().storageKey();
            if (!text.open()) {
                return;
            }
            if (!stored.contains(partKey) || !seen.add(partKey)) {
                continue;
            }
            try (Stream<TextSection> sections = extractions.partSections(extraction.key(), partKey)) {
                var iterator = sections.iterator();
                while (iterator.hasNext() && text.open()) {
                    iterator.next().blocks().forEach(text::accept);
                }
            }
        }
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

        int length() {
            return text.length();
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
    public static String plain(Block block) {
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

    /** Der erste Status an einer vorübergehenden Grenze: beim Behälter oder bei einem seiner Teile. */
    private static Optional<Outcome> transientFailure(StructuredDocument document) {
        if (transientFailure(document.outcome())) {
            return Optional.of(document.outcome());
        }
        return document.directory().stream().map(EmbeddedEntry::outcome).filter(DocumentExtractions::transientFailure)
                .findFirst();
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

    /**
     * Der Strom des äußeren Dokuments geht in die Ablage, jeder Teil in seine eigene Ablage im selben Stand (AP7b);
     * schließen bestätigt nicht (das macht {@link #extract}). Inhaltsgleiche Teile desselben Behälters teilen sich die
     * Ablage: Der zweite wird gelesen (seine Kinder brauchen eigene Einträge), seine Blöcke werden nicht ein zweites
     * Mal geschrieben.
     */
    private static final class Target implements StructureSink {

        private final ExtractionWriter writer;
        private long blocks;
        private long chars;

        Target(ExtractionWriter writer) {
            this.writer = writer;
        }

        @Override
        public BlockSink document() {
            return block -> {
                Block numbered = renumbered(block, blocks);
                writer.accept(numbered);
                blocks++;
                chars += numbered.chars();
            };
        }

        @Override
        public PartSink part(EmbeddedEntry entry) {
            Optional<PartWriter> part = writer.part(entry.id().storageKey());
            if (part.isEmpty()) {
                return block -> {
                    // inhaltsgleich: schon abgelegt
                };
            }
            PartWriter target = part.get();
            return new PartSink() {
                private long index;

                @Override
                public void accept(Block block) {
                    target.accept(renumbered(block, index++));
                }

                @Override
                public void head(DocumentHead head) {
                    target.head(head);
                }

                @Override
                public void close() {
                    target.close();
                }
            };
        }
    }

    /** Derselbe Block an anderer Position. */
    static Block renumbered(Block block, long index) {
        if (block.index() == index) {
            return block;
        }
        return switch (block) {
            case Block.Heading b -> new Block.Heading(index, b.location(), b.links(), b.level(), b.text());
            case Block.Paragraph b -> new Block.Paragraph(index, b.location(), b.links(), b.text());
            case Block.ListItem b -> new Block.ListItem(index, b.location(), b.links(), b.ordered(), b.level(),
                    b.text());
            case Block.Code b -> new Block.Code(index, b.location(), b.links(), b.text(), b.language());
            case Block.Table b -> new Block.Table(index, b.location(), b.links(), b.cells(), b.continued());
        };
    }

    private static boolean usable(Outcome outcome) {
        return outcome.status() == ExtractionStatus.OK || outcome.status() == ExtractionStatus.PARTIAL;
    }
}
