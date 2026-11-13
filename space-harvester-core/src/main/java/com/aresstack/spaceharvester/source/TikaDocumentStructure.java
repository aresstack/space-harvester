package com.aresstack.spaceharvester.source;

import com.aresstack.spaceharvester.application.assets.Block;
import com.aresstack.spaceharvester.application.assets.BlockSink;
import com.aresstack.spaceharvester.application.assets.DocumentHead;
import com.aresstack.spaceharvester.application.assets.DocumentStructure;
import com.aresstack.spaceharvester.application.assets.EmbeddedEntry;
import com.aresstack.spaceharvester.application.assets.ExtractionLimits;
import com.aresstack.spaceharvester.application.assets.ExtractionStatus;
import com.aresstack.spaceharvester.application.assets.ExtractionStore;
import com.aresstack.spaceharvester.application.assets.Link;
import com.aresstack.spaceharvester.application.assets.Location;
import com.aresstack.spaceharvester.application.assets.Outcome;
import com.aresstack.spaceharvester.application.assets.PartId;
import com.aresstack.spaceharvester.application.assets.PartIdNumbering;
import com.aresstack.spaceharvester.application.assets.PartRules;
import com.aresstack.spaceharvester.application.assets.PartSink;
import com.aresstack.spaceharvester.application.assets.ReadExtent;
import com.aresstack.spaceharvester.application.assets.StatusReason;
import com.aresstack.spaceharvester.application.assets.StructureSink;
import com.aresstack.spaceharvester.application.assets.StructuredDocument;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.ParsingEmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Message;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MediaTypeRegistry;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Eigener Tika-Adapter des Core (ADR 0026, Stufe 2a, AP3): liest das abgelegte Original aus der Datei, nie als
 * Byte-Array, und gibt den Text fortlaufend als Absatz-Blöcke an den Empfänger, ohne ihn als Ganzes zu halten. Damit
 * entfallen die 20 MiB je Datei und die 1 Mio. Zeichen des bisherigen Wegs ({@link TikaDocumentText}).
 *
 * <ul>
 *   <li>Blöcke: in dieser Stufe nur {@link Block.Paragraph}; Überschriften, Listen, Tabellen und Fundstellen folgen
 *       mit AP6. Kein Block wird größer als {@code maxBlockChars} (Standard: Größe eines Abschnitts der Ablage); ein
 *       längerer Absatz wird an Leerraum geteilt.</li>
 *   <li>Enthaltene Dateien (Mail-Anhänge, Archiveinträge) laufen vorerst wie bisher in den Text des Behälters; eigene
 *       Dokumente werden sie mit AP7.</li>
 *   <li>Schutzgrenzen ({@link ExtractionLimits}): Größe je Datei, Schranke {@code memory-limit} je Format, Zeichen je
 *       Dokument, Verhältnis Zeichen zu Dateigröße, Laufzeit ohne Fortschritt und insgesamt, Platzreserve für
 *       temporäre Dateien. Jede erreichte Grenze ist ein {@link Outcome}; was bis dahin gelesen ist, bleibt.</li>
 *   <li>Texterkennung (OCR) ist abgeschaltet: Die Tika-Konfiguration schließt den Tesseract-Parser aus.</li>
 *   <li>Gleichzeitig laufen höchstens {@code parallel} Extraktionen; ein hängender Parser hält seinen Platz, bis er
 *       endet, damit nie mehr Threads in Tika stecken.</li>
 * </ul>
 *
 * Meldungen und Protokoll nennen weder Dateinamen noch Inhalte.
 */
public final class TikaDocumentStructure implements DocumentStructure {

    private static final System.Logger LOG = System.getLogger(TikaDocumentStructure.class.getName());

    public static final String ID = "tika";
    /** Tika-Linie und Regeln dieses Adapters; steigt, wenn sich die gelesenen Blöcke ändern können. */
    public static final String VERSION = "3.3-s11";

    /** Tika-Konfiguration: alle Standard-Parser außer der Texterkennung. */
    private static final String CONFIG = """
            <?xml version="1.0" encoding="UTF-8"?>
            <properties>
              <parsers>
                <parser class="org.apache.tika.parser.DefaultParser">
                  <parser-exclude class="org.apache.tika.parser.ocr.TesseractOCRParser"/>
                  <parser-exclude class="org.apache.tika.parser.mail.RFC822Parser"/>
                </parser>
                <parser class="org.apache.tika.parser.mail.RFC822Parser">
                  <params>
                    <param name="extractAllAlternatives" type="bool">true</param>
                  </params>
                </parser>
              </parsers>
            </properties>
            """;
    private static final Set<String> BLOCK_ELEMENTS = Set.of("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li",
            "dt", "dd", "tr", "pre", "blockquote", "table", "ul", "ol", "dl", "section", "article", "body");
    private static final Set<String> MAIL_TYPES = Set.of("message/rfc822", "application/vnd.ms-outlook");
    /** Mails und Archive werden geöffnet, auch wenn ihr Typ kein eigenes Dokument wird. */
    private static final Set<String> CONTAINER_TYPES = Set.of("message/rfc822", "application/vnd.ms-outlook",
            "application/zip", "application/x-zip-compressed", "application/x-tar", "application/gzip",
            "application/x-gzip", "application/x-7z-compressed", "application/x-rar-compressed", "application/vnd.rar",
            "application/x-bzip2", "application/x-xz", "application/x-compress", "application/java-archive");
    /** So oft prüft das Auspacken die Platzreserve. */
    private static final long SPACE_CHECK_BYTES = 8L * 1024 * 1024;
    private static final long POLL_MILLIS = 50;
    private static final int MAX_LINKS = 1_000;
    private static final int MAX_TABLE_CELLS = 10_000;
    private static final int MAX_LABEL_CHARS = 1_000;

    private final int maxBlockChars;
    private final Semaphore slots;
    private final Parser parseWith;
    /** Tika lädt beim ersten Gebrauch, nicht beim Start. */
    private volatile AutoDetectParser parser;

    /** Mit {@link ExtractionLimits#DEFAULTS} gleichzeitigen Läufen und Blöcken bis zur Abschnittsgröße. */
    public TikaDocumentStructure() {
        this(ExtractionLimits.DEFAULTS.parallel(), ExtractionStore.DEFAULT_SECTION_CHARS);
    }

    /**
     * @param parallel      gleichzeitige Läufe, mindestens 1 ({@link ExtractionLimits#parallel()})
     * @param maxBlockChars größter Block in Zeichen, mindestens 1
     */
    public TikaDocumentStructure(int parallel, int maxBlockChars) {
        this(parallel, maxBlockChars, null);
    }

