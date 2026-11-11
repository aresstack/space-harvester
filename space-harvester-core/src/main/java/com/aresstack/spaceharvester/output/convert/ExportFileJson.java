package com.aresstack.spaceharvester.output.convert;

import com.aresstack.spaceharvester.application.convert.ExportAssetManifest;
import com.aresstack.spaceharvester.application.convert.ExportReadingView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;

/**
 * JSON-Darstellung der Begleitdateien eines Datenbestands, gemeinsam für das Exportverzeichnis und das
 * JSON-Paket (json-package/3): Bildmanifest {@code assets.json} ({@link ExportAssetManifest#FORMAT}) und Lese-Ansicht
 * einer Seite ({@link ExportReadingView#FORMAT}). Was hier geschrieben wird, liest dieselbe Klasse verlustfrei zurück.
 */
public final class ExportFileJson {

    private static final Pattern SAFE_PAGE_ID = Pattern.compile("[0-9a-z][0-9a-z_-]{0,63}");

    private ExportFileJson() {
    }

    /**
     * Dateiname der Payload einer Seite unter {@code raw/} (und ihrer Lese-Ansicht unter {@code views/}): die
     * Seiten-ID, wenn sie sicher ist, sonst {@code id-} und ein Hash; eindeutig je Seiten-ID.
     */
    public static String rawFileName(String pageId, String mediaType) {
        String base = SAFE_PAGE_ID.matcher(pageId).matches()
                ? pageId
                : "id-" + sha256Hex(pageId.getBytes(StandardCharsets.UTF_8)).substring(0, 40);
        return base + ("application/json".equals(mediaType) ? ".json" : ".bin");
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt", e);
        }
    }

    /** Schreibt das Bildmanifest als JSON-Objekt. */
    public static void writeAssets(JsonGenerator generator, ExportAssetManifest manifest) {
        generator.writeStartObject();
        generator.writeStringProperty("format", ExportAssetManifest.FORMAT);
        generator.writeBooleanProperty("complete", manifest.complete());
        generator.writeBooleanProperty("imagesComplete", manifest.imagesComplete());
        generator.writeBooleanProperty("documentsComplete", manifest.documentsComplete());
        generator.writeName("assets");
        generator.writeStartArray();
        for (ExportAssetManifest.Entry entry : manifest.assets()) {
            ExportAssetManifest.Reference ref = entry.reference();
            generator.writeStartObject();
            generator.writeStringProperty("pageId", entry.pageId());
            generator.writeName("reference");
            generator.writeStartObject();
            generator.writeStringProperty("kind", ref.kind().json());
            if (ref.attachmentId() != null) {
                generator.writeStringProperty("attachmentId", ref.attachmentId());
            }
            if (ref.filename() != null) {
                generator.writeStringProperty("filename", ref.filename());
            }
            if (ref.src() != null) {
                generator.writeStringProperty("src", ref.src());
            }
            if (ref.index() != null) {
                generator.writeNumberProperty("index", ref.index());
            }
            if (ref.source() != null) {
                generator.writeStringProperty("source", ref.source());
            }
            generator.writeEndObject();
            generator.writeStringProperty("attachmentId", entry.attachmentId());
            generator.writeStringProperty("version", entry.version());
            generator.writeStringProperty("title", entry.title());
            generator.writeStringProperty("mediaType", entry.mediaType());
            generator.writeNumberProperty("size", entry.size());
            generator.writeStringProperty("sha256", entry.sha256());
            generator.writeStringProperty("status", entry.status().name());
            generator.writeStringProperty("reason", entry.reason());
            if (entry.rendition() != null) {
                generator.writeName("rendition");
                generator.writeStartObject();
                if (entry.rendition().drawn()) {
                    generator.writeStringProperty("mediaType", entry.rendition().mediaType());
                    generator.writeNumberProperty("size", entry.rendition().size());
                    generator.writeStringProperty("sha256", entry.rendition().sha256());
                } else {
                    generator.writeStringProperty("problem", entry.rendition().problem());
                }
                generator.writeEndObject();
            }
            generator.writeEndObject();
        }
        generator.writeEndArray();
        generator.writeEndObject();
    }

    /** Liest ein mit {@link #writeAssets} geschriebenes Bildmanifest; leer bei fremdem Format. */
    public static Optional<ExportAssetManifest> readAssets(JsonNode node) {
        if (!ExportAssetManifest.FORMAT.equals(node.path("format").asString())) {
            return Optional.empty();
        }
        List<ExportAssetManifest.Entry> entries = new ArrayList<>();
        for (JsonNode entry : node.path("assets")) {
            JsonNode ref = entry.path("reference");
            ExportAssetManifest.Reference reference = new ExportAssetManifest.Reference(
                    ExportAssetManifest.Kind.ofJson(ref.path("kind").asString()), optional(ref, "attachmentId"),
                    optional(ref, "filename"), optional(ref, "src"),
                    ref.hasNonNull("index") ? ref.path("index").asInt() : null, optional(ref, "source"));
            entries.add(new ExportAssetManifest.Entry(entry.path("pageId").asString(), reference,
                    optional(entry, "attachmentId"), optional(entry, "version"), optional(entry, "title"),
                    optional(entry, "mediaType"), entry.path("size").asLong(), optional(entry, "sha256"),
                    ExportAssetManifest.Status.valueOf(entry.path("status").asString()), optional(entry, "reason"),
                    rendition(entry.path("rendition"))));
        }
        boolean complete = node.path("complete").asBoolean(false);
        // Vor der Trennung in Bilder und Anhänge gab es nur „complete“; es galt für beide.
        return Optional.of(new ExportAssetManifest(node.path("imagesComplete").asBoolean(complete),
                node.path("documentsComplete").asBoolean(complete), entries));
    }

    /** Schreibt die Lese-Ansicht einer Seite als JSON-Objekt. */
    public static void writeReadingView(JsonGenerator generator, ExportReadingView view) {
        generator.writeStartObject();
        generator.writeStringProperty("format", ExportReadingView.FORMAT);
        generator.writeStringProperty("pageId", view.pageId());
        generator.writeStringProperty("version", view.version());
        generator.writeStringProperty("status", view.status().name());
        generator.writeStringProperty("reason", view.reason());
        generator.writeStringProperty("html", view.html());
        generator.writeStringProperty("baseUri", view.baseUri());
        generator.writeStringProperty("text", view.text());
        generator.writeEndObject();
    }

    /** Liest eine mit {@link #writeReadingView} geschriebene Ansicht; leer bei fremdem Format oder anderer Seite. */
    public static Optional<ExportReadingView> readReadingView(JsonNode node, String pageId) {
        if (!ExportReadingView.FORMAT.equals(node.path("format").asString())
                || !pageId.equals(node.path("pageId").asString())) {
            return Optional.empty();
        }
        return Optional.of(new ExportReadingView(pageId, optional(node, "version"),
                ExportReadingView.Status.valueOf(node.path("status").asString()), optional(node, "reason"),
                node.path("html").asString(), node.path("baseUri").asString(), node.path("text").asString()));
    }

    private static ExportAssetManifest.Rendition rendition(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        return node.hasNonNull("sha256") ? ExportAssetManifest.Rendition.drawn(node.path("mediaType").asString(),
                node.path("size").asLong(), node.path("sha256").asString())
                : ExportAssetManifest.Rendition.notDrawn(node.path("problem").asString());
    }

    private static String optional(JsonNode node, String name) {
        return node.hasNonNull(name) ? node.path(name).asString() : null;
    }
}
