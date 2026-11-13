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

    /**
     * Das Original einer enthaltenen Datei als Strom (ADR 0026, Punkt 6, Abnahme 9): Der abgelegte Behälter wird unter
     * denselben Schutzgrenzen erneut gelesen und der Teil mit passendem Hash herausgegeben; es gibt keine zweite Kopie
     * in der Ablage. Der Strom muss geschlossen werden (er löscht seine temporäre Datei).
     *
     * @throws PartUnavailableException mit festem Code: Teil ohne Hash, Behälter nicht mehr vorhanden, Teil nicht
     *                                  gefunden
     */
    public InputStream openPart(Asset asset, AssetStore store, PartId id) {
        if (!(id instanceof PartId.Content content)) {
            throw new PartUnavailableException(PartUnavailableException.Reason.PART_WITHOUT_HASH, null);
        }
        Optional<InputStream> part;
        try {
            part = withOriginal(asset, store, original -> structure.openPart(original, asset.title(),
                    asset.mediaType(), limits, rules.forContainer(asset.sha256()), content));
        } catch (AssetStoreException e) {
            throw new PartUnavailableException(PartUnavailableException.Reason.CONTAINER_MISSING, e);
        }
        return part.orElseThrow(() -> new PartUnavailableException(PartUnavailableException.Reason.PART_NOT_FOUND,
                null));
    }

    /** Liest das Original aus der abgelegten Datei oder einer temporären Kopie. */
    private StructuredDocument read(Asset asset, AssetStore store, StructureSink sink) {
        PartRules forAsset = rules.forContainer(asset.sha256());
        return withOriginal(asset, store, original -> structure.extract(original, asset.title(), asset.mediaType(),
                limits, sink, forAsset));
    }

    /** Gibt die abgelegte Datei oder eine temporäre Kopie an {@code use}; die Kopie wird danach gelöscht. */
    private <T> T withOriginal(Asset asset, AssetStore store, java.util.function.Function<Path, T> use) {
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
            return use.apply(original);
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
        return withSource(result, asset);
    }

    /**
     * Status des Dokuments eines Anhangs: der der abgelegten Extraktion, mit dem, was die Quelle auslassen musste
     * (AP7c); leer, solange nichts abgelegt ist.
     */
    public Optional<Outcome> outcome(Asset asset) {
        return stored(asset.sha256()).map(extraction -> withSource(extraction.document().outcome(), asset));
    }

    /**
     * Ein abgelegter Abschnitt eines Dokuments für den Volltextindex (ADR 0026, AP4b).
     *
     * @param partId    Teilkennung ({@link PartId#value()}) der enthaltenen Datei, leer für das Dokument selbst
     * @param partTitle Anzeigename der enthaltenen Datei, leer für das Dokument selbst oder ohne Namen
     * @param section   Nummer des Abschnitts im Dokument ab 0
     * @param text      reiner Text des Abschnitts, Absätze mit Leerzeile getrennt
     */
    public record IndexSection(String partId, String partTitle, int section, String text) {

        public IndexSection {
            Objects.requireNonNull(partId, "partId");
            Objects.requireNonNull(partTitle, "partTitle");
            Objects.requireNonNull(text, "text");
        }

        @Override
        public String toString() {
            return "IndexSection[part=" + !partId.isEmpty() + ", section=" + section + ", chars=" + text.length() + "]";
        }
    }

    /**
     * Die abgelegten Abschnitte eines Dokuments und seiner lesbaren enthaltenen Dateien für den Volltextindex
     * (ADR 0026, AP4b, Punkte 8 und 12): zuerst die des Dokuments, dann je Teil in Verzeichnisreihenfolge,
     * inhaltsgleiche Teile einmal. Einzeln aus der Ablage gelesen, solange der Strom läuft, ohne Zeichengrenze; der
     * Strom muss geschlossen werden. Ein Teil mit Status {@code FAILED}, {@code UNSUPPORTED} oder {@code EXCLUDED}
     * hat keinen Abschnitt, {@code PARTIAL} den gelesenen Text.
     *
     * @return leer, wenn keine Extraktion abgelegt ist oder das Dokument keinen Text hat
     */
    public Optional<Stream<IndexSection>> indexSections(Asset asset) {
        Optional<StoredExtraction> known = stored(asset.sha256());
        if (known.isEmpty() || !usable(known.get().document().outcome())
                || transientFailure(known.get().document()).isPresent()) {
            return Optional.empty();
        }
        StoredExtraction extraction = known.get();
        Stream<IndexSection> own = extraction.sections() > 0
                ? extractions.sections(extraction.key()).map(section -> section("", "", section))
                : Stream.empty();
        Stream<IndexSection> parts = Stream.of(extraction).flatMap(this::partSections);
        return Optional.of(Stream.concat(own, parts).filter(section -> !section.text().isBlank()));
    }

    /** Die Abschnitte der lesbaren Teile, erst beim Lesen geöffnet. */
    private Stream<IndexSection> partSections(StoredExtraction extraction) {
        if (extraction.document().directory().isEmpty()) {
            return Stream.empty();
        }
        java.util.Set<String> stored = new java.util.HashSet<>();
        extractions.parts(extraction.key()).forEach(part -> stored.add(part.key()));
        java.util.Set<String> seen = new java.util.HashSet<>();
        return extraction.document().directory().stream()
                .filter(entry -> usable(entry.outcome()))
                .filter(entry -> stored.contains(entry.id().storageKey()) && seen.add(entry.id().storageKey()))
                .flatMap(entry -> extractions.partSections(extraction.key(), entry.id().storageKey())
                        .map(section -> section(entry.id().value(), entry.displayName().orElse(""), section)));
    }

    private static IndexSection section(String partId, String partTitle, TextSection section) {
        StringBuilder text = new StringBuilder();
        for (Block block : section.blocks()) {
            String piece = plain(block);
            if (piece.isBlank()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append(PARAGRAPH_BREAK);
            }
            text.append(piece);
        }
        return new IndexSection(partId, partTitle, section.index(), text.toString());
    }

    /**
     * Hat die Quelle beim Erzeugen der Datei Teile ausgelassen (ADR 0026, AP7c), ist ein vollständig gelesenes Dokument
     * {@code PARTIAL/entries-not-readable}, Umfang wie gelesen, gekürzt. Ein schon schlechterer Status behält seinen
     * ersten Grund (ein Grund je Status). Steht nicht in der Extraktion, die nach dem Hash geteilt wird.
     */
    public static Outcome withSource(Outcome outcome, Asset asset) {
        if (asset.partsWithoutFile() == 0 || outcome.status() != ExtractionStatus.OK) {
            return outcome;
        }
        return new Outcome(ExtractionStatus.PARTIAL, Optional.of(StatusReason.ENTRIES_NOT_READABLE), true,
                outcome.extent());
    }

    /** Der Text gilt als gekürzt, wenn die Quelle Teile auslassen musste; abgelegt wird er ohne diese Angabe. */
    static AssetText withSource(AssetText text, Asset asset) {
        if (asset.partsWithoutFile() == 0) {
            return text;
        }
        return new AssetText(text.sha256(), text.extractor(), text.version(), text.text(), true, text.createdAt(),
                text.outcome().map(outcome -> withSource(outcome, asset)));
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
            return withSource(text.result(asset, id(), version(), outcome, now), asset);
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
        return withSource(text.result(asset, id(), version(), document.outcome(), now), asset);
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
        // Die Teile stehen im Stand in Verzeichnisreihenfolge, inhaltsgleiche nur einmal; der Stand wird einmal gelesen.
        try (Stream<TextSection> sections = extractions.allPartSections(extraction.key())) {
            var iterator = sections.iterator();
            while (iterator.hasNext() && text.open()) {
                iterator.next().blocks().forEach(text::accept);
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
                    now, Optional.of(outcome));
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
            // Ein abgewiesener Teil (Status schon am Eintrag) belegt keine Ablage; ein lesbarer gleicher Teil füllt sie.
            Optional<PartWriter> part = usable(entry.outcome()) ? writer.part(entry.id().storageKey())
                    : Optional.empty();
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