    /** Für Tests: liest mit {@code parseWith} statt mit Tikas Erkennung (etwa ein hängender Parser). */
    TikaDocumentStructure(int parallel, int maxBlockChars, Parser parseWith) {
        if (parallel < 1 || maxBlockChars < 1) {
            throw new IllegalArgumentException("Grenzen müssen positiv sein");
        }
        this.slots = new Semaphore(parallel);
        this.maxBlockChars = maxBlockChars;
        this.parseWith = parseWith;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public StructuredDocument extract(Path original, String title, String mediaType, ExtractionLimits limits,
                                      StructureSink sink) {
        return extract(original, title, mediaType, limits, sink, PartRules.ALL);
    }

    @Override
    public StructuredDocument extract(Path original, String title, String mediaType, ExtractionLimits limits,
                                      StructureSink sink, PartRules rules) {
        return extract(original, title, mediaType, limits, sink, rules, null, null, null);
    }

    /**
     * Ein Durchlauf ohne abgelegte Blöcke: jeder verlangte Teil geht, sobald er vollständig ausgepackt und gehasht ist,
     * aus seiner temporären Datei an {@code originals}; sind alle übergeben, endet das Auspacken.
     */
    @Override
    public void copyParts(Path original, String title, String mediaType, ExtractionLimits limits, PartRules rules,
                          java.util.Set<PartId.Content> wanted, PartOriginals originals) throws IOException {
        Objects.requireNonNull(originals, "originals");
        if (wanted.isEmpty()) {
            return;
        }
        Delivery delivery = new Delivery(new java.util.HashSet<>(wanted), originals);
        StructureSink discard = new StructureSink() {
            @Override
            public BlockSink document() {
                return block -> {
                };
            }

            @Override
            public PartSink part(EmbeddedEntry entry) {
                return block -> {
                };
            }
        };
        extract(original, title, mediaType, limits, discard, rules, null, null, delivery);
        if (delivery.failure != null) {
            throw delivery.failure;
        }
    }

    /** Verlangte Teile eines Durchlaufs und ihr Abnehmer; {@code failure}, wenn der Abnehmer scheiterte. */
    private static final class Delivery {
        final java.util.Set<PartId.Content> remaining;
        final PartOriginals originals;
        IOException failure;

        Delivery(java.util.Set<PartId.Content> remaining, PartOriginals originals) {
            this.remaining = remaining;
            this.originals = originals;
        }
    }

    /**
     * Liest den Behälter erneut unter denselben Grenzen, ohne Blöcke abzulegen, und behält die temporäre Datei des
     * gesuchten Teils; das Auspacken endet dort.
     */
    @Override
    public Optional<InputStream> openPart(Path original, String title, String mediaType, ExtractionLimits limits,
                                          PartRules rules, PartId.Content id) {
        Objects.requireNonNull(id, "id");
        java.util.concurrent.atomic.AtomicReference<Path> found = new java.util.concurrent.atomic.AtomicReference<>();
        StructureSink discard = new StructureSink() {
            @Override
            public BlockSink document() {
                return block -> {
                };
            }

            @Override
            public PartSink part(EmbeddedEntry entry) {
                return block -> {
                };
            }
        };
        extract(original, title, mediaType, limits, discard, rules, id, found, null);
        Path file = found.get();
        if (file == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new java.io.FilterInputStream(Files.newInputStream(file)) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        Files.deleteIfExists(file);
                    }
                }
            });
        } catch (IOException e) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // nichts weiter: eine temporäre Datei
            }
            return Optional.empty();
        }
    }

    private StructuredDocument extract(Path original, String title, String mediaType, ExtractionLimits limits,
                                       StructureSink sink, PartRules rules, PartId.Content wanted,
                                       java.util.concurrent.atomic.AtomicReference<Path> found, Delivery delivery) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(sink, "sink");
        BlockSink target = sink.document();
        try {
            long size;
            try {
                size = Files.size(original);
            } catch (IOException e) {
                return empty(Outcome.of(ExtractionStatus.FAILED, StatusReason.CORRUPT));
            }
            if (size == 0) {
                return empty(Outcome.of(ExtractionStatus.UNSUPPORTED, StatusReason.EMPTY));
            }
            if (ExtractionLimits.exceeds(size, limits.maxFileBytes())) {
                return empty(Outcome.of(ExtractionStatus.EXCLUDED, StatusReason.TOO_LARGE));
            }
            AutoDetectParser tika = parser();
            // Der gemeldete Typ wird hier billig geprüft, der erkannte erst im Lauf (unter Platz- und Zeitgrenze).
            if (ExtractionLimits.exceeds(size, limits.memoryLimit(mediaType))) {
                return empty(Outcome.of(ExtractionStatus.EXCLUDED, StatusReason.MEMORY_LIMIT));
            }
            if (limits.tempSpaceReserve() > 0 && usableTempSpace() < limits.tempSpaceReserve()) {
                return empty(Outcome.of(ExtractionStatus.FAILED, StatusReason.TEMP_SPACE_LOW));
            }
            if (!supported(tika, mediaType)) {
                return empty(Outcome.of(ExtractionStatus.UNSUPPORTED, StatusReason.UNSUPPORTED_FORMAT));
            }
            try {
                // Auch das Warten auf einen Platz endet an der Zeitgrenze: Hängende Läufe behalten ihren Platz.
                long wait = limits.totalTimeout().isZero() ? limits.idleTimeout().toNanos()
                        : limits.totalTimeout().toNanos();
                boolean acquired = true;
                if (wait > 0) {
                    acquired = slots.tryAcquire(wait, TimeUnit.NANOSECONDS);
                } else {
                    slots.acquire();
                }
                if (!acquired) {
                    return empty(Outcome.of(ExtractionStatus.FAILED, StatusReason.TIMEOUT));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return empty(Outcome.of(ExtractionStatus.FAILED, StatusReason.TIMEOUT));
            }
            Reader reader = new Reader(tika, original, title, mediaType, size, limits, sink, target, rules);
            reader.wanted = wanted;
            reader.found = found;
            reader.delivery = delivery;
            return run(reader, limits);
        } finally {
            target.close();
        }
    }

    /** Liest in einem eigenen Thread und wacht über Laufzeit und Fortschritt. */
    private StructuredDocument run(Reader reader, ExtractionLimits limits) {
        Thread worker = new Thread(() -> {
            try {
                reader.read();
            } finally {
                slots.release();
            }
        }, "document-structure");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (RuntimeException | OutOfMemoryError e) {
            slots.release();
            return empty(Outcome.of(ExtractionStatus.FAILED, StatusReason.MEMORY_LIMIT));
        }
        long idle = limits.idleTimeout().toNanos();
        long total = limits.totalTimeout().toNanos();
        try {
            while (true) {
                worker.join(POLL_MILLIS);
                if (!worker.isAlive()) {
                    return reader.result();
                }
                long now = System.nanoTime();
                if (idle > 0 && now - reader.lastProgress.get() > idle || reader.overTime(now, total)) {
                    StructuredDocument stopped = reader.stop(StatusReason.TIMEOUT);
                    worker.interrupt();
                    LOG.log(System.Logger.Level.INFO, "Extraktion an der Zeitgrenze beendet ({0})",
                            StatusReason.TIMEOUT.code());
                    return stopped;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            StructuredDocument stopped = reader.stop(StatusReason.TIMEOUT);
            worker.interrupt();
            return stopped;
        }
    }

    /** Ob Tika für den Typ (oder einen Obertyp) einen Parser hat; ohne Angabe entscheidet die Erkennung beim Lesen. */
    private static boolean supported(AutoDetectParser tika, String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            return true;
        }
        MediaType type = MediaType.parse(mediaType.toLowerCase(Locale.ROOT));
        if (type == null) {
            return true;
        }
        type = type.getBaseType();
        if (type.equals(MediaType.OCTET_STREAM)) {
            return true;
        }
        ParseContext context = new ParseContext();
        var parsers = tika.getParsers(context);
        MediaTypeRegistry registry = tika.getMediaTypeRegistry();
        for (MediaType current = registry.normalize(type); current != null; current = registry.getSupertype(current)) {
            if (parsers.containsKey(current)) {
                return !current.equals(MediaType.OCTET_STREAM);
            }
        }
        return false;
    }

    /** Der an der Datei erkannte Typ ohne Parameter; leer, wenn die Erkennung scheitert. */
    private static String detect(AutoDetectParser tika, Path original, String title) {
        Metadata metadata = new Metadata();
        if (title != null && !title.isBlank()) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, title);
        }
        try (TikaInputStream in = TikaInputStream.get(original)) {
            return tika.getDetector().detect(in, metadata).getBaseType().toString();
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static long usableTempSpace() {
        try {
            return Files.getFileStore(Path.of(System.getProperty("java.io.tmpdir"))).getUsableSpace();
        } catch (IOException | RuntimeException e) {
            return Long.MAX_VALUE;
        }
    }

    private static StructuredDocument empty(Outcome outcome) {
        return new StructuredDocument(DocumentHead.EMPTY, outcome, List.of(), 0, 0);
    }

    private AutoDetectParser parser() {
        AutoDetectParser current = parser;
        if (current == null) {
            synchronized (this) {
                current = parser;
                if (current == null) {
                    try {
                        current = new AutoDetectParser(new TikaConfig(
                                new ByteArrayInputStream(CONFIG.getBytes(StandardCharsets.UTF_8))));
                    } catch (Exception e) {
                        throw new IllegalStateException("Tika-Konfiguration nicht lesbar", e);
                    }
                    parser = current;
                }
            }
        }
        return current;
    }

    /**
     * Ein Lauf: Tika im Arbeitsthread, Ergebnis und Abbruch unter einer Sperre. Der äußere Behälter und jede enthaltene
     * Datei sind je ein {@link Doc} mit eigenem Blockstrom, eigenen Zählern und eigenem Status (AP7).
     */
    private final class Reader {

        private final AutoDetectParser tika;
        private final Path original;
        private final String title;
        private final String mediaType;
        private final long size;
        private final ExtractionLimits limits;
        private final StructureSink sink;
        private final PartRules rules;
        final AtomicLong lastProgress = new AtomicLong(System.nanoTime());
        /** Kontext des Laufs, für Textteile einer Mail. */
        private ParseContext parseContext;
        private final Doc root;
        /** Offene Dokumente, innen oben: der Behälter und die gerade gelesenen Teile. */
        private final ArrayDeque<Doc> open = new ArrayDeque<>();
        /** Verzeichnis in Lesereihenfolge; ein Platz wird beim Beginn eines Teils belegt. */
        private final List<EmbeddedEntry> directory = new ArrayList<>();
        private PartIdNumbering numbering;
        private int entries;
        private long unpacked;
        /** Erster Grund, aus dem dem Behälter Teile fehlen (zu viele, zu weit ausgepackt). */
        private StatusReason missing;
        private boolean unpackingEnded;
        /** Gesucht für {@link #openPart}: dieser Teil behält seine temporäre Datei, danach endet das Auspacken. */
        private PartId.Content wanted;
        private java.util.concurrent.atomic.AtomicReference<Path> found;
        /** Verlangt für {@link #copyParts}: diese Teile gehen nacheinander an den Abnehmer. */
        private Delivery delivery;
        private boolean stopped;
        private StatusReason stopReason;
        private StructuredDocument result;

        Reader(AutoDetectParser tika, Path original, String title, String mediaType, long size,
               ExtractionLimits limits, StructureSink sink, BlockSink target, PartRules rules) {
            this.tika = tika;
            this.original = original;
            this.title = title;
            this.mediaType = mediaType;
            this.size = size;
            this.limits = limits;
            this.sink = sink;
            this.rules = rules;
            this.root = new Doc(target, size, mediaType, null, "", 0);
        }

        void read() {
            // Gelesen wird nach dem Inhalt; die Schranke gilt deshalb auch für den an der Datei erkannten Typ, bei
            // Abweichung der strengere Wert (Architekt 13.11.2026). Die Erkennung läuft hier unter Platz- und
            // Zeitgrenze, weil sie bei Containern (OLE2, ZIP) selbst Struktur liest.
            if (ExtractionLimits.exceeds(size, limits.memoryLimit(detect(tika, original, title)))) {
                synchronized (this) {
                    if (!stopped) {
                        result = new StructuredDocument(DocumentHead.EMPTY,
                                Outcome.of(ExtractionStatus.EXCLUDED, StatusReason.MEMORY_LIMIT), List.of(), 0, 0);
                        stopped = true;
                    }
                }
                return;
            }
            Metadata metadata = new Metadata();
            if (title != null && !title.isBlank()) {
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, title);
            }
            if (mediaType != null && !mediaType.isBlank()) {
                metadata.set(TikaCoreProperties.CONTENT_TYPE_HINT, mediaType);
            }
            ParseContext context = new ParseContext();
            context.set(Parser.class, tika);
            parseContext = context;
            // Enthaltene Dateien werden eigene Dokumente (AP7); was zum Dokument selbst gehört, bleibt in seinem Text.
            context.set(EmbeddedDocumentExtractor.class, new PartExtractor(context));
            synchronized (this) {
                open.push(root);
            }
            Outcome outcome;
            try (TikaInputStream in = TikaInputStream.get(original, metadata)) {
                root.stream = in;
                root.handler.metadata = metadata;
                (parseWith == null ? tika : parseWith).parse(in, root.handler, metadata, context);
                flushMailBody(root);
                root.handler.flush();
                outcome = root.full ? root.truncatedOrFailed(StatusReason.TOO_LARGE)
                        : missing != null ? root.partial(missing)
                        : embeddedFailure(metadata) ? root.partial(StatusReason.ENTRIES_NOT_READABLE) : null;
            } catch (Exception | LinkageError | OutOfMemoryError e) {
                outcome = aborted(root, e);
            } finally {
                discardMailBody(root);
            }
            synchronized (this) {
                if (stopped) {
                    return;
                }
                outcome = root.finish(outcome);
                result = new StructuredDocument(root.head(metadata), outcome, entries(), root.blocks, root.chars);
                stopped = true;
            }
        }

        /** Übersetzt einen Abbruch beim Lesen eines Dokuments in seinen Status. */
        private Outcome aborted(Doc doc, Throwable e) {
            if (e instanceof Stop stop) {
                return doc.truncatedOrFailed(stop.reason);
            }
            if (e instanceof EncryptedDocumentException) {
                return doc.truncatedOrFailed(StatusReason.ENCRYPTED);
            }
            if (e instanceof OutOfMemoryError) {
                return doc.truncatedOrFailed(StatusReason.MEMORY_LIMIT);
            }
            Throwable stop = cause(e, Stop.class);
            if (stop != null) {
                return doc.truncatedOrFailed(((Stop) stop).reason);
            }
            if (cause(e, EncryptedDocumentException.class) != null) {
                return doc.truncatedOrFailed(StatusReason.ENCRYPTED);
            }
            if (zipBomb(e)) {
                return doc.truncatedOrFailed(StatusReason.UNPACKED_TOO_LARGE);
            }
            LOG.log(System.Logger.Level.INFO, "Extraktion gescheitert ({0})", e.getClass().getName());
            return doc.truncatedOrFailed(StatusReason.CORRUPT);
        }

        /** Das Verzeichnis ohne noch freie Plätze (unter der Sperre). */
        private List<EmbeddedEntry> entries() {
            return directory.stream().filter(Objects::nonNull).toList();
        }

        /** Bricht von außen ab; danach nimmt kein Empfänger mehr einen Block an. */
        synchronized StructuredDocument stop(StatusReason reason) {
            if (result != null) {
                return result;
            }
            for (Doc doc : open) {
                doc.drain();
                if (doc != root && doc.entry != null) {
                    // Ein offener Teil endet mit dem Lauf; sein Eintrag bleibt mit Status.
                    directory.set(doc.slot, withOutcome(doc.entry, doc.truncatedOrFailed(reason)));
                }
            }
            stopped = true;
            stopReason = reason;
            result = new StructuredDocument(DocumentHead.EMPTY, root.truncatedOrFailed(reason), entries(),
                    root.blocks, root.chars);
            return result;
        }

        /**
         * Prüft die Gesamtlaufzeit des innersten offenen Dokuments: Beim Behälter endet der Lauf ({@code true}), bei
         * einem Teil nur dieser Teil (er endet an seinem nächsten Fortschritt).
         */
        synchronized boolean overTime(long now, long total) {
            Doc current = open.isEmpty() ? root : open.peek();
            if (total <= 0 || now - current.started <= total) {
                return false;
            }
            if (current == root) {
                return true;
            }
            current.expired = true;
            return false;
        }

        synchronized StructuredDocument result() {
            if (result == null) {
                // Der Thread endete ohne Ergebnis (etwa durch einen Fehler außerhalb von Tika).
                result = new StructuredDocument(DocumentHead.EMPTY, root.truncatedOrFailed(StatusReason.CORRUPT),
                        entries(), root.blocks, root.chars);
                stopped = true;
            }
            return result;
        }

        private void checkRunning() throws Stop {
            if (stopped) {
                throw new Stop(stopReason == null ? StatusReason.TIMEOUT : stopReason);
            }
        }

        /** Enthaltene Dateien, die Tika nicht lesen konnte, meldet es nur in den Metadaten des Behälters. */
        private static boolean embeddedFailure(Metadata metadata) {
            return metadata.get(TikaCoreProperties.EMBEDDED_EXCEPTION) != null
                    || metadata.get(TikaCoreProperties.EMBEDDED_BYTES_EXCEPTION) != null
                    || metadata.get(TikaCoreProperties.TIKA_META_EXCEPTION_EMBEDDED_STREAM) != null;
        }

        /** Kennungen dieses Behälters; den Hash bildet der Adapter selbst, wenn ihn der Aufruf nicht nennt. */
        private PartIdNumbering numbering() throws IOException {
            if (numbering == null) {
                String sha = rules.containerSha256().orElse(null);
                if (sha == null) {
                    try (InputStream in = Files.newInputStream(original)) {
                        sha = sha256(in);
                    }
                }
                numbering = new PartIdNumbering(sha);
            }
            return numbering;
        }

        /** Belegt den nächsten Platz im Verzeichnis. */
        private synchronized int reserve() {
            directory.add(null);
            return directory.size() - 1;
        }

        /** Setzt einen Eintrag und meldet ihn mit leerem Strom (Teil ohne gelesenen Text). */
        private void record(int slot, EmbeddedEntry entry) throws Stop {
            synchronized (this) {
                checkRunning();
                directory.set(slot, entry);
            }
            try (PartSink part = sink.part(entry)) {
                // leer: Status steht am Eintrag
                Objects.requireNonNull(part);
            }
        }

        /** Dem Behälter fehlt ein Teil, das Auspacken geht weiter. */
        private void partMissing(StatusReason reason) {
            synchronized (this) {
                if (missing == null) {
                    missing = reason;
                }
            }
        }

        private void endUnpacking(StatusReason reason) {
            synchronized (this) {
                unpackingEnded = true;
                if (missing == null) {
                    missing = reason;
                }
            }
        }

        /**
         * Nimmt jede Datei an, die Tika aus einem Behälter übergibt (ADR 0026, Punkte 5, 8 und 13). Was zum Dokument
         * selbst gehört (Inline-Bilder, Vorschaubilder, Makros), läuft wie bisher in dessen Text; jede andere Datei
         * wird ein Eintrag mit eigener Kennung und eigenem Status und wird aus einer temporären Datei gelesen.
         */
        private final class PartExtractor extends ParsingEmbeddedDocumentExtractor {

            private final ParseContext context;

            PartExtractor(ParseContext context) {
                super(context);
                this.context = context;
            }

            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return true;
            }

            @Override
            public void parseEmbedded(InputStream stream, ContentHandler handler, Metadata metadata,
                                      boolean outputHtml) throws SAXException, IOException {
                Doc parent;
                String path;
                synchronized (Reader.this) {
                    checkRunning();
                    parent = open.peek();
                }
                if (mailText(metadata, parent)) {
                    // Textteil der Mail: kein Teil, keine Position (wie früher, als Tika ihn selbst einfügte).
                    mailBody(stream, metadata, parent);
                    return;
                }
                beforePart(metadata, parent);
                synchronized (Reader.this) {
                    checkRunning();
                    // Die Position zählt jeden übergebenen Teil, auch die, die kein Eintrag werden (Architekt).
                    path = parent.path + "/" + ++parent.parts;
                    if (unpackingEnded) {
                        return;
                    }
                }
                if (belongsToDocument(metadata, parent)) {
                    super.parseEmbedded(stream, handler, metadata, outputHtml);
                    return;
                }
                if (limits.maxEmbeddedFiles() > 0 && entries >= limits.maxEmbeddedFiles()) {
                    // Über der Grenze entstehen keine weiteren Einträge; das Auspacken endet.
                    endUnpacking(StatusReason.TOO_MANY_FILES);
                    return;
                }
                entries++;
                readPart(stream, metadata, parent, path, context);
            }
        }

        /**
         * Ob ein Teil ein namenloser Textteil einer Mail ist (ADR 0026, Punkte 6 und 13): Tikas Mail-Parser reicht alle
         * Teile als Strom durch ({@code extractAllAlternatives}), statt die der ersten Ebene als Byte-Array zu
         * kopieren; die Textteile ordnet der Adapter selbst der Mail zu.
         */
        private boolean mailText(Metadata metadata, Doc parent) {
            if (!parent.handler.detectedType().equals("message/rfc822")) {
                return false;
            }
            String type = baseType(metadata.get(Metadata.CONTENT_TYPE));
            if (!type.equals("text/plain") && !type.equals("text/html")) {
                return false;
            }
            String disposition = metadata.get(Metadata.CONTENT_DISPOSITION);
            if (disposition != null && disposition.strip().toLowerCase(Locale.ROOT).startsWith("attachment")) {
                return false;
            }
            String name = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
            return name == null || name.isBlank();
        }

        /**
         * Nimmt einen Textteil der Mail an, wie Tika ohne {@code extractAllAlternatives} wählt
         * ({@code MailContentHandler#handleBestParts}): Außerhalb von {@code multipart/alternative} wird er sofort
         * gelesen. In Alternativen zählt der beste Zweig (Text 1, HTML 2, ein verschachtelter Teil wie
         * {@code multipart/related} 4), bei Gleichstand der frühere; sein Text wird gelesen, wenn die Alternativen enden.
         * Wohin ein Teil gehört, zeigt der Baum der Grenzen ({@link MimeTree}); ohne Nachweis endet die Gruppe, und
         * nichts geht verloren.
         */
        private void mailBody(InputStream stream, Metadata metadata, Doc parent) throws SAXException, IOException {
            String boundary = metadata.get(Message.MULTIPART_BOUNDARY);
            String subtype = Objects.toString(metadata.get(Message.MULTIPART_SUBTYPE), "").toLowerCase(Locale.ROOT);
            MailBody body = spoolBody(stream, metadata);
            try {
                if (parent.alternatives != null && !parent.alternatives.contains(boundary, parent)) {
                    flushMailBody(parent);
                }
                if (parent.alternatives == null) {
                    String group = subtype.equals("alternative") ? boundary
                            : !subtype.isEmpty() && !subtype.equals("mixed") && boundary != null
                            ? parent.mimeTree().alternativeAbove(boundary) : null;
                    if (group == null) {
                        read(body, parent);
                        return;
                    }
                    parent.alternatives = new Alternatives(group);
                }
                parent.alternatives.add(boundary, body, parent);
                body = null;
            } finally {
                if (body != null) {
                    Files.deleteIfExists(body.temp());
                }
            }
        }

        /** Schreibt einen Textteil zählend in eine temporäre Datei. */
        private MailBody spoolBody(InputStream stream, Metadata metadata) throws SAXException, IOException {
            Path temp = Files.createTempFile("teil-text-", ".tmp");
            try {
                byte[] buffer = new byte[64 * 1024];
                try (var out = Files.newOutputStream(temp)) {
                    int read;
                    while ((read = stream.read(buffer)) >= 0) {
                        lastProgress.set(System.nanoTime());
                        synchronized (Reader.this) {
                            checkRunning();
                        }
                        out.write(buffer, 0, read);
                    }
                }
            } catch (IOException | SAXException | RuntimeException e) {
                Files.deleteIfExists(temp);
                throw e;
            }
            int score = baseType(metadata.get(Metadata.CONTENT_TYPE)).equals("text/html") ? 2 : 1;
            return new MailBody(temp, metadata, score);
        }

        /**
         * Vor einem anderen Teil der Mail: Gehört er nicht zu den wartenden Alternativen (etwa ein Inline-Bild im
         * gewählten Zweig), enden sie, und ihr bester Zweig wird gelesen.
         */
        private void beforePart(Metadata metadata, Doc parent) throws SAXException, IOException {
            if (parent.alternatives != null
                    && !parent.alternatives.contains(metadata.get(Message.MULTIPART_BOUNDARY), parent)) {
                flushMailBody(parent);
            }
        }

        /** Liest den besten Zweig der wartenden Alternativen in den Text der Mail; die übrigen fallen weg. */
        private void flushMailBody(Doc doc) throws SAXException, IOException {
            Alternatives alternatives = doc.alternatives;
            if (alternatives == null) {
                return;
            }
            doc.alternatives = null;
            List<MailBody> chosen = alternatives.best();
            try {
                for (MailBody body : chosen) {
                    read(body, doc);
                }
            } finally {
                alternatives.delete();
            }
        }

        /** Liest einen Textteil in den Text der Mail; danach ist seine Datei gelöscht. */
        private void read(MailBody body, Doc doc) throws SAXException, IOException {
            try (TikaInputStream in = TikaInputStream.get(body.temp())) {
                Metadata inline = new Metadata();
                String type = baseType(body.metadata().get(Metadata.CONTENT_TYPE));
                inline.set(TikaCoreProperties.CONTENT_TYPE_PARSER_OVERRIDE, type);
                String charset = body.metadata().get(Metadata.CONTENT_ENCODING);
                inline.set(Metadata.CONTENT_TYPE, charset == null || charset.isBlank() ? type
                        : type + "; charset=" + charset.strip());
                tika.parse(in, new org.apache.tika.sax.EmbeddedContentHandler(
                        new org.apache.tika.sax.BodyContentHandler(doc.handler)), inline, parseContext);
            } catch (org.apache.tika.exception.TikaException e) {
                throw new IOException("Textteil der Mail nicht lesbar", e);
            } finally {
                Files.deleteIfExists(body.temp());
            }
        }

        /** Nach einem Abbruch: Wartende Textteile werden nicht mehr gelesen, ihre temporären Dateien fallen weg. */
        private void discardMailBody(Doc doc) {
            Alternatives alternatives = doc.alternatives;
            doc.alternatives = null;
            if (alternatives != null) {
                alternatives.delete();
            }
        }

        /** Ein Textteil der Mail in einer temporären Datei; Wert 1 Text, 2 HTML. */
        private record MailBody(Path temp, Metadata metadata, int score) {
        }

        /** Wartende Zweige einer {@code multipart/alternative} (Grenze {@code group}) in Lesereihenfolge. */
        private static final class Alternatives {

            private final String group;
            private final java.util.LinkedHashMap<String, Branch> branches = new java.util.LinkedHashMap<>();
            private int direct;

            Alternatives(String group) {
                this.group = group;
            }

            /** Ob ein Teil mit dieser Grenze in diesen Alternativen liegt (nachgewiesen über den Baum). */
            boolean contains(String boundary, Doc doc) throws IOException {
                if (boundary == null) {
                    return false;
                }
                return boundary.equals(group) || doc.mimeTree().childBelow(group, boundary) != null;
            }

            void add(String boundary, MailBody body, Doc doc) throws IOException {
                if (group.equals(boundary)) {
                    // Ein Teil direkt in den Alternativen ist ein eigener Zweig.
                    Branch branch = new Branch(body.score());
                    branch.bodies.add(body);
                    branches.put("direkt-" + direct++, branch);
                    return;
                }
                String child = doc.mimeTree().childBelow(group, boundary);
                branches.computeIfAbsent("teil-" + child, key -> new Branch(4)).bodies.add(body);
            }

            /** Der beste Zweig, bei Gleichstand der frühere; seine Textteile in Reihenfolge. */
            List<MailBody> best() {
                Branch best = null;
                for (Branch branch : branches.values()) {
                    if (best == null || branch.score > best.score) {
                        best = branch;
                    }
                }
                if (best == null) {
                    return List.of();
                }
                List<MailBody> chosen = List.copyOf(best.bodies);
                best.bodies.clear();
                return chosen;
            }

            void delete() {
                for (Branch branch : branches.values()) {
                    for (MailBody body : branch.bodies) {
                        try {
                            Files.deleteIfExists(body.temp());
                        } catch (IOException e) {
                            LOG.log(System.Logger.Level.DEBUG, "Temporäre Datei nicht gelöscht");
                        }
                    }
                    branch.bodies.clear();
                }
            }

            private static final class Branch {
                private final int score;
                private final List<MailBody> bodies = new ArrayList<>();

                Branch(int score) {
                    this.score = score;
                }
            }
        }

        /**
         * Ob ein Teil zum Dokument selbst gehört (ADR 0026, Punkt 13): zuerst Tikas Angabe am Teil ({@code INLINE},
         * Makro, Vorschaubild gehören dazu, {@code ATTACHMENT} nicht); fehlt sie, gehören nur Bilder eines
         * Office-Dokuments oder PDF dazu. Eine eingebettete Datei (etwa eine Excel-Mappe oder ein PDF in Word) ist eine
         * enthaltene Datei und wird ein Eintrag, auch mit Beziehungskennung. Mails sind immer eigene Einträge, auch
         * weitergeleitete ohne Dateinamen.
         */
        private boolean belongsToDocument(Metadata metadata, Doc parent) {
            String declared = baseType(metadata.get(Metadata.CONTENT_TYPE));
            if (MAIL_TYPES.contains(declared)) {
                return false;
            }
            String kind = metadata.get(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE);
            if (kind != null && !kind.isBlank()) {
                return !kind.strip().equalsIgnoreCase("ATTACHMENT");
            }
            String parentType = parent.handler.detectedType();
            boolean office = parentType.equals("application/pdf") || parentType.equals("application/rtf")
                    || parentType.equals("application/msword") || parentType.startsWith("application/vnd.ms-excel")
                    || parentType.startsWith("application/vnd.ms-powerpoint")
                    || parentType.startsWith("application/vnd.ms-word")
                    || parentType.startsWith("application/vnd.openxmlformats-officedocument.")
                    || parentType.startsWith("application/vnd.oasis.opendocument.");
            return office && declared.startsWith("image/");
        }

        /** Schreibt den Teil zählend in eine temporäre Datei und liest ihn von dort als eigenes Dokument. */
        private void readPart(InputStream stream, Metadata metadata, Doc parent, String path, ParseContext context)
                throws SAXException, IOException {
            int depth = parent.depth + 1;
            Optional<String> name = Optional.ofNullable(metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY))
                    .map(String::strip).filter(value -> !value.isEmpty());
            Optional<String> declared = Optional.of(baseType(metadata.get(Metadata.CONTENT_TYPE)))
                    .filter(value -> !value.isEmpty());
            Optional<PartId> parentId = Optional.ofNullable(parent.id);
            int slot = reserve();
            parent.entries++;
            if (limits.maxDepth() > 0 && depth > limits.maxDepth()) {
                // Zu tief: ein Eintrag mit Status, sein Inhalt wird nicht geöffnet.
                record(slot, new EmbeddedEntry(numbering().position(path), Optional.empty(), name, declared, path,
                        parentId, depth, Optional.empty(), Outcome.of(ExtractionStatus.EXCLUDED,
                        StatusReason.TOO_DEEP)));
                return;
            }
            Path temp = null;
            try {
                temp = Files.createTempFile("teil-", ".tmp");
                Spooled spooled = spool(stream, temp);
                if (spooled.failure() != null) {
                    record(slot, new EmbeddedEntry(numbering().position(path), Optional.empty(), name, declared, path,
                            parentId, depth, Optional.of(spooled.bytes()), spooled.failure()));
                    return;
                }
                PartId id = numbering().content(spooled.sha256());
                if (wanted != null && wanted.equals(id)) {
                    // Gefunden: Die Datei geht an den Aufrufer, die übrigen Teile werden nicht mehr ausgepackt.
                    Path kept = Files.createTempFile("original-", ".tmp");
                    Files.move(temp, kept, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    temp = null;
                    synchronized (this) {
                        // Unter der Sperre wie der Abbruch: Ist der Aufrufer schon fort (Zeitgrenze), übernimmt
                        // niemand die Datei, sie wird gelöscht.
                        if (stopped) {
                            Files.deleteIfExists(kept);
                            return;
                        }
                        found.set(kept);
                        unpackingEnded = true;
                    }
                    return;
                }
                if (delivery != null && deliver(id, temp)) {
                    return;
                }
                Optional<String> sha = Optional.of(spooled.sha256());
                String type = detectedType(temp, metadata, declared.orElse(""));
                Optional<String> typeValue = Optional.of(type).filter(value -> !value.isEmpty()).or(() -> declared);
                Outcome refusal = refusal(type, declared.orElse(""), spooled.bytes());
                if (refusal != null) {
                    record(slot, new EmbeddedEntry(id, sha, name, typeValue, path, parentId, depth,
                            Optional.of(spooled.bytes()), refusal));
                    return;
                }
                EmbeddedEntry preliminary = new EmbeddedEntry(id, sha, name, typeValue, path, parentId, depth,
                        Optional.of(spooled.bytes()), Outcome.ok(new ReadExtent(spooled.bytes(), 0)));
                parsePart(temp, type, name, preliminary, slot, parent, context);
            } catch (IOException e) {
                // Lesefehler beim Auspacken: Eintrag nach Position, die übrigen Teile werden gelesen.
                record(slot, new EmbeddedEntry(numbering().position(path), Optional.empty(), name, declared, path,
                        parentId, depth, Optional.empty(), Outcome.of(ExtractionStatus.FAILED,
                        StatusReason.CORRUPT)));
            } finally {
                if (temp != null) {
                    Files.deleteIfExists(temp);
                }
            }
        }

        /**
         * Gibt einen verlangten Teil an den Abnehmer, unter der Sperre wie der Abbruch: Ist der Aufrufer fort
         * (Zeitgrenze), geht nichts mehr hinaus. Der Teil wird danach weiter gelesen, weil er selbst verlangte Teile
         * enthalten kann.
         *
         * @return ob das Auspacken endet (alles übergeben, Abnehmer gescheitert oder Lauf beendet)
         */
        private boolean deliver(PartId id, Path temp) throws IOException {
            synchronized (this) {
                if (stopped) {
                    return true;
                }
                if (!(id instanceof PartId.Content content) || !delivery.remaining.remove(content)) {
                    return false;
                }
                lastProgress.set(System.nanoTime());
                try (InputStream in = Files.newInputStream(temp)) {
                    delivery.originals.accept(content, in);
                } catch (IOException e) {
                    delivery.failure = e;
                    unpackingEnded = true;
                    return true;
                } catch (java.io.UncheckedIOException e) {
                    delivery.failure = e.getCause();
                    unpackingEnded = true;
                    return true;
                } catch (RuntimeException e) {
                    delivery.failure = new IOException("Abnehmer gescheitert", e);
                    unpackingEnded = true;
                    return true;
                } finally {
                    lastProgress.set(System.nanoTime());
                }
                if (delivery.remaining.isEmpty()) {
                    unpackingEnded = true;
                    return true;
                }
                return false;
            }
        }

        /** Warum ein vollständig ausgepackter Teil nicht gelesen wird; {@code null}, wenn er gelesen wird. */
        private Outcome refusal(String type, String declared, long bytes) {
            if (bytes == 0) {
                return Outcome.of(ExtractionStatus.UNSUPPORTED, StatusReason.EMPTY);
            }
            if (!CONTAINER_TYPES.contains(type) && !rules.documentTypes().test(type)) {
                return Outcome.of(ExtractionStatus.EXCLUDED, StatusReason.MEDIA_TYPE_EXCLUDED);
            }
            long limit = limits.memoryLimit(type);
            if (!declared.isEmpty()) {
                long other = limits.memoryLimit(declared);
                limit = limit == 0 ? other : other == 0 ? limit : Math.min(limit, other);
            }
            if (ExtractionLimits.exceeds(bytes, limit)) {
                return Outcome.of(ExtractionStatus.EXCLUDED, StatusReason.MEMORY_LIMIT);
            }
            if (ExtractionLimits.exceeds(bytes, limits.maxFileBytes())) {
                return Outcome.of(ExtractionStatus.EXCLUDED, StatusReason.TOO_LARGE);
            }
            return null;
        }

        /** Liest einen ausgepackten Teil mit eigenem Handler; nur dieser Teil endet an seinen Grenzen. */
        private void parsePart(Path temp, String type, Optional<String> name, EmbeddedEntry preliminary, int slot,
                               Doc parent, ParseContext context) throws SAXException, IOException {
            PartSink partSink = sink.part(preliminary);
            Doc child = new Doc(partSink, preliminary.size().orElse(0L), type, preliminary.id(),
                    preliminary.position(), preliminary.depth());
            child.entry = preliminary;
            child.slot = slot;
            synchronized (Reader.this) {
                checkRunning();
                directory.set(slot, preliminary);
                open.push(child);
            }
            Metadata metadata = new Metadata();
            name.ifPresent(value -> metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, value));
            if (!type.isEmpty()) {
                metadata.set(TikaCoreProperties.CONTENT_TYPE_HINT, type);
            }
            Outcome outcome;
            try {
                try (TikaInputStream in = TikaInputStream.get(temp, metadata)) {
                    child.stream = in;
                    child.handler.metadata = metadata;
                    (parseWith == null ? tika : parseWith).parse(in, child.handler, metadata, context);
                    flushMailBody(child);
                    child.handler.flush();
                    outcome = child.full ? child.truncatedOrFailed(StatusReason.TOO_LARGE)
                            : embeddedFailure(metadata) ? child.partial(StatusReason.ENTRIES_NOT_READABLE) : null;
                } catch (Exception | LinkageError | OutOfMemoryError e) {
                    synchronized (Reader.this) {
                        // Ein Abbruch von außen beendet den ganzen Lauf, nicht nur diesen Teil.
                        checkRunning();
                    }
                    outcome = aborted(child, e);
                }
                synchronized (Reader.this) {
                    checkRunning();
                    outcome = child.finish(outcome);
                    directory.set(slot, withOutcome(preliminary, outcome));
                }
                partSink.head(child.head(metadata));
            } finally {
                discardMailBody(child);
                synchronized (Reader.this) {
                    open.remove(child);
                    // Die Zeit im Teil zählt nicht zur Gesamtlaufzeit des Elterndokuments.
                    // Die ganze Wanduhrzeit des Teils samt seiner Kinder, nicht seine verschobene eigene Laufzeit.
                    parent.started += System.nanoTime() - child.entered;
                }
                partSink.close();
            }
        }

        /**
         * Schreibt einen Teil in die temporäre Datei und bildet dabei Hash und Größe; endet am Verhältnis von
         * Ausgepacktem zu Gepacktem, an der Platzreserve, einem Lesefehler oder einem Abbruch des Laufs.
         */
        private Spooled spool(InputStream stream, Path temp) throws IOException, SAXException {
            java.security.MessageDigest digest = sha256Digest();
            long bytes = 0;
            long nextSpaceCheck = SPACE_CHECK_BYTES;
            byte[] buffer = new byte[64 * 1024];
            try (var out = Files.newOutputStream(temp)) {
                while (true) {
                    int read;
                    try {
                        read = stream.read(buffer);
                    } catch (IOException e) {
                        return new Spooled(null, bytes, Outcome.of(ExtractionStatus.FAILED, StatusReason.CORRUPT));
                    }
                    if (read < 0) {
                        break;
                    }
                    lastProgress.set(System.nanoTime());
                    synchronized (Reader.this) {
                        checkRunning();
                    }
                    bytes += read;
                    unpacked += read;
                    if (limits.unpackRatio() > 0 && unpacked > limits.unpackRatioFromBytes()
                            && unpacked > (size > Long.MAX_VALUE / limits.unpackRatio() ? Long.MAX_VALUE
                            : limits.unpackRatio() * size)) {
                        // Nur dieser Teil endet (ADR 0026, Abnahme 4): seine Bytes zählen nicht weiter, die
                        // temporäre Datei fällt weg, die übrigen Teile werden gelesen; der Behälter ist PARTIAL.
                        unpacked -= bytes;
                        partMissing(StatusReason.UNPACKED_TOO_LARGE);
                        return new Spooled(null, bytes, Outcome.of(ExtractionStatus.FAILED,
                                StatusReason.UNPACKED_TOO_LARGE));
                    }
                    if (bytes >= nextSpaceCheck) {
                        nextSpaceCheck += SPACE_CHECK_BYTES;
                        if (limits.tempSpaceReserve() > 0 && usableTempSpace() < limits.tempSpaceReserve()) {
                            return new Spooled(null, bytes, Outcome.of(ExtractionStatus.FAILED,
                                    StatusReason.TEMP_SPACE_LOW));
                        }
                    }
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }
            return new Spooled(java.util.HexFormat.of().formatHex(digest.digest()), bytes, null);
        }

        /** Der am Teil erkannte Typ ohne Parameter; sonst der gemeldete. */
        private String detectedType(Path temp, Metadata metadata, String declared) {
            Metadata probe = new Metadata();
            String name = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
            if (name != null) {
                probe.set(TikaCoreProperties.RESOURCE_NAME_KEY, name);
            }
            try (TikaInputStream in = TikaInputStream.get(temp)) {
                return baseType(tika.getDetector().detect(in, probe).toString());
            } catch (IOException | RuntimeException e) {
                return declared;
            }
        }

        /** Ein Dokument dieses Laufs: der Behälter oder eine enthaltene Datei. */
        private final class Doc {

            private final BlockSink target;
            private final long size;
            private final String mediaType;
            private final PartId id;
            private final String path;
            private final int depth;
            private final Handler handler = new Handler();
            private volatile TikaInputStream stream;
            private long blocks;
            private long chars;
            /** Beginn der eigenen Laufzeit; die Zeit in Teilen wird hinzugerechnet (nicht mitgezählt). */
            private long started = System.nanoTime();
            /** Beginn des Dokuments, unverschoben. */
            private final long entered = started;
            /** Gesamtlaufzeit erreicht (nur Teile): endet am nächsten Fortschritt. */
            private volatile boolean expired;
            /**
             * Zeichengrenze einer Mail erreicht: Ihr weiterer Text fällt weg, ihre Anhänge werden noch gelesen (eine
             * Grenze beendet nur den betroffenen Teil).
             */
            private boolean full;
            /** Teile, die Tika aus diesem Dokument übergeben hat (Zählerpfad). */
            private int parts;
            /** Einträge im Verzeichnis, deren Eltern dieses Dokument ist. */
            private int entries;
            private EmbeddedEntry entry;
            private int slot = -1;
            /** Textteile einer Mail, die auf das Ende ihrer Alternativen warten. */
            private Alternatives alternatives;
            /** Baum der MIME-Grenzen einer Mail, erst bei Bedarf gelesen. */
            private MimeTree mimeTree;

            /** Der Baum der Grenzen aus der Datei dieses Dokuments (eine Mail). */
            private MimeTree mimeTree() throws IOException {
                if (mimeTree == null) {
                    Path file = stream == null ? null : stream.getPath();
                    mimeTree = file == null ? MimeTree.EMPTY : MimeTree.scan(file);
                }
                return mimeTree;
            }

            Doc(BlockSink target, long size, String mediaType, PartId id, String path, int depth) {
                this.target = target;
                this.size = size;
                this.mediaType = mediaType;
                this.id = id;
                this.path = path;
                this.depth = depth;
            }

            /** Status am Ende: Abbruch mit gesammeltem Text, sonst vollständig oder ohne Text (unter der Sperre). */
            private Outcome finish(Outcome outcome) {
                // Nach vollständigem Lesen ist der Puffer leer; drain betrifft nur Abbrüche.
                StatusReason abort = outcome == null ? null : outcome.reason().orElse(StatusReason.CORRUPT);
                if (abort != null && abort != StatusReason.TOO_LARGE && abort != StatusReason.TEXT_RATIO_EXCEEDED
                        && drain()) {
                    // Bis zum Abbruch gesammelten Text behalten; der Grund bleibt der Abbruch.
                    outcome = truncatedOrFailed(abort);
                }
                if (outcome == null) {
                    // Ohne eigenen Text, aber mit Einträgen (Archiv, Mail nur mit Anhängen) ist das Dokument gelesen;
                    // no-text gilt nur, wenn weder Text noch Einträge da sind (Architekt 13.11.2026).
                    outcome = chars == 0 && entries == 0 ? Outcome.of(ExtractionStatus.UNSUPPORTED, StatusReason.NO_TEXT)
                            : Outcome.ok(new ReadExtent(size, chars));
                }
                return outcome;
            }

            /** Gibt den noch gepufferten Text aus (unter der Sperre); true, wenn dabei Zeichen dazukamen. */
            private boolean drain() {
                synchronized (Reader.this) {
                    long before = chars;
                    try {
                        handler.flush();
                    } catch (SAXException | RuntimeException e) {
                        // Grenze erreicht: Der angenommene Teil bleibt.
                    }
                    return chars > before;
                }
            }

            /**
             * Vollständig gelesen, aber mit fehlenden Teilen: gekürzt; ohne jeden Text und ohne Einträge gescheitert,
             * außer bei einem Archiv, das nie eigenen Text hat.
             */
            private Outcome partial(StatusReason reason) {
                return chars == 0 && entries == 0 && !archive() ? Outcome.of(ExtractionStatus.FAILED, reason)
                        : Outcome.truncated(reason, new ReadExtent(size, chars));
            }

            /**
             * Ein Archiv hat keinen eigenen Text: Was Tika dort schreibt, sind Namen der Einträge (etwa eines
             * verschlüsselten), also Artefakte. Mails sind keine Archive.
             */
            private boolean archive() {
                String type = handler.detectedType();
                return CONTAINER_TYPES.contains(type) && !MAIL_TYPES.contains(type);
            }

            /**
             * Ohne Text gescheitert, sonst gekürzt mit Umfang: gelesene Bytes laut Strom (Parser, die die Datei direkt
             * lesen, melden hier weniger) und erzeugte Zeichen.
             */
            private Outcome truncatedOrFailed(StatusReason reason) {
                if (chars == 0) {
                    return Outcome.of(ExtractionStatus.FAILED, reason);
                }
                TikaInputStream in = stream;
                long read = in == null ? 0 : Math.min(size, Math.max(0, in.getPosition()));
                return Outcome.truncated(reason, new ReadExtent(read, chars));
            }

            /** Nimmt einen Textblock an, solange der Lauf nicht beendet ist, und prüft dabei die Zeichengrenzen. */
            private void emitText(TextKind kind, int level, boolean ordered, String text, List<Link> links,
                                  Optional<Location> location) throws Stop {
                synchronized (Reader.this) {
                    checkRunning();
                    if (archive() || full) {
                        return;
                    }
                    long room = room();
                    boolean cut = text.length() > room;
                    String accepted = cut ? text.substring(0, (int) Math.max(0, room)) : text;
                    if (!accepted.isEmpty()) {
                        long index = blocks;
                        Block block = switch (kind) {
                            case HEADING -> new Block.Heading(index, location, links, level, accepted);
                            case ITEM -> new Block.ListItem(index, location, links, ordered, level, accepted);
                            case CODE -> new Block.Code(index, location, links, accepted, Optional.empty());
                            case PARAGRAPH -> new Block.Paragraph(index, location, links, accepted);
                        };
                        accept(block);
                    }
                    if (cut) {
                        limitReached();
                        return;
                    }
                    checkRatio();
                }
            }

            /** Nimmt ein Tabellenstück an; an der Zeichengrenze nur die Zellen bis dahin. */
            private void emitTable(List<Block.Cell> cells, boolean continued, List<Link> links,
                                   Optional<Location> location) throws Stop {
                synchronized (Reader.this) {
                    checkRunning();
                    if (archive() || full) {
                        return;
                    }
                    long room = room();
                    List<Block.Cell> accepted = new ArrayList<>();
                    long total = 0;
                    boolean cut = false;
                    for (Block.Cell cell : cells) {
                        if (total + cell.text().length() > room) {
                            int left = (int) Math.max(0, room - total);
                            if (left > 0) {
                                accepted.add(new Block.Cell(cell.row(), cell.column(), cell.text().substring(0, left),
                                        cell.header(), cell.cellRef()));
                                total += left;
                            }
                            cut = true;
                            break;
                        }
                        accepted.add(cell);
                        total += cell.text().length();
                    }
                    if (total > 0) {
                        accept(new Block.Table(blocks, location, links, accepted, continued));
                    }
                    if (cut) {
                        limitReached();
                        return;
                    }
                    checkRatio();
                }
            }

            /** An der Zeichengrenze: Eine Mail liest ihre Anhänge weiter, jedes andere Dokument endet. */
            private void limitReached() throws Stop {
                if (MAIL_TYPES.contains(handler.detectedType())) {
                    full = true;
                    return;
                }
                throw new Stop(StatusReason.TOO_LARGE);
            }

            private long room() {
                return limits.maxChars() > 0 ? limits.maxChars() - chars : Long.MAX_VALUE;
            }

            private void accept(Block block) {
                target.accept(block);
                blocks++;
                chars += block.chars();
            }

            private void checkRatio() throws Stop {
                if (limits.textRatio() > 0 && chars > limits.textRatioFromChars()
                        && chars > (size > Long.MAX_VALUE / limits.textRatio() ? Long.MAX_VALUE
                        : limits.textRatio() * size)) {
                    throw new Stop(StatusReason.TEXT_RATIO_EXCEEDED);
                }
            }

            private DocumentHead head(Metadata metadata) {
                boolean mail = MAIL_TYPES.contains(handler.detectedType());
                Optional<String> titleValue = Optional.ofNullable(metadata.get(TikaCoreProperties.TITLE));
                Optional<Instant> date = Optional.ofNullable(metadata.getDate(TikaCoreProperties.CREATED))
                        .map(java.util.Date::toInstant);
                List<String> to = Arrays.stream(metadata.getValues(Message.MESSAGE_TO)).filter(v -> !v.isBlank())
                        .toList();
                return new DocumentHead(mail ? Optional.empty() : titleValue, mail ? titleValue : Optional.empty(),
                        Optional.ofNullable(metadata.get(Message.MESSAGE_FROM)), to, date);
            }

            /**
             * Baut Blöcke aus Tikas XHTML (ADR 0026, Punkte 3 und 4): Überschriften, Listenpunkte, Tabellen (an Zeilen in
             * Stücke bis zur Blockgröße geteilt), vorformatierter Text als Code, sonst Absätze; Verweisziele am Block.
             * Fundstellen nur, wo Tika sie liefert: PDF-Seite als laufende Nummer der Seitenelemente (physische Seite ab
             * 1), Excel-Blatt aus dem Blattnamen. Tika-Artefakte fallen weg: leere Absätze, Kopfdaten, Namen der Einträge
             * eines Pakets (etwa {@code image1.png}) und Verweise ohne Ziel.
             */
            private final class Handler extends DefaultHandler {

                private final StringBuilder buffer = new StringBuilder();
                private TextKind kind = TextKind.PARAGRAPH;
                private int headingLevel = 1;
                private final ArrayDeque<Boolean> lists = new ArrayDeque<>();
                private int items;
                private int pre;
                private int ignored;
                private final List<Link> links = new ArrayList<>();
                /** Verweise der offenen Zelle; sie gehen mit der Zelle in ihr Tabellenstück. */
                private final List<Link> cellLinks = new ArrayList<>();
                private String anchor;
                private final StringBuilder label = new StringBuilder();
                /** Klassen der offenen div-Elemente. */
                private final ArrayDeque<String> divs = new ArrayDeque<>();
                private int embedded;
                private boolean entryTitle;
                private boolean dropping;
                /** Offene dl-Elemente des Kopfblocks einer Outlook-Mail (Von, An): Kopfdaten, kein Inhalt. */
                private int headerList;
                /**
                 * Stand des Kopfblocks einer Outlook-Mail: 0 vor dem Betreff, 1 nach dem Betreff (Von/An können folgen),
                 * 2 vorbei. Danach sind {@code h1} und {@code dl} Inhalt.
                 */
                private int outlookStage;
                private boolean subject;
                private boolean sheetTitle;
                private boolean sheetHeading;
                private int page;
                private int slide;
                /** Folien von Tika in einem .pptx bisher und ihre Nummern ({@link PptxSlideOrder}). */
                private int slides;
                private int[] slideNumbers;
                private String sheet;
                private int tables;
                private final List<Block.Cell> cells = new ArrayList<>();
                private final StringBuilder cell = new StringBuilder();
                private long cellChars;
                private long row;
                private int column;
                private boolean cellOpen;
                private boolean header;
                private boolean continued;
                private Optional<Location> tableLocation = Optional.empty();
                Metadata metadata = new Metadata();

                @Override
                public void startElement(String uri, String localName, String qName, Attributes attributes)
                        throws SAXException {
                    progress();
                    String name = name(localName, qName);
                    synchronized (Reader.this) {
                        if (name.equals("head") || name.equals("script") || name.equals("style")) {
                            ignored++;
                            return;
                        }
                        if (name.equals("dl") && (headerList > 0 || outlookStage <= 1 && outlookHeader(outlookStage))) {
                            headerList++;
                            ignored++;
                            return;
                        }
                        if (ignored > 0) {
                            return;
                        }
                        if (tables > 0) {
                            startInTable(name, attributes);
                            return;
                        }
                        switch (name) {
                            case "div" -> startDiv(attributes.getValue("class"));
                            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                                flushText();
                                kind = TextKind.HEADING;
                                headingLevel = name.charAt(1) - '0';
                                if (entryTitle && name.equals("h1")) {
                                    // Name des Paketeintrags (etwa image1.png): von Tika, nicht aus der Quelle.
                                    dropping = true;
                                } else if (name.equals("h1") && outlookHeader(0)) {
                                    // Betreff einer Outlook-Mail: steht in den Kopfdaten (ADR 0026, Punkte 3 und 7);
                                    // geprüft am Ende gegen den Betreff, eine Überschrift des Inhalts bleibt.
                                    subject = true;
                                    outlookStage = 1;
                                } else if (sheetTitle && name.equals("h1")) {
                                    sheetHeading = true;
                                }
                                entryTitle = false;
                                sheetTitle = false;
                            }
                            case "ul", "ol" -> {
                                flushText();
                                lists.push(name.equals("ol"));
                            }
                            case "li" -> {
                                flushText();
                                items++;
                                kind = TextKind.ITEM;
                            }
                            case "pre" -> {
                                flushText();
                                pre++;
                                kind = TextKind.CODE;
                            }
                            case "table" -> {
                                flushText();
                                tables = 1;
                                tableLocation = location();
                                row = 0;
                                continued = false;
                            }
                            case "br" -> append('\n');
                            case "a" -> startAnchor(attributes.getValue("href"));
                            default -> {
                                if (BLOCK_ELEMENTS.contains(name)) {
                                    flushText();
                                    resetKind();
                                    if (headerList == 0 && !name.equals("body")) {
                                        // Anderer Inhalt beginnt: Der Kopfblock ist vorbei.
                                        outlookStage = 2;
                                    }
                                }
                            }
                        }
                    }
                }

                @Override
                public void endElement(String uri, String localName, String qName) throws SAXException {
                    progress();
                    String name = name(localName, qName);
                    synchronized (Reader.this) {
                        if (name.equals("head") || name.equals("script") || name.equals("style")) {
                            ignored = Math.max(0, ignored - 1);
                            return;
                        }
                        if (name.equals("dl") && headerList > 0) {
                            headerList--;
                            if (headerList == 0) {
                                outlookStage = 2;
                            }
                            ignored = Math.max(0, ignored - 1);
                            return;
                        }
                        if (ignored > 0) {
                            return;
                        }
                        if (tables > 0) {
                            endInTable(name);
                            return;
                        }
                        switch (name) {
                            case "div" -> {
                                flushText();
                                String cls = divs.isEmpty() ? "" : divs.pop();
                                if (cls.equals("embedded") || cls.equals("package-entry")) {
                                    embedded = Math.max(0, embedded - 1);
                                    entryTitle = false;
                                } else if (embedded == 0 && isSheet(cls)) {
                                    sheet = null;
                                }
                                resetKind();
                            }
                            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                                if (subject) {
                                    subject = false;
                                    String title = metadata == null ? null : metadata.get(TikaCoreProperties.TITLE);
                                    if (title != null && normalize(buffer.toString()).equals(normalize(title))) {
                                        buffer.setLength(0);
                                        links.clear();
                                    } else {
                                        outlookStage = 2;
                                    }
                                }
                                if (dropping) {
                                    buffer.setLength(0);
                                    links.clear();
                                    dropping = false;
                                } else if (sheetHeading) {
                                    String title = normalize(buffer.toString());
                                    sheet = title.isEmpty() ? null : title;
                                    sheetHeading = false;
                                }
                                flushText();
                                resetKind();
                            }
                            case "ul", "ol" -> {
                                flushText();
                                if (!lists.isEmpty()) {
                                    lists.pop();
                                }
                                resetKind();
                            }
                            case "li" -> {
                                flushText();
                                items = Math.max(0, items - 1);
                                resetKind();
                            }
                            case "pre" -> {
                                flushText();
                                pre = Math.max(0, pre - 1);
                                resetKind();
                            }
                            case "a" -> endAnchor();
                            default -> {
                                if (BLOCK_ELEMENTS.contains(name)) {
                                    flushText();
                                    resetKind();
                                }
                            }
                        }
                    }
                }

                @Override
                public void characters(char[] ch, int start, int length) throws SAXException {
                    progress();
                    // Unter der Sperre des Laufs, damit ein Abbruch von außen den Puffer ausgeben kann.
                    synchronized (Reader.this) {
                        if (ignored > 0) {
                            return;
                        }
                        if (anchor != null && label.length() < MAX_LABEL_CHARS) {
                            label.append(ch, start, Math.min(length, MAX_LABEL_CHARS - label.length()));
                        }
                        if (tables > 0) {
                            if (cellOpen) {
                                appendCell(ch, start, length);
                                return;
                            }
                            if (new String(ch, start, length).isBlank()) {
                                // Leerraum zwischen Zellen setzt Tika selbst.
                                return;
                            }
                        }
                        int offset = start;
                        int end = start + length;
                        while (offset < end) {
                            // Nie mehr als eine Blockgröße auf einmal anhängen: Der Puffer bleibt unter zwei Blockgrößen.
                            int take = Math.min(end - offset, maxBlockChars);
                            buffer.append(ch, offset, take);
                            offset += take;
                            while (buffer.length() > maxBlockChars) {
                                splitLong();
                            }
                        }
                    }
                }

                @Override
                public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
                    characters(ch, start, length);
                }

                private void startDiv(String value) throws SAXException {
                    flushText();
                    String cls = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
                    divs.push(cls);
                    if (cls.equals("embedded") || cls.equals("package-entry")) {
                        embedded++;
                        entryTitle = cls.equals("package-entry");
                        return;
                    }
                    if (embedded > 0) {
                        return;
                    }
                    if (isSheet(cls)) {
                        sheetTitle = true;
                        sheet = null;
                    } else if (cls.equals("page") && pdf()) {
                        page++;
                    } else if (cls.equals("slide") && legacyPowerPoint()) {
                        // .ppt: Tika gibt die Folien dort in Folienreihenfolge aus (ausgeblendete zählen mit).
                        slide++;
                    } else if (cls.equals("slide-content") && powerPoint()) {
                        // .pptx (AP10): Tika gibt die Folien nicht in Folienreihenfolge aus; die Nummer kommt aus
                        // PptxSlideOrder, sonst bleibt sie leer. Notizen danach gehören zu dieser Folie.
                        slide = nextSlide();
                    }
                    resetKind();
                }

                /** Blätter: in .xlsx div class=sheet, in .xls div class=page; der Name steht im ersten h1. */
                private boolean isSheet(String cls) {
                    return cls.equals("sheet") || cls.equals("page") && spreadsheet();
                }

                private boolean pdf() {
                    return "application/pdf".equals(detectedType());
                }

                    /**
                 * Noch vor dem Inhalt einer Outlook-Mail: Tika schreibt dort Betreff ({@code h1}) und Von/An
                 * ({@code dl}), die schon in den Kopfdaten stehen.
                 */
                private boolean outlookHeader(int stage) {
                    if (outlookStage != stage) {
                        return false;
                    }
                    if (Doc.this.blocks == 0 && buffer.toString().isBlank() && embedded == 0
                            && "application/vnd.ms-outlook".equals(detectedType())) {
                        return true;
                    }
                    outlookStage = 2;
                    return false;
                }

                private boolean powerPoint() {
                    String type = detectedType();
                    return type.startsWith("application/vnd.openxmlformats-officedocument.presentationml.")
                            || type.startsWith("application/vnd.ms-powerpoint.presentation.macroenabled")
                            || type.startsWith("application/vnd.ms-powerpoint.slideshow.macroenabled");
                }

                /** Die Foliennummer der nächsten Folie von Tika; 0, wenn die Zuordnung fehlt oder nicht mehr passt. */
                private int nextSlide() {
                    if (slideNumbers == null) {
                        TikaInputStream in = stream;
                        Path file = null;
                        try {
                            file = in != null && in.hasFile() ? in.getPath() : null;
                        } catch (IOException e) {
                            file = null;
                        }
                        slideNumbers = PptxSlideOrder.of(file).orElse(new int[0]);
                    }
                    slides++;
                    // Mehr Folien als zugeordnet: ab hier keine Nummer (lieber keine als eine falsche).
                    return slides <= slideNumbers.length ? slideNumbers[slides - 1] : 0;
                }

                private boolean legacyPowerPoint() {
                    return "application/vnd.ms-powerpoint".equals(detectedType());
                }

                private boolean spreadsheet() {
                    String type = detectedType();
                    return type.equals("application/vnd.ms-excel") || type.startsWith(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml")
                            || type.startsWith("application/vnd.ms-excel.sheet");
                }

                private String detectedType() {
                    String type = metadata.get(Metadata.CONTENT_TYPE);
                    if (type == null || type.isBlank()) {
                        type = mediaType == null ? "" : mediaType;
                    }
                    return type.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
                }

                private Optional<Location> location() {
                    if (embedded > 0) {
                        return Optional.empty();
                    }
                    if (sheet != null) {
                        return Optional.of(new Location(Optional.empty(), Optional.empty(), Optional.of(sheet)));
                    }
                    if (slide > 0) {
                        return Optional.of(Location.slide(slide));
                    }
                    return page > 0 ? Optional.of(Location.page(page)) : Optional.empty();
                }

                private void resetKind() {
                    if (pre > 0) {
                        kind = TextKind.CODE;
                    } else if (items > 0) {
                        kind = TextKind.ITEM;
                    } else {
                        kind = TextKind.PARAGRAPH;
                    }
                }

                private void startAnchor(String href) {
                    anchor = href == null ? "" : href.strip();
                    label.setLength(0);
                }

                private void endAnchor() {
                    // Verweise ohne Ziel sind Artefakte (leere Anker aus Word).
                    List<Link> into = tables > 0 && cellOpen ? cellLinks : links;
                    if (anchor != null && !anchor.isEmpty() && links.size() + cellLinks.size() < MAX_LINKS) {
                        into.add(new Link(anchor, Optional.of(normalize(label.toString()))));
                    }
                    anchor = null;
                    label.setLength(0);
                }

                private void startInTable(String name, Attributes attributes) throws SAXException {
                    switch (name) {
                        case "table" -> {
                            tables++;
                            appendCell(" ");
                        }
                        case "tr" -> {
                            if (tables == 1) {
                                // Text vor den Zeilen (etwa eine Beschriftung) steht vor der Tabelle.
                                if (row == 0 && cells.isEmpty() && !continued && !buffer.isEmpty()) {
                                    flushText();
                                }
                                column = 0;
                            } else {
                                appendCell("\n");
                            }
                        }
                        case "td", "th" -> {
                            if (tables == 1) {
                                closeCell();
                                cellOpen = true;
                                header = name.equals("th");
                            } else {
                                appendCell("\t");
                            }
                        }
                        case "br" -> appendCell("\n");
                        case "a" -> startAnchor(attributes.getValue("href"));
                        default -> {
                            if (cellOpen && BLOCK_ELEMENTS.contains(name)) {
                                appendCell(" ");
                            }
                        }
                    }
                }

                private void endInTable(String name) throws SAXException {
                    switch (name) {
                        case "table" -> {
                            tables--;
                            if (tables == 0) {
                                closeCell();
                                emitTablePiece();
                                // Text in der Tabelle außerhalb der Zellen (etwa eine Beschriftung) als Absatz.
                                flushText();
                                resetKind();
                            }
                        }
                        case "tr" -> {
                            if (tables == 1) {
                                closeCell();
                                row++;
                                if (cellChars >= maxBlockChars / 2) {
                                    emitTablePiece();
                                }
                            }
                        }
                        case "td", "th" -> {
                            if (tables == 1) {
                                closeCell();
                            }
                        }
                        case "a" -> endAnchor();
                        default -> {
                            if (cellOpen && BLOCK_ELEMENTS.contains(name)) {
                                appendCell(" ");
                            }
                        }
                    }
                }

                private void appendCell(String text) throws SAXException {
                    appendCell(text.toCharArray(), 0, text.length());
                }

                /** Hängt Text an die offene Zelle; das Stück bleibt unter der Blockgröße, notfalls mitten in der Zelle. */
                private void appendCell(char[] ch, int start, int length) throws SAXException {
                    if (!cellOpen) {
                        return;
                    }
                    int offset = start;
                    int end = start + length;
                    while (offset < end) {
                        int take = Math.min(end - offset, maxBlockChars);
                        cell.append(ch, offset, take);
                        offset += take;
                        while (cellChars + cell.length() > maxBlockChars) {
                            if (!cells.isEmpty()) {
                                emitTablePiece();
                                continue;
                            }
                            int cut = Math.min(cell.length(), maxBlockChars);
                            if (Character.isHighSurrogate(cell.charAt(cut - 1))) {
                                cut--;
                            }
                            String part = cell.substring(0, cut);
                            cell.delete(0, cut);
                            links.addAll(cellLinks);
                            cellLinks.clear();
                            cells.add(new Block.Cell(row, column, part, header, Optional.empty()));
                            cellChars += part.length();
                            emitTablePiece();
                        }
                    }
                }

                private void closeCell() throws SAXException {
                    if (!cellOpen) {
                        return;
                    }
                    String text = normalize(cell.toString());
                    cell.setLength(0);
                    cellOpen = false;
                    if (cellChars + text.length() > maxBlockChars && !cells.isEmpty()) {
                        emitTablePiece();
                    }
                    cells.add(new Block.Cell(row, column, text, header, Optional.empty()));
                    cellChars += text.length();
                    links.addAll(cellLinks);
                    cellLinks.clear();
                    column++;
                    // Auch viele leere Zellen bleiben in begrenzten Stücken.
                    if (cells.size() >= MAX_TABLE_CELLS) {
                        emitTablePiece();
                    }
                }

                private void emitTablePiece() throws SAXException {
                    if (cells.isEmpty()) {
                        return;
                    }
                    List<Block.Cell> piece = List.copyOf(cells);
                    boolean empty = cellChars == 0;
                    cells.clear();
                    cellChars = 0;
                    List<Link> pieceLinks = List.copyOf(links);
                    links.clear();
                    if (!empty) {
                        emitTable(piece, continued, pieceLinks, tableLocation);
                        continued = true;
                    }
                }

                /** Trennzeichen zählen zur Blockgröße wie Text. */
                private void append(char separator) throws SAXException {
                    buffer.append(separator);
                    while (buffer.length() > maxBlockChars) {
                        splitLong();
                    }
                }

                /** Gibt einen zu langen Block bis zum letzten Leerraum vor der Grenze aus, notfalls genau an der Grenze. */
                private void splitLong() throws SAXException {
                    int cut = maxBlockChars;
                    for (int i = maxBlockChars; i > maxBlockChars / 2; i--) {
                        if (Character.isWhitespace(buffer.charAt(i - 1))) {
                            cut = i;
                            break;
                        }
                    }
                    if (Character.isHighSurrogate(buffer.charAt(cut - 1))) {
                        cut--;
                    }
                    String part = buffer.substring(0, cut);
                    buffer.delete(0, cut);
                    emitPending(part);
                }

                /** Gibt den gepufferten Text als Block der aktuellen Art aus. */
                void flushText() throws SAXException {
                    synchronized (Reader.this) {
                        if (buffer.isEmpty()) {
                            links.clear();
                            return;
                        }
                        String text = buffer.toString();
                        buffer.setLength(0);
                        emitPending(text);
                    }
                }

                /** Am Ende oder bei einem Abbruch: auch eine offene Tabelle ausgeben. */
                void flush() throws SAXException {
                    synchronized (Reader.this) {
                        if (tables > 0) {
                            closeCell();
                            emitTablePiece();
                            tables = 0;
                        }
                        flushText();
                    }
                }

                private void emitPending(String raw) throws SAXException {
                    String text = kind == TextKind.CODE ? raw.replace("\r\n", "\n").replace('\r', '\n').strip()
                            : normalize(raw);
                    List<Link> blockLinks = List.copyOf(links);
                    links.clear();
                    if (text.isEmpty()) {
                        return;
                    }
                    boolean ordered = !lists.isEmpty() && lists.peek();
                    int level = kind == TextKind.HEADING ? headingLevel : Math.max(1, lists.size());
                    emitText(kind, level, ordered, text, blockLinks, location());
                }

                private void progress() throws Stop {
                    lastProgress.set(System.nanoTime());
                    if (expired) {
                        // Gesamtlaufzeit dieses Teils erreicht: nur dieser Teil endet.
                        throw new Stop(StatusReason.TIMEOUT);
                    }
                }
            }
        }
    }

    /** Hash und Größe eines ausgepackten Teils; mit {@code failure}, wenn er nicht vollständig gelesen ist. */
    private record Spooled(String sha256, long bytes, Outcome failure) {
    }

    private static EmbeddedEntry withOutcome(EmbeddedEntry entry, Outcome outcome) {
        return new EmbeddedEntry(entry.id(), entry.sha256(), entry.displayName(), entry.mediaType(), entry.position(),
                entry.parent(), entry.depth(), entry.size(), outcome);
    }

    private static String baseType(String type) {
        return type == null ? "" : type.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    }

    private static java.security.MessageDigest sha256Digest() {
        try {
            return java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(InputStream in) throws IOException {
        java.security.MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[64 * 1024];
        for (int read; (read = in.read(buffer)) >= 0; ) {
            digest.update(buffer, 0, read);
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    /** Art des Textblocks, den der Puffer gerade sammelt. */
    private enum TextKind { PARAGRAPH, HEADING, ITEM, CODE }

    /** Zeilenenden vereinheitlicht, außen ohne Leerraum. */
    private static String normalize(String raw) {
        return raw.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private static String name(String localName, String qName) {
        String name = localName == null || localName.isEmpty() ? qName : localName;
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static Throwable cause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return current;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return null;
    }

    private static boolean zipBomb(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("zip bomb")) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    /** Beendet das Lesen an einer Grenze; was bis dahin gelesen ist, bleibt. */
    private static final class Stop extends SAXException {

        final StatusReason reason;

        Stop(StatusReason reason) {
            super(reason.code());
            this.reason = reason;
        }
    }
}
