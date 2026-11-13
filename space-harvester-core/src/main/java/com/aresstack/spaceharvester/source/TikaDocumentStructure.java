package com.aresstack.spaceharvester.source;

import com.aresstack.spaceharvester.application.assets.Block;
import com.aresstack.spaceharvester.application.assets.BlockSink;
import com.aresstack.spaceharvester.application.assets.DocumentHead;
import com.aresstack.spaceharvester.application.assets.DocumentStructure;
import com.aresstack.spaceharvester.application.assets.ExtractionLimits;
import com.aresstack.spaceharvester.application.assets.ExtractionStatus;
import com.aresstack.spaceharvester.application.assets.ExtractionStore;
import com.aresstack.spaceharvester.application.assets.Outcome;
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
    public static final String VERSION = "3.3-s1";

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
            AutoDetectParser tika = parser();
            // Gelesen wird nach dem Inhalt; die Schranke gilt deshalb auch für den an der Datei erkannten Typ, bei
            // Abweichung der strengere Wert (Architekt 13.11.2026).
            long memoryLimit = stricter(limits.memoryLimit(mediaType),
                    limits.memoryLimit(detect(tika, original, title)));
            if (ExtractionLimits.exceeds(size, memoryLimit)) {
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

    /** Die strengere zweier Schranken; {@code 0} heißt ohne Schranke. */
    private static long stricter(long first, long second) {
        if (first == 0) {
            return second;
        }
        return second == 0 ? first : Math.min(first, second);
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
            // Bis AP7 (Grenze je Teil): Ist der Behälter größer als die Standardschranke, bleiben enthaltene Teile
            // nicht nachgewiesener Formate ungelesen, sonst umginge eine große eingebettete Mail die Schranke.
            long standard = limits.memoryLimits().getOrDefault(ExtractionLimits.ANY_TYPE, 0L);
            GuardedEmbedded guard = null;
            if (ExtractionLimits.exceeds(size, standard)) {
                guard = new GuardedEmbedded(context);
                context.set(EmbeddedDocumentExtractor.class, guard);
            }
            Outcome outcome;
            try (TikaInputStream in = TikaInputStream.get(original, metadata)) {
                stream = in;
                (parseWith == null ? tika : parseWith).parse(in, handler, metadata, context);
                handler.flush();
                outcome = embeddedFailure(metadata) || guard != null && guard.skipped > 0
                        ? partial(StatusReason.ENTRIES_NOT_READABLE) : null;
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

        /** Liest enthaltene Teile nur in nachgewiesenen Formaten (ohne Schranke) und Bilder; zählt die übrigen. */
        private final class GuardedEmbedded extends ParsingEmbeddedDocumentExtractor {

            volatile int skipped;

            GuardedEmbedded(ParseContext context) {
                super(context);
            }

            @Override
            public void parseEmbedded(InputStream stream, ContentHandler handler, Metadata metadata,
                                      boolean outputHtml) throws SAXException, IOException {
                TikaInputStream in = TikaInputStream.get(stream);
                MediaType type = tika.getDetector().detect(in, metadata).getBaseType();
                if (type.getType().equals("image") || limits.memoryLimit(type.toString()) == 0) {
                    super.parseEmbedded(in, handler, metadata, outputHtml);
                } else {
                    skipped++;
                }
            }
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

        /** Nimmt einen Block an, solange der Lauf nicht beendet ist, und prüft dabei die Zeichengrenzen. */
        private synchronized void emit(String text) throws Stop {
            if (stopped) {
                throw new Stop(stopReason == null ? StatusReason.TIMEOUT : stopReason);
            }
            long room = limits.maxChars() > 0 ? limits.maxChars() - chars : Long.MAX_VALUE;
            boolean cut = text.length() > room;
            String accepted = cut ? text.substring(0, (int) Math.max(0, room)) : text;
            if (!accepted.isEmpty()) {
                target.accept(new Block.Paragraph(blocks, Optional.empty(), List.of(), accepted));
                blocks++;
                chars += accepted.length();
            }
            if (cut) {
                throw new Stop(StatusReason.TOO_LARGE);
            }
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

        /** Sammelt Text bis zum Ende eines Blockelements und teilt zu lange Absätze an Leerraum. */
        private final class Handler extends DefaultHandler {

            private final StringBuilder buffer = new StringBuilder();
            private int ignored;
            private boolean cellOpen;

            @Override
            public void startElement(String uri, String localName, String qName, Attributes attributes)
                    throws SAXException {
                progress();
                String name = name(localName, qName);
                if (name.equals("head") || name.equals("script") || name.equals("style")) {
                    ignored++;
                } else if (BLOCK_ELEMENTS.contains(name)) {
                    flush();
                } else if (name.equals("td") || name.equals("th")) {
                    if (cellOpen) {
                        append('\t');
                    }
                    cellOpen = true;
                } else if (name.equals("br")) {
                    append('\n');
                }
            }

            @Override
            public void endElement(String uri, String localName, String qName) throws SAXException {
                progress();
                String name = name(localName, qName);
                if (name.equals("head") || name.equals("script") || name.equals("style")) {
                    ignored = Math.max(0, ignored - 1);
                } else if (BLOCK_ELEMENTS.contains(name)) {
                    flush();
                    if (name.equals("tr")) {
                        cellOpen = false;
                    }
                }
            }

            @Override
            public void characters(char[] ch, int start, int length) throws SAXException {
                progress();
                if (ignored > 0) {
                    return;
                }
                int offset = start;
                int end = start + length;
                // Unter der Sperre des Laufs, damit ein Abbruch von außen den Puffer ausgeben kann.
                synchronized (Reader.this) {
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

            /** Trennzeichen zählen zur Blockgröße wie Text. */
            private void append(char separator) throws SAXException {
                synchronized (Reader.this) {
                    buffer.append(separator);
                    while (buffer.length() > maxBlockChars) {
                        splitLong();
                    }
                }
            }

            /** Gibt einen zu langen Absatz bis zum letzten Leerraum vor der Grenze aus, notfalls genau an der Grenze. */
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
                emitText(part);
            }

            void flush() throws SAXException {
                String text;
                synchronized (Reader.this) {
                    if (buffer.isEmpty()) {
                        return;
                    }
                    text = buffer.toString();
                    buffer.setLength(0);
                }
                emitText(text);
            }

            private void emitText(String raw) throws SAXException {
                String text = raw.replace("\r\n", "\n").replace('\r', '\n').strip();
                if (!text.isEmpty()) {
                    emit(text);
                }
            }

            private void progress() {
                lastProgress.set(System.nanoTime());
            }
        }
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
