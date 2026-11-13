package com.aresstack.spaceharvester.source;

import com.aresstack.spaceharvester.application.assets.Block;
import com.aresstack.spaceharvester.application.assets.BlockSink;
import com.aresstack.spaceharvester.application.assets.DocumentHead;
import com.aresstack.spaceharvester.application.assets.DocumentStructure;
import com.aresstack.spaceharvester.application.assets.ExtractionLimits;
import com.aresstack.spaceharvester.application.assets.ExtractionStatus;
import com.aresstack.spaceharvester.application.assets.ExtractionStore;
import com.aresstack.spaceharvester.application.assets.Link;
import com.aresstack.spaceharvester.application.assets.Location;
import com.aresstack.spaceharvester.application.assets.Outcome;
import com.aresstack.spaceharvester.application.assets.ReadExtent;
import com.aresstack.spaceharvester.application.assets.StatusReason;
import com.aresstack.spaceharvester.application.assets.StructureSink;
import com.aresstack.spaceharvester.application.assets.StructuredDocument;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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
    public static final String VERSION = "3.3-s2";

    /** Tika-Konfiguration: alle Standard-Parser außer der Texterkennung. */
    private static final String CONFIG = """
            <?xml version="1.0" encoding="UTF-8"?>
            <properties>
              <parsers>
                <parser class="org.apache.tika.parser.DefaultParser">
                  <parser-exclude class="org.apache.tika.parser.ocr.TesseractOCRParser"/>
                </parser>
              </parsers>
            </properties>
            """;
    private static final Set<String> BLOCK_ELEMENTS = Set.of("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li",
            "dt", "dd", "tr", "pre", "blockquote", "table", "ul", "ol", "dl", "section", "article", "body");
    private static final Set<String> MAIL_TYPES = Set.of("message/rfc822", "application/vnd.ms-outlook");
    private static final long POLL_MILLIS = 50;
    private static final int MAX_LINKS = 1_000;
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
        Objects.requireNonNull(original, "original");
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
            if (ExtractionLimits.exceeds(size, limits.memoryLimit(mediaType))) {
                return empty(Outcome.of(ExtractionStatus.EXCLUDED, StatusReason.MEMORY_LIMIT));
            }
            if (limits.tempSpaceReserve() > 0 && usableTempSpace() < limits.tempSpaceReserve()) {
                return empty(Outcome.of(ExtractionStatus.FAILED, StatusReason.TEMP_SPACE_LOW));
            }
            AutoDetectParser tika = parser();
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
            return run(tika, original, title, mediaType, size, limits, target);
        } finally {
            target.close();
        }
    }

    /** Liest in einem eigenen Thread und wacht über Laufzeit und Fortschritt. */
    private StructuredDocument run(AutoDetectParser tika, Path original, String title, String mediaType, long size,
                                   ExtractionLimits limits, BlockSink target) {
        Reader reader = new Reader(tika, original, title, mediaType, size, limits, target);
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
        long started = System.nanoTime();
        long idle = limits.idleTimeout().toNanos();
        long total = limits.totalTimeout().toNanos();
        try {
            while (true) {
                worker.join(POLL_MILLIS);
                if (!worker.isAlive()) {
                    return reader.result();
                }
                long now = System.nanoTime();
                if (idle > 0 && now - reader.lastProgress.get() > idle || total > 0 && now - started > total) {
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

    /** Ein Lauf: Tika im Arbeitsthread, Ergebnis und Abbruch unter einer Sperre. */
    private final class Reader {

        private final AutoDetectParser tika;
        private final Path original;
        private final String title;
        private final String mediaType;
        private final long size;
        private final ExtractionLimits limits;
        private final BlockSink target;
        final AtomicLong lastProgress = new AtomicLong(System.nanoTime());
        private volatile TikaInputStream stream;
        private final Handler handler = new Handler();
        private long blocks;
        private long chars;
        private boolean stopped;
        private StatusReason stopReason;
        private StructuredDocument result;

        Reader(AutoDetectParser tika, Path original, String title, String mediaType, long size,
               ExtractionLimits limits, BlockSink target) {
            this.tika = tika;
            this.original = original;
            this.title = title;
            this.mediaType = mediaType;
            this.size = size;
            this.limits = limits;
            this.target = target;
        }

        void read() {
            Metadata metadata = new Metadata();
            if (title != null && !title.isBlank()) {
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, title);
            }
            if (mediaType != null && !mediaType.isBlank()) {
                metadata.set(TikaCoreProperties.CONTENT_TYPE_HINT, mediaType);
            }
            ParseContext context = new ParseContext();
            // Enthaltene Dateien wie bisher im Text des Behälters (eigene Dokumente erst mit AP7).
            context.set(Parser.class, tika);
            Outcome outcome;
            try (TikaInputStream in = TikaInputStream.get(original, metadata)) {
                stream = in;
                handler.metadata = metadata;
                (parseWith == null ? tika : parseWith).parse(in, handler, metadata, context);
                handler.flush();
                outcome = embeddedFailure(metadata) ? partial(StatusReason.ENTRIES_NOT_READABLE) : null;
            } catch (Stop e) {
                outcome = truncatedOrFailed(e.reason);
            } catch (EncryptedDocumentException e) {
                outcome = truncatedOrFailed(StatusReason.ENCRYPTED);
            } catch (OutOfMemoryError e) {
                outcome = truncatedOrFailed(StatusReason.MEMORY_LIMIT);
            } catch (Exception | LinkageError e) {
                Throwable stop = cause(e, Stop.class);
                if (stop != null) {
                    outcome = truncatedOrFailed(((Stop) stop).reason);
                } else if (cause(e, EncryptedDocumentException.class) != null) {
                    outcome = truncatedOrFailed(StatusReason.ENCRYPTED);
                } else if (zipBomb(e)) {
                    outcome = truncatedOrFailed(StatusReason.UNPACKED_TOO_LARGE);
                } else {
                    LOG.log(System.Logger.Level.INFO, "Extraktion gescheitert ({0})", e.getClass().getName());
                    outcome = truncatedOrFailed(StatusReason.CORRUPT);
                }
            }
            synchronized (this) {
                if (stopped) {
                    return;
                }
                // Nach vollständigem Lesen ist der Puffer leer; drain betrifft nur Abbrüche.
                StatusReason abort = outcome == null ? null : outcome.reason().orElse(StatusReason.CORRUPT);
                if (abort != null && abort != StatusReason.TOO_LARGE && abort != StatusReason.TEXT_RATIO_EXCEEDED
                        && drain()) {
                    // Bis zum Abbruch gesammelten Text behalten; der Grund bleibt der Abbruch.
                    outcome = truncatedOrFailed(abort);
                }
                if (outcome == null) {
                    outcome = chars == 0 ? Outcome.of(ExtractionStatus.UNSUPPORTED, StatusReason.NO_TEXT)
                            : Outcome.ok(new ReadExtent(size, chars));
                }
                result = new StructuredDocument(head(metadata), outcome, List.of(), blocks, chars);
                stopped = true;
            }
        }

        /** Bricht von außen ab; danach nimmt der Empfänger keinen Block mehr an. */
        synchronized StructuredDocument stop(StatusReason reason) {
            if (result != null) {
                return result;
            }
            drain();
            stopped = true;
            stopReason = reason;
            result = new StructuredDocument(DocumentHead.EMPTY, truncatedOrFailed(reason), List.of(), blocks, chars);
            return result;
        }

        /** Gibt den noch gepufferten Text aus (unter der Sperre); true, wenn dabei Zeichen dazukamen. */
        private synchronized boolean drain() {
            long before = chars;
            try {
                handler.flush();
            } catch (SAXException | RuntimeException e) {
                // Grenze erreicht: Der angenommene Teil bleibt.
            }
            return chars > before;
        }

        /** Enthaltene Dateien, die Tika nicht lesen konnte, meldet es nur in den Metadaten des Behälters. */
        private static boolean embeddedFailure(Metadata metadata) {
            return metadata.get(TikaCoreProperties.EMBEDDED_EXCEPTION) != null
                    || metadata.get(TikaCoreProperties.EMBEDDED_BYTES_EXCEPTION) != null;
        }

        /** Vollständig gelesen, aber mit fehlenden Teilen: gekürzt; ohne jeden Text gescheitert. */
        private Outcome partial(StatusReason reason) {
            return chars == 0 ? Outcome.of(ExtractionStatus.FAILED, reason)
                    : Outcome.truncated(reason, new ReadExtent(size, chars));
        }

        synchronized StructuredDocument result() {
            if (result == null) {
                // Der Thread endete ohne Ergebnis (etwa durch einen Fehler außerhalb von Tika).
                result = new StructuredDocument(DocumentHead.EMPTY, truncatedOrFailed(StatusReason.CORRUPT),
                        List.of(), blocks, chars);
                stopped = true;
            }
            return result;
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
        private synchronized void emitText(TextKind kind, int level, boolean ordered, String text, List<Link> links,
                                           Optional<Location> location) throws Stop {
            checkRunning();
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
                throw new Stop(StatusReason.TOO_LARGE);
            }
            checkRatio();
        }

        /** Nimmt ein Tabellenstück an; an der Zeichengrenze nur die Zellen bis dahin. */
        private synchronized void emitTable(List<Block.Cell> cells, boolean continued, List<Link> links,
                                            Optional<Location> location) throws Stop {
            checkRunning();
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
                throw new Stop(StatusReason.TOO_LARGE);
            }
            checkRatio();
        }

        private void checkRunning() throws Stop {
            if (stopped) {
                throw new Stop(stopReason == null ? StatusReason.TIMEOUT : stopReason);
            }
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
            boolean mail = mediaType != null && MAIL_TYPES.contains(mediaType.split(";", 2)[0].strip()
                    .toLowerCase(Locale.ROOT));
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
            private String anchor;
            private final StringBuilder label = new StringBuilder();
            /** Klassen der offenen div-Elemente. */
            private final ArrayDeque<String> divs = new ArrayDeque<>();
            private int embedded;
            private boolean entryTitle;
            private boolean dropping;
            private boolean sheetTitle;
            private boolean sheetHeading;
            private int page;
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
                if (anchor != null && !anchor.isEmpty() && links.size() < MAX_LINKS) {
                    links.add(new Link(anchor, Optional.of(normalize(label.toString()))));
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
                column++;
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

            private void progress() {
                lastProgress.set(System.nanoTime());
            }
        }
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
