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
     * Die Extraktion dieser Bytes für Leser (ADR 0026, Punkt 11, Abnahme 8): die aktuelle, wenn sie Text hat, sonst die
     * jüngste lesbare einer anderen Extraktion, Version oder eines anderen Strukturmodells. Diese gilt als veraltet
     * ({@link #current}), bis ein Neulesen erfolgreich ist; ein gescheitertes Neulesen lässt sie stehen. Liest nichts.
     */
    public Optional<StoredExtraction> readable(String sha256) {
        Optional<StoredExtraction> current = stored(sha256);
        if (current.isPresent() && readable(current.get())) {
            return current;
        }
        ExtractionKey currentKey = key(sha256);
        return extractions.all(sha256).stream().filter(extraction -> !extraction.key().equals(currentKey))
                .filter(DocumentExtractions::readable).findFirst();
    }

    /** Ob die Extraktion zur eingestellten Extraktion und Version und zum aktuellen Strukturmodell gehört. */
    public boolean current(StoredExtraction extraction) {
        return extraction.key().equals(key(extraction.key().sha256()));
    }

    private static boolean readable(StoredExtraction extraction) {
        return usable(extraction.document().outcome()) && transientFailure(extraction.document()).isEmpty();
    }

    /**
     * Die Extraktion eines abgelegten Dokuments: aus der Ablage oder jetzt aus der Datei gelesen.
     *
     * @return die Extraktion samt Status; bei einer vorübergehenden Grenze nicht abgelegt
     * @throws AssetStoreException wenn das Original fehlt oder die Ablage nicht schreiben kann
     */
    public StoredExtraction extract(Asset asset, AssetStore store) {
        return extract(asset, store, false);
    }

    /**
     * @param fresh liest auch, wenn schon eine Extraktion abgelegt ist („Neu lesen“ auf Dokumente mit Status), und legt
     *              das Ergebnis nur ab, wenn es Text hat: Bis dahin gilt der bisherige Stand (ADR 0026, Punkt 11)
     */
    private StoredExtraction extract(Asset asset, AssetStore store, boolean fresh) {
        ExtractionKey key = key(asset.sha256());
        if (!fresh) {
            Optional<StoredExtraction> known = extractions.find(key);
            if (known.isPresent()) {
                return known.get();
            }
        }
        try (ExtractionWriter writer = extractions.writer(key)) {
            Target target = new Target(writer);
            StructuredDocument document = read(asset, store, target);
            // Auch ein Teil an einer vorübergehenden Grenze verhindert das Ablegen: Behälter und Teile werden nur
            // zusammen neu gelesen (Punkt 5, Verzeichnis als Ganzes), der Teil würde sonst nie erneut gelesen.
            if (transientFailure(document).isPresent() || fresh && !usable(document.outcome())) {
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

    /**
     * Die Originale der verlangten enthaltenen Dateien in einem Durchlauf durch den abgelegten Behälter (Auflage zu
     * AP4c): unter denselben Schutzgrenzen wie beim Lesen, jeder Teil als Strom aus einer temporären Datei, die nach
     * der Übergabe wegfällt. Was sich nicht mehr lesen lässt, wird übergangen.
     *
     * @throws PartUnavailableException {@code CONTAINER_MISSING}, wenn der Behälter nicht mehr vorliegt
     * @throws java.io.IOException      wenn {@code originals} scheitert
     */
    public void copyParts(Asset asset, AssetStore store, java.util.Set<PartId.Content> wanted,
                          DocumentStructure.PartOriginals originals) throws java.io.IOException {
        if (wanted.isEmpty()) {
            return;
        }
        java.io.IOException[] failure = new java.io.IOException[1];
        try {
            withOriginal(asset, store, original -> {
                try {
                    structure.copyParts(original, asset.title(), asset.mediaType(), limits,
                            rules.forContainer(asset.sha256()), wanted, originals);
                } catch (java.io.IOException e) {
                    // Fehler des Abnehmers, nicht der Ablage: unverändert an den Aufrufer.
                    failure[0] = e;
                }
                return null;
            });
        } catch (AssetStoreException e) {
            throw new PartUnavailableException(PartUnavailableException.Reason.CONTAINER_MISSING, e);
        }
        if (failure[0] != null) {
            throw failure[0];
        }
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
        return text(asset, store, now, false);
    }

    /**
     * Liest ein abgelegtes Dokument erneut, auch wenn schon eine Extraktion dieser Version abgelegt ist („Neu lesen“ auf
     * Dokumente mit Status, ADR 0026, Punkt 11), und ersetzt Extraktion und Text nur, wenn das Ergebnis Text hat; sonst
     * bleibt der bisherige Stand.
     *
     * @throws DocumentTextException wie {@link #text}; dann ist nichts ersetzt
     */
    public AssetText reread(Asset asset, AssetStore store, Instant now) {
        return text(asset, store, now, true);
    }

    /**
     * Entfernt die Extraktionen dieser Bytes aus anderen Extraktionen, Versionen und Strukturmodellen, nachdem das
     * Original erfolgreich gelesen ist (ADR 0026, Punkt 11: alte Stände erst danach).
     */
    public void removeOlderVersions(String sha256) {
        extractions.removeOthers(key(sha256));
    }

    private AssetText text(Asset asset, AssetStore store, Instant now, boolean fresh) {
        StoredExtraction extraction = extract(asset, store, fresh);
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
        // Abgelegt ohne Status: er steht in der abgelegten Extraktion (AP4a); nur ein importierter Text ohne
        // Extraktion trägt ihn selbst.
        store.putText(result.withOutcome(null));
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
     * Das abgelegte Dokument eines Anhangs mit seinen enthaltenen Dateien für den Export (ADR 0026, AP4c); liest das
     * Original nicht. Status mit dem, was die Quelle auslassen musste (AP7c). Ohne aktuelle Extraktion mit Text gilt
     * bis zum Neulesen die jüngste lesbare anderer Version ({@link #readable}, ADR 0026, Punkt 11); Extraktion und
     * Version stehen dann am Eintrag.
     *
     * @return leer, wenn keine Extraktion abgelegt ist
     */
    public Optional<DocumentExport> export(Asset asset, AssetStore store) {
        Optional<StoredExtraction> known = readable(asset.sha256()).or(() -> stored(asset.sha256()));
        if (known.isEmpty()) {
            return Optional.empty();
        }
        StoredExtraction extraction = known.get();
        StructuredDocument document = extraction.document();
        Outcome outcome = withSource(document.outcome(), asset);
        ExtractionKey key = extraction.key();
        Optional<ExtractionStore.PartSections> reader = document.directory().isEmpty() ? Optional.empty()
                : extractions.partSections(key);
        java.util.Map<String, StoredPart> storedParts = new java.util.HashMap<>();
        extractions.parts(key).forEach(part -> storedParts.put(part.key(), part));
        java.util.List<DocumentExport.Part> parts = new java.util.ArrayList<>();
        for (EmbeddedEntry entry : document.directory()) {
            StoredPart part = storedParts.get(entry.id().storageKey());
            boolean text = usable(entry.outcome()) && part != null && part.chars() > 0;
            parts.add(new DocumentExport.Part(entry.id().value(), entry.parent().map(PartId::value).orElse(null),
                    entry.position(), entry.depth(), entry.displayName().orElse(null), entry.mediaType().orElse(null),
                    entry.size().orElse(null), entry.sha256().orElse(null), entry.id().fileName(),
                    DocumentExport.Extraction.of(entry.outcome(), key), text,
                    entry.id() instanceof PartId.Content));
        }
        java.util.List<DocumentExport.Part> listed = java.util.List.copyOf(parts);
        boolean ownText = usable(document.outcome()) && extraction.sections() > 0 && extraction.chars() > 0;
        return Optional.of(new DocumentExport() {
            @Override
            public Extraction extraction() {
                return Extraction.of(outcome, key);
            }

            @Override
            public boolean hasText() {
                return ownText;
            }

            @Override
            public void writeText(java.io.OutputStream out) throws java.io.IOException {
                if (ownText) {
                    try (Stream<TextSection> sections = extractions.sections(key)) {
                        plainText(sections, out);
                    }
                }
            }

            @Override
            public void writeMarkdown(java.io.OutputStream out) throws java.io.IOException {
                if (ownText) {
                    try (Stream<TextSection> sections = extractions.sections(key)) {
                        markdownText(sections, out);
                    }
                }
            }

            @Override
            public java.util.List<Part> parts() {
                return listed;
            }

            @Override
            public void writeText(Part part, java.io.OutputStream out) throws java.io.IOException {
                if (part.hasText() && reader.isPresent()) {
                    try (Stream<TextSection> sections = reader.get().sections(storageKey(part))) {
                        plainText(sections, out);
                    }
                }
            }

            @Override
            public void writeMarkdown(Part part, java.io.OutputStream out) throws java.io.IOException {
                if (part.hasText() && reader.isPresent()) {
                    try (Stream<TextSection> sections = reader.get().sections(storageKey(part))) {
                        markdownText(sections, out);
                    }
                }
            }

            @Override
            public Optional<java.io.InputStream> openOriginal(Part part) {
                PartId id = PartId.parse(part.partId());
                if (!(id instanceof PartId.Content)) {
                    return Optional.empty();
                }
                return Optional.of(openPart(asset, store, id));
            }

            @Override
            public void copyOriginals(Originals originals) throws java.io.IOException {
                java.util.Map<PartId.Content, Part> wanted = new java.util.LinkedHashMap<>();
                for (Part part : listed) {
                    if (part.original() && PartId.parse(part.partId()) instanceof PartId.Content content) {
                        wanted.put(content, part);
                    }
                }
                copyParts(asset, store, wanted.keySet(), (id, in) -> originals.accept(wanted.get(id), in));
            }
        });
    }

    private static String storageKey(DocumentExport.Part part) {
        return PartId.parse(part.partId()).storageKey();
    }

    /** Reiner Text aus Abschnitten, Absätze mit Leerzeile getrennt, wie {@link #text}; kein erzeugter Text. */
    private static void plainText(Stream<TextSection> sections, java.io.OutputStream out) throws java.io.IOException {
        java.io.Writer writer = new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8);
        // Nicht schließen: den Strom schließt der Aufrufer.
        boolean first = true;
        var iterator = sections.iterator();
        while (iterator.hasNext()) {
            for (Block block : iterator.next().blocks()) {
                String piece = plain(block);
                if (piece.isBlank()) {
                    continue;
                }
                if (!first) {
                    writer.write(PARAGRAPH_BREAK);
                }
                writer.write(piece);
                first = false;
            }
        }
        writer.flush();
    }

    private static void markdownText(Stream<TextSection> sections, java.io.OutputStream out)
            throws java.io.IOException {
        java.io.Writer writer = new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8);
        // Nicht schließen: den Strom schließt der Aufrufer.
        MarkdownView view = new MarkdownView(writer);
        var iterator = sections.iterator();
        while (iterator.hasNext()) {
            iterator.next().blocks().forEach(view::accept);
        }
        view.finish();
        writer.flush();
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
     * hat keinen Text, nur einen Namenseintrag (Abschnitt 0, leerer Text), wenn er einen Anzeigenamen hat;
     * {@code PARTIAL} hat den gelesenen Text.
     *
     * <p>Ohne aktuelle Extraktion mit Text gilt die jüngste lesbare anderer Version ({@link #readable}).
     *
     * @return leer, wenn keine Extraktion abgelegt ist oder das Dokument keinen Text hat
     */
    public Optional<Stream<IndexSection>> indexSections(Asset asset) {
        return readable(asset.sha256()).map(this::indexSections);
    }

    /** Wie {@link #indexSections(Asset)} für eine Extraktion aus {@link #readable}. */
    public Stream<IndexSection> indexSections(StoredExtraction extraction) {
        Stream<IndexSection> own = extraction.sections() > 0
                ? extractions.sections(extraction.key()).map(section -> section("", "", section))
                        .filter(section -> !section.text().isBlank())
                : Stream.empty();
        Stream<IndexSection> parts = pieces(extraction).map(DocumentExtractions::indexSection)
                .filter(Objects::nonNull);
        return Stream.concat(own, parts);
    }

    /** Ein Teil ohne Text ist nur über seinen Namen auffindbar, nie mit erzeugtem Text (Status, Hinweis). */
    private static IndexSection indexSection(Piece piece) {
        String name = piece.entry().displayName().orElse("");
        if (piece.section() == null) {
            return name.isBlank() ? null : new IndexSection(piece.entry().id().value(), name, 0, "");
        }
        IndexSection section = section(piece.entry().id().value(), name, piece.section());
        return section.text().isBlank() ? null : section;
    }

    /**
     * Ein abgelegter Abschnitt eines Dokuments für die Analysen (ADR 0026, AP9a), mit seinen Blöcken.
     *
     * @param part    die enthaltene Datei, leer für das Dokument selbst
     * @param section der Abschnitt aus der Ablage
     */
    public record DocumentSection(Optional<EmbeddedEntry> part, TextSection section) {

        public DocumentSection {
            Objects.requireNonNull(part, "part");
            Objects.requireNonNull(section, "section");
        }
    }

    /**
     * Die abgelegten Abschnitte eines Dokuments und seiner lesbaren enthaltenen Dateien für die Analysen (ADR 0026,
     * AP9a; Schlüsselform Punkt 6): wie {@link #indexSections(StoredExtraction)} zuerst die des Dokuments, dann je Teil
     * in Verzeichnisreihenfolge, inhaltsgleiche Teile einmal, einzeln gelesen. Teile mit Status {@code FAILED},
     * {@code UNSUPPORTED} oder {@code EXCLUDED} fehlen ganz, {@code PARTIAL} hat den gelesenen Text. Der Strom muss
     * geschlossen werden.
     */
    public Stream<DocumentSection> documentSections(StoredExtraction extraction) {
        Stream<DocumentSection> own = extraction.sections() > 0
                ? extractions.sections(extraction.key())
                        .map(section -> new DocumentSection(Optional.empty(), section))
                : Stream.empty();
        Stream<DocumentSection> parts = pieces(extraction).filter(piece -> piece.section() != null)
                .map(piece -> new DocumentSection(Optional.of(piece.entry()), piece.section()));
        return Stream.concat(own, parts);
    }

    /**
     * Welche Dokumente {@link #documentSections} liefern würde, ohne einen Abschnitt zu lesen (nur das Manifest): das
     * Dokument selbst ({@link Optional#empty()}), wenn es Abschnitte hat, dann je lesbarem Teil mit abgelegtem Text
     * einer, inhaltsgleiche einmal.
     */
    public java.util.List<Optional<EmbeddedEntry>> documents(StoredExtraction extraction) {
        java.util.List<Optional<EmbeddedEntry>> documents = new java.util.ArrayList<>();
        if (extraction.sections() > 0) {
            documents.add(Optional.empty());
        }
        java.util.List<EmbeddedEntry> directory = extraction.document().directory();
        if (directory.isEmpty()) {
            return documents;
        }
        Optional<ExtractionStore.PartSections> reader = extractions.partSections(extraction.key());
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (EmbeddedEntry entry : directory) {
            String key = entry.id().storageKey();
            if (usable(entry.outcome()) && reader.isPresent() && reader.get().keys().contains(key) && seen.add(key)) {
                documents.add(Optional.of(entry));
            }
        }
        return documents;
    }

    /** Ein Teil mit einem seiner Abschnitte, oder ohne Abschnitt ({@code null}), wenn er keinen Text hat. */
    private record Piece(EmbeddedEntry entry, TextSection section) {
    }

    /**
     * Die Abschnitte der Teile, einer nach dem anderen gelesen: ohne {@code flatMap}, dessen Iterator alle Abschnitte
     * eines Elements puffern würde; das Manifest wird einmal gelesen. Ein Teil ohne lesbaren Text erscheint einmal
     * ohne Abschnitt.
     */
    private Stream<Piece> pieces(StoredExtraction extraction) {
        java.util.List<EmbeddedEntry> directory = extraction.document().directory();
        if (directory.isEmpty()) {
            return Stream.empty();
        }
        Optional<ExtractionStore.PartSections> reader = extractions.partSections(extraction.key());
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.List<Stream<TextSection>> opened = new java.util.ArrayList<>();
        java.util.Iterator<Piece> pieces = new java.util.Iterator<>() {
            private int entry;
            private Piece next;
            private java.util.Iterator<TextSection> current;
            private EmbeddedEntry currentEntry;

            @Override
            public boolean hasNext() {
                while (next == null) {
                    if (current != null && current.hasNext()) {
                        next = new Piece(currentEntry, current.next());
                        continue;
                    }
                    current = null;
                    if (entry >= directory.size()) {
                        return false;
                    }
                    EmbeddedEntry candidate = directory.get(entry++);
                    if (!usable(candidate.outcome())) {
                        next = new Piece(candidate, null);
                        continue;
                    }
                    String key = candidate.id().storageKey();
                    if (reader.isPresent() && reader.get().keys().contains(key) && seen.add(key)) {
                        Stream<TextSection> stream = reader.get().sections(key);
                        opened.add(stream);
                        current = stream.iterator();
                        currentEntry = candidate;
                    }
                }
                return true;
            }

            @Override
            public Piece next() {
                if (!hasNext()) {
                    throw new java.util.NoSuchElementException();
                }
                Piece result = next;
                next = null;
                return result;
            }
        };
        return java.util.stream.StreamSupport.stream(java.util.Spliterators.spliteratorUnknownSize(pieces,
                java.util.Spliterator.ORDERED | java.util.Spliterator.NONNULL), false)
                .onClose(() -> opened.forEach(Stream::close));
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
                text.outcome().map(outcome -> withSource(outcome, asset)), text.stale());
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
