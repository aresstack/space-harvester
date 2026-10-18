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
 *   <li>Größe: höchstens {@link #DEFAULT_MAX_BYTES} je Datei (dieselben 20 MiB wie Confluence-Antworten, ADR 0002, und
 *       {@code options.maxFileMegabytes} lokaler Dateien); größere Dateien werden nicht geparst.</li>
 *   <li>Zeit: höchstens {@link #DEFAULT_TIMEOUT} je Datei, in einem eigenen Thread; danach wird er unterbrochen und
 *       das Ergebnis verworfen.</li>
 *   <li>Gleichzeitigkeit: höchstens {@link #MAX_PARALLEL} Läufe; ein hängender Lauf hält seinen Platz, bis er endet,
 *       sodass nie mehr als so viele Threads in Tika stecken.</li>
 *   <li>Textlänge: höchstens {@link #DEFAULT_MAX_CHARS} Zeichen, so viele, wie der Volltextindex je Seite führt.</li>
 * </ul>
 * Die letzten Ergebnisse bleiben im Speicher ({@value #CACHE_ENTRIES} Einträge nach Inhalts-Hash), weil Volltextindex
 * und Anreicherungen dieselbe Datei kurz nacheinander projizieren. Meldungen nennen weder Dateinamen noch Inhalte.
 */
public final class TikaDocumentText implements DocumentText {

    private static final System.Logger LOG = System.getLogger(TikaDocumentText.class.getName());

    public static final String ID = "tika";
    /** Tika-Linie und Regeln dieser Klasse; steigt, wenn sich der gelesene Text ändern kann. */
    public static final String VERSION = "3.3-1";

    /** Größte Datei, die geparst wird: 20 MiB. */
    public static final long DEFAULT_MAX_BYTES = 20L * 1024 * 1024;
    /** Längster Text je Datei, wie {@code LucenePageTextIndex.MAX_TEXT_CHARS}. */
    public static final int DEFAULT_MAX_CHARS = 1_000_000;
    /** Längste Laufzeit je Datei. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    /** Gleichzeitige Läufe. */
    public static final int MAX_PARALLEL = 2;
    static final int CACHE_ENTRIES = 8;
    /** So viele Bytes reichen für die Signatur, wenn die Datei zu groß zum Parsen ist. */
    private static final int PREFIX_BYTES = 64 * 1024;
    private static final KnowledgeResourceId RESOURCE_ID = KnowledgeResourceId.of("asset:document");
    private static final SourceAddress SOURCE = SourceAddress.of("asset://space-harvester");

    private final long maxBytes;
    private final int maxChars;
    private final Duration timeout;
    private final Semaphore slots = new Semaphore(MAX_PARALLEL);
    private final Map<String, Object> recent = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };
    /** Tika lädt beim ersten Gebrauch, nicht beim Start. */
    private volatile Engine engine;

    public TikaDocumentText() {
        this(DEFAULT_MAX_BYTES, DEFAULT_MAX_CHARS, DEFAULT_TIMEOUT);
    }

    public TikaDocumentText(long maxBytes, int maxChars, Duration timeout) {
        if (maxBytes < 1 || maxChars < 1 || timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Grenzen müssen positiv sein");
        }
        this.maxBytes = maxBytes;
        this.maxChars = maxChars;
        this.timeout = timeout;
    }

    /** Die gemeinsame Instanz des Dienstes mit den Standardgrenzen. */
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

        final ContentDetector detector = DocumentExtraction.detector();
        final SourceFileTextExtractor files = new DocumentSourceFileTextExtractor(detector,
                DocumentExtraction.registry(), null);
    }

    private static final class Shared {

        static final TikaDocumentText INSTANCE = new TikaDocumentText();
    }
}
