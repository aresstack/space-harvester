package com.aresstack.enterpriseai.astu.acropolis.image;

import com.aresstack.enterpriseai.chat.api.ChatCompletionPort;
import com.aresstack.enterpriseai.astu.chat.ChatImage;
import com.aresstack.enterpriseai.astu.chat.ChatMessage;
import com.aresstack.enterpriseai.astu.chat.ChatOptions;
import com.aresstack.enterpriseai.astu.chat.ChatRequest;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.source.api.ImageSource;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Wertet die Bilder einer Quellenseite aus: je Bild getrennt OCR (Modell der Kategorie DOCUMENT_OCR) und
 * Beschreibung (Kategorie VISION), beides über den Chat-Port. Je Bild-ID und Version nur einmal (Cache); ein Bild
 * ohne gewähltes Modell bleibt in diesem Teil leer, ein Modellfehler ebenso (das nächste Mal wird es erneut
 * versucht). Ohne Quelle, die Bilder liefert ({@link ImageSource}), gibt es nichts zu tun.
 *
 * <p>{@link #describe} ist der Einzelbild-Weg des Lesebereichs: nur die Beschreibung (VISION, keine OCR), genau für
 * ein Bild und erst auf Abruf. {@link #describeAll} ruft denselben Weg für alle Bilder einer Seite parallel auf
 * (vorbereitet für den späteren Zugriff über MCP/Bookmark, heute von niemandem ausgelöst).</p>
 */
public final class SourceImageAnalysisUseCase {

    static final String OCR_PROMPT = "Gib den gesamten Text in diesem Bild wörtlich wieder, Zeile für Zeile. "
            + "Nur den Text, keine Erklärung. Ohne Text im Bild: leere Antwort.";
    static final String DESCRIPTION_PROMPT = "Beschreibe dieses Bild sachlich auf Deutsch in höchstens fünf "
            + "Sätzen: was es zeigt und welche Aussage es trägt (Diagramm, Screenshot, Foto, Skizze).";

    /** Cache-Präfix der reinen Beschreibungen ({@link #describe}), getrennt von den vollen Auswertungen. */
    static final String DESCRIPTION_PREFIX = "description:";

    private final Supplier<Collection<KnowledgeSourcePort>> sources;
    private final ChatCompletionPort chat;
    private final Supplier<String> ocrModel;
    private final Supplier<String> visionModel;
    private final ImageAnalysisCache cache;

    /**
     * @param ocrModel    Modell-ID der Kategorie DOCUMENT_OCR, {@code null}: keine OCR
     * @param visionModel Modell-ID der Kategorie VISION, {@code null}: keine Beschreibung
     */
    public SourceImageAnalysisUseCase(Supplier<Collection<KnowledgeSourcePort>> sources, ChatCompletionPort chat,
                                      Supplier<String> ocrModel, Supplier<String> visionModel,
                                      ImageAnalysisCache cache) {
        if (sources == null || chat == null || ocrModel == null || visionModel == null || cache == null) {
            throw new IllegalArgumentException("all arguments are required");
        }
        this.sources = sources;
        this.chat = chat;
        this.ocrModel = ocrModel;
        this.visionModel = visionModel;
        this.cache = cache;
    }

    /** Die Auswertungen der Bilder einer Seite, höchstens {@code maxImages}. */
    public List<ImageAnalysis> analyze(KnowledgeResourceId page, int maxImages) {
        String ocr = ocrModel.get();
        String vision = visionModel.get();
        if (isBlank(ocr) && isBlank(vision)) {
            return Collections.emptyList();
        }
        for (KnowledgeSourcePort source : sources.get()) {
            if (!(source instanceof ImageSource)) {
                continue;
            }
            List<KnowledgeResource> images;
            try {
                images = ((ImageSource) source).imagesOf(page, maxImages);
            } catch (KnowledgeSourceException notThisSource) {
                continue; // eine andere Quelle kennt die Seite
            }
            List<ImageAnalysis> results = new ArrayList<ImageAnalysis>();
            for (KnowledgeResource image : images) {
                ImageAnalysis analysis = analyzeOne((ImageSource) source, image, ocr, vision);
                if (analysis != null) {
                    results.add(analysis);
                }
            }
            return results;
        }
        return Collections.emptyList();
    }

    /**
     * Beschreibt genau dieses eine Bild über das VISION-Modell, je Bild-ID und Version einmal: aus dem Cache, wenn
     * schon beschrieben (auch von {@link #analyze}), sonst jetzt. OCR läuft hier nicht.
     *
     * @param imageId   stabile Kennung des Bildes (Ressourcen-ID oder Adresse)
     * @param version   Version des Bildes, leer wenn unbekannt
     * @param mediaType Medientyp der Bytes ({@code image/png} …)
     * @return die Auswertung mit Beschreibung, oder {@code null}, wenn kein VISION-Modell gewählt ist
     * @throws RuntimeException wenn das Modell scheitert (dann wird nichts gespeichert)
     */
    public ImageAnalysis describe(String imageId, String title, String version, String mediaType, byte[] bytes) {
        if (isBlank(imageId) || bytes == null || bytes.length == 0 || mediaType == null
                || !mediaType.startsWith("image/")) {
            throw new IllegalArgumentException("imageId, image bytes and an image media type are required");
        }
        String key = ImageAnalysis.cacheKey(imageId, version);
        ImageAnalysis analyzed = cache.get(key);
        if (analyzed != null && !analyzed.description().isEmpty()) {
            return analyzed;
        }
        ImageAnalysis described = cache.get(DESCRIPTION_PREFIX + key);
        if (described != null) {
            return described;
        }
        String vision = visionModel.get();
        if (isBlank(vision)) {
            return null;
        }
        ChatRequest request = new ChatRequest(
                Arrays.asList(ChatMessage.userWithImages(DESCRIPTION_PROMPT,
                        Collections.singletonList(new ChatImage(mediaType, bytes)))),
                ChatOptions.builder().model(vision).build());
        String description = chat.complete(request).content().trim();
        ImageAnalysis result = new ImageAnalysis(imageId, title, version, "", description);
        cache.put(DESCRIPTION_PREFIX + key, result);
        return result;
    }

    /**
     * Beschreibt alle Bilder einer Seite parallel über denselben Einzelbild-Weg ({@link #describe}); fertig, wenn
     * alle fertig sind. Ein Bild, das nicht ladbar ist oder dessen Beschreibung scheitert, fehlt im Ergebnis.
     */
    public CompletableFuture<List<ImageAnalysis>> describeAll(final ImageSource source, KnowledgeResourceId page,
                                                              int maxImages, Executor pool)
            throws KnowledgeSourceException {
        List<CompletableFuture<ImageAnalysis>> pending = new ArrayList<CompletableFuture<ImageAnalysis>>();
        for (final KnowledgeResource image : source.imagesOf(page, maxImages)) {
            pending.add(CompletableFuture.supplyAsync(() -> {
                try {
                    byte[] bytes = source.loadImage(image.id());
                    String mediaType = image.contentType() == null ? "" : image.contentType().split(";")[0].trim();
                    return describe(image.id().value(), image.title(), image.revision().version(), mediaType, bytes);
                } catch (KnowledgeSourceException | RuntimeException failed) {
                    return null;
                }
            }, pool));
        }
        final List<CompletableFuture<ImageAnalysis>> all = pending;
        return CompletableFuture.allOf(all.toArray(new CompletableFuture<?>[0])).thenApply(done -> {
            List<ImageAnalysis> results = new ArrayList<ImageAnalysis>();
            for (CompletableFuture<ImageAnalysis> future : all) {
                ImageAnalysis analysis = future.join();
                if (analysis != null) {
                    results.add(analysis);
                }
            }
            return results;
        });
    }

    private ImageAnalysis analyzeOne(ImageSource source, KnowledgeResource image, String ocr, String vision) {
        String version = image.revision().version();
        String key = ImageAnalysis.cacheKey(image.id().value(), version);
        ImageAnalysis cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        byte[] bytes;
        try {
            bytes = source.loadImage(image.id());
        } catch (KnowledgeSourceException unreadable) {
            return null;
        }
        String mediaType = image.contentType() == null ? "" : image.contentType().split(";")[0].trim();
        if (!mediaType.startsWith("image/") || bytes == null || bytes.length == 0) {
            return null;
        }
        ChatImage chatImage = new ChatImage(mediaType, bytes);
        String text = isBlank(ocr) ? "" : ask(ocr, OCR_PROMPT, chatImage);
        String description = isBlank(vision) ? "" : ask(vision, DESCRIPTION_PROMPT, chatImage);
        ImageAnalysis analysis = new ImageAnalysis(image.id().value(), image.title(), version, text, description);
        if (text != null && description != null) {
            cache.put(key, analysis);
        }
        return analysis;
    }

    /** @return die Antwort, oder {@code null} bei einem Fehler (dann wird nicht gespeichert) */
    private String ask(String model, String prompt, ChatImage image) {
        try {
            ChatRequest request = new ChatRequest(
                    Arrays.asList(ChatMessage.userWithImages(prompt, Collections.singletonList(image))),
                    ChatOptions.builder().model(model).build());
            return chat.complete(request).content().trim();
        } catch (RuntimeException failed) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
