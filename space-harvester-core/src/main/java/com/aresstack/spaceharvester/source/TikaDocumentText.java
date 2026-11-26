package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.astu.acropolis.knowledge.SourceFileTextExtractor;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.astu.knowledge.SourceAddress;
import com.aresstack.enterpriseai.proasteion.application.knowledge.DocumentSourceFileTextExtractor;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentCategory;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.ContentDetector;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.DetectedContentType;
import com.aresstack.enterpriseai.proasteion.emporion.deigma.tika.DocumentExtraction;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.spaceharvester.application.assets.DocumentText;
import com.aresstack.spaceharvester.application.assets.DocumentTextException;
import com.aresstack.spaceharvester.application.assets.ExtractionLimits;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Typerkennung am Inhalt und Textextraktion (#183) mit dem Ablauf des Enterprise AI Client, aus der Kopie in
 * {@code enterprise-ai-sources}: Erkennung über {@link DocumentExtraction#detector()} ({@code TikaContentDetector}),
 * Text über {@link DocumentSourceFileTextExtractor} mit {@link DocumentExtraction#registry()} (Markdown und Klartext
 * direkt, sonst Apache Tika) – dieselbe Zusammenstellung wie dort für Confluence-Anhänge und „Lokale Dateien“. Bilder
 * haben hier keinen Text (keine Bildbeschreibung über diesen Weg, dafür gibt es den {@code ImageDescriber}).
 *
 * <p>Dazu kommen die Grenzen des Dienstes, weil Tika fremde Dateien parst:
 * <ul>
 *   <li>Größe: die Speicherschranke aus {@code assets.extraction.memory-limits} (Vorgabe 20 MiB, wie Confluence-
 *       Antworten, ADR 0002); größere Dateien werden nicht geparst ({@link #TikaDocumentText(ExtractionLimits)}).</li>
 *   <li>Zeit: {@code assets.extraction.idle-timeout} je Datei (Vorgabe 30 s), in einem eigenen Thread; danach wird er
 *       unterbrochen und das Ergebnis verworfen.</li>
 *   <li>Gleichzeitigkeit: {@code assets.extraction.parallel} Läufe (Vorgabe 2); ein hängender Lauf hält seinen Platz,
 *       bis er endet, sodass nie mehr als so viele Threads in Tika stecken.</li>
 *   <li>Textlänge: ohne Grenze (F39-10); nur ein ausdrücklich übergebenes Zeichenbudget kürzt.</li>
 * </ul>
 * Die letzten Ergebnisse bleiben im Speicher ({@value #CACHE_ENTRIES} Einträge nach Inhalts-Hash), weil Volltextindex
 * und Anreicherungen dieselbe Datei kurz nacheinander projizieren. Meldungen nennen weder Dateinamen noch Inhalte.
 */
public final class TikaDocumentText implements DocumentText {

    private static final System.Logger LOG = System.getLogger(TikaDocumentText.class.getName());

    public static final String ID = "tika";
    /** Tika-Linie und Regeln dieser Klasse; steigt, wenn sich der gelesene Text ändern kann. */
    public static final String VERSION = "3.3-2";

    /** Ohne Zeichenbudget: der ganze Text (F39-10); Tika liefert ihn ohnehin als einen String. */
    private static final int UNBOUNDED = Integer.MAX_VALUE;
    /** Ohne Zeitgrenze ({@code 0} in den Einstellungen). */
    private static final Duration NO_TIMEOUT = Duration.ofNanos(Long.MAX_VALUE);
    static final int CACHE_ENTRIES = 8;
    /** So viele Bytes reichen für die Signatur, wenn die Datei zu groß zum Parsen ist. */
    private static final int PREFIX_BYTES = 64 * 1024;
    private static final KnowledgeResourceId RESOURCE_ID = KnowledgeResourceId.of("asset:document");
    private static final SourceAddress SOURCE = SourceAddress.of("asset://space-harvester");

    private final long maxBytes;
    private final int maxChars;
    private final Duration timeout;
    private final Semaphore slots;
    private final Map<String, Object> recent = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };
    /** Tika lädt beim ersten Gebrauch, nicht beim Start. */
    private volatile Engine engine;

    /** Mit den Vorgaben von {@code space-harvester.assets.extraction.*} ({@link ExtractionLimits#DEFAULTS}). */
    public TikaDocumentText() {
        this(ExtractionLimits.DEFAULTS);
    }

    /**
     * Mit den Schutzgrenzen aus {@code space-harvester.assets.extraction.*} (F39-10: dieselben Einstellungen wie die
     * strukturierte Extraktion, keine eigenen festen Werte). Dieser Weg hält die ganze Datei im Speicher, darum gilt
     * als Größe die Speicherschranke für alle Typen ({@code memory-limits."*"}, Vorgabe 20 MiB) und, wenn gesetzt, die
     * kleinere {@code max-file-size}. Tika meldet hier keinen Fortschritt, darum gilt {@code idle-timeout} (Vorgabe
     * 30 s) als Laufzeit je Datei, ohne sie {@code total-timeout}. {@code max-chars} kürzt den Text, {@code parallel}
     * begrenzt die gleichzeitigen Läufe. {@code 0} heißt jeweils: keine Grenze.
     */
    public TikaDocumentText(ExtractionLimits limits) {
        this(maxBytes(limits), limits.maxChars() > 0 ? (int) Math.min(limits.maxChars(), UNBOUNDED) : UNBOUNDED,
                timeout(limits), limits.parallel());
    }

    /** Mit ausdrücklichen Grenzen; gleichzeitige Läufe wie in {@link ExtractionLimits#DEFAULTS}. */
    public TikaDocumentText(long maxBytes, int maxChars, Duration timeout) {
        this(maxBytes, maxChars, timeout, ExtractionLimits.DEFAULTS.parallel());
    }

    private TikaDocumentText(long maxBytes, int maxChars, Duration timeout, int parallel) {
        if (maxBytes < 1 || maxChars < 1 || timeout == null || timeout.isNegative() || timeout.isZero()
                || parallel < 1) {
            throw new IllegalArgumentException("Grenzen müssen positiv sein");
        }
        this.maxBytes = maxBytes;
        this.maxChars = maxChars;
        this.timeout = timeout;
        this.slots = new Semaphore(parallel);
    }

    private static long maxBytes(ExtractionLimits limits) {
        long memory = limits.memoryLimits().getOrDefault(ExtractionLimits.ANY_TYPE, 0L);
        long file = limits.maxFileBytes();
        if (memory > 0 && file > 0) {
            return Math.min(memory, file);
        }
        long either = Math.max(memory, file);
        return either > 0 ? either : Long.MAX_VALUE;
    }

    private static Duration timeout(ExtractionLimits limits) {
        if (!limits.idleTimeout().isZero()) {
            return limits.idleTimeout();
        }
        return limits.totalTimeout().isZero() ? NO_TIMEOUT : limits.totalTimeout();
    }

    /** Gemeinsame Instanz mit den Vorgaben ({@link ExtractionLimits#DEFAULTS}), nur wo keine Einstellungen vorliegen. */
    public static TikaDocumentText shared() {
        return Shared.INSTANCE;
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
    public Optional<String> detect(byte[] content) {
        if (content == null || content.length == 0) {
            return Optional.empty();
        }
        byte[] sample = content.length <= maxBytes ? content : Arrays.copyOf(content, PREFIX_BYTES);
        DetectedContentType type;
        try {
            // Ohne Dateiname und Angabe der Quelle: nur der Inhalt zählt.
            type = limited(() -> engine().detector.detect(null, null, sample));
        } catch (DocumentTextException e) {
            LOG.log(System.Logger.Level.INFO, "Typerkennung am Inhalt ohne Ergebnis ({0})", e.reason().code());
            return Optional.empty();
        }
        if (type == null || type.category() == ContentCategory.UNKNOWN || type.mimeType() == null
                || type.mimeType().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(type.mimeType());
    }

    /**
     * Erst am Inhalt allein ({@link #detect(byte[])}); lässt der den Typ offen, fragt die Kopie wie der Enterprise AI
     * Client mit dem Dateinamen ({@code TikaContentDetector}: Endung, sonst Tika mit dem Namen als Hinweis). Das
     * Ergebnis zählt nur, wenn es zum Inhalt passt ({@link #refine}).
     */
    @Override
    public Optional<String> detect(String fileName, byte[] content) {
        Optional<String> byContent = detect(content);
        if (fileName == null || fileName.isBlank() || content == null || content.length == 0
                || byContent.isPresent() && !OPEN.contains(byContent.get())) {
            return byContent;
        }
        byte[] sample = content.length <= maxBytes ? content : Arrays.copyOf(content, PREFIX_BYTES);
        DetectedContentType named;
        try {
            named = limited(() -> engine().detector.detect(fileName, null, sample));
        } catch (DocumentTextException e) {
            LOG.log(System.Logger.Level.INFO, "Typerkennung mit Dateiname ohne Ergebnis ({0})", e.reason().code());
            return byContent;
        }
        if (named == null || named.category() == ContentCategory.UNKNOWN || named.mimeType() == null
                || named.mimeType().isBlank()) {
            return byContent;
        }
        return Optional.of(refine(byContent.orElse(null), named.mimeType().toLowerCase(Locale.ROOT)));
    }

    /** Am Inhalt erkannte Typen, die den Dateityp offenlassen: Klartext und bloße Behälter. */
    private static final java.util.Set<String> OPEN = java.util.Set.of("text/plain", "application/zip",
            "application/x-tika-ooxml", "application/x-tika-msoffice", "application/x-ole-storage");

    /**
     * Der Typ nach Name, soweit der Inhalt ihn zulässt: ohne Signatur der Name (wie im Enterprise AI Client), bei
     * Klartext nur ein Textformat (Markdown, CSV …), in einem ZIP nur OOXML oder OpenDocument, in OLE2 nur die alten
     * Office-Formate; sonst bleibt der Typ des Inhalts.
     */
    static String refine(String byContent, String named) {
        if (byContent == null) {
            return named;
        }
        boolean fits = switch (byContent) {
            case "text/plain" -> named.startsWith("text/") || named.equals("application/json")
                    || named.endsWith("/xml") || named.endsWith("+xml") || named.equals("application/x-yaml");
            case "application/zip", "application/x-tika-ooxml" -> named.startsWith("application/vnd.openxmlformats")
                    || named.startsWith("application/vnd.oasis.opendocument");
            default -> named.equals("application/msword") || named.startsWith("application/vnd.ms-");
        };
        return fits ? named : byContent;
    }

    @Override
    public Extracted extract(String title, String mediaType, byte[] content) {
        if (content.length > maxBytes) {
            throw new DocumentTextException(DocumentTextException.Reason.TOO_LARGE,
                    "Datei größer als " + maxBytes + " Bytes", null);
        }
        if (content.length == 0) {
            return new Extracted("", false);
        }
        String key = key(title, mediaType, content);
        Object known = cached(key);
        if (known instanceof Extracted extracted) {
            return extracted;
        }
        if (known instanceof DocumentTextException.Reason reason) {
            throw new DocumentTextException(reason, "Kein Text (wie zuvor)", null);
        }
        KnowledgeResource resource = KnowledgeResource.builder(RESOURCE_ID, SOURCE).title(title)
                .contentType(mediaType).build();
        String text;
        try {
            text = limited(() -> engine().files.extract(resource, content));
        } catch (DocumentTextException e) {
            if (!e.reason().transientFailure()) {
                remember(key, e.reason());
            }
            throw e;
        }
        text = text == null ? "" : text.replace("\r\n", "\n").strip();
        boolean truncated = text.length() > maxChars;
        Extracted extracted = new Extracted(truncated ? text.substring(0, maxChars) : text, truncated);
        remember(key, extracted);
        return extracted;
    }

    /** Führt {@code work} in einem eigenen Thread mit Zeitgrenze aus. */
    private <T> T limited(Callable<T> work) {
        long started = System.nanoTime();
        try {
            if (!slots.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new DocumentTextException(DocumentTextException.Reason.BUSY, "Zu viele Extraktionen", null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DocumentTextException(DocumentTextException.Reason.BUSY, "Warten abgebrochen", null);
        }
        FutureTask<T> task = new FutureTask<>(work);
        Thread worker = new Thread(() -> {
            try {
                task.run();
            } finally {
                slots.release();
            }
        }, "document-text");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (RuntimeException | OutOfMemoryError e) {
            slots.release();
            throw new DocumentTextException(DocumentTextException.Reason.BUSY, "Kein Thread für die Extraktion", null);
        }
        long remaining = timeout.toNanos() - (System.nanoTime() - started);
        try {
            return task.get(Math.max(remaining, TimeUnit.MILLISECONDS.toNanos(1)), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            throw new DocumentTextException(DocumentTextException.Reason.TIMEOUT,
                    "Extraktion länger als " + timeout.toSeconds() + " s", null);
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new DocumentTextException(DocumentTextException.Reason.BUSY, "Warten abgebrochen", null);
        } catch (ExecutionException e) {
            throw failure(e.getCause());
        }
    }

    /** Übersetzt Fehler der Extraktion in feste Gründe; die Meldungen der Kopie nennen den Dateinamen. */
    private static DocumentTextException failure(Throwable cause) {
        if (cause instanceof DocumentTextException own) {
            return own;
        }
        if (cause instanceof KnowledgeSourceException source
                && source.kind() == KnowledgeSourceException.Kind.UNSUPPORTED) {
            return new DocumentTextException(DocumentTextException.Reason.UNSUPPORTED, "Format nicht lesbar", null);
        }
        String type = cause == null ? "unbekannt" : cause.getClass().getSimpleName();
        return new DocumentTextException(DocumentTextException.Reason.FAILED, "Extraktion gescheitert (" + type + ")",
                null);
    }

    private Engine engine() {
        Engine current = engine;
        if (current == null) {
            synchronized (this) {
                current = engine;
                if (current == null) {
                    current = new Engine();
                    engine = current;
                }
            }
        }
        return current;
    }

    private Object cached(String key) {
        synchronized (recent) {
            return recent.get(key);
        }
    }

    private void remember(String key, Object result) {
        synchronized (recent) {
            recent.put(key, result);
        }
    }

    /** Inhalts-Hash, Medientyp und Endung: das, wonach die Extraktion den Weg wählt. */
    private static String key(String title, String mediaType, byte[] content) {
        String name = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)) + '\n'
                    + (mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT)) + '\n' + extension;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Erkennung und Extraktoren wie in der Composition Root des Enterprise AI Client. */
    private static final class Engine {

        final ContentDetector detector = new OneNoteContentDetector(DocumentExtraction.detector());
        final SourceFileTextExtractor files = new DocumentSourceFileTextExtractor(detector,
                DocumentExtraction.registry(), null);
    }

    private static final class Shared {

        static final TikaDocumentText INSTANCE = new TikaDocumentText();
    }
}
