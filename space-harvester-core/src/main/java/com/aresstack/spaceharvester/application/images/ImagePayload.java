package com.aresstack.spaceharvester.application.images;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Format des Ergebnisses des Prozessors {@code images} (Schema {@value #SCHEMA}): je Bild der Lese-Ansicht in
 * Reihenfolge der Seite die Zuordnung zum Asset, der Status und die Beschreibung. Enthält nie Bildbytes.
 */
public final class ImagePayload {

    /** Kennung des Prozessors. */
    public static final String PROCESSOR = "images";
    /** Aufbau des Ergebnisses. */
    public static final String SCHEMA = "page-images/v1";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ImagePayload() {
    }

    /**
     * Ein Bild der Seite.
     *
     * @param index       Position in der Lese-Ansicht, ab 0
     * @param assetId     Anhang-ID oder abgeleitete ID ({@code view-…}) des Assets, {@code null} wenn nicht geladen
     * @param sha256      {@code sha256:<hex>} der Bildbytes, {@code null} wenn nicht geladen
     * @param filename    Dateiname aus der Adresse des Bildes
     * @param title       Titel bzw. Beschriftung des Bildes
     * @param mediaType   Medientyp der Bytes oder {@code null}
     * @param status      Analysestatus
     * @param reason      fester Code bei {@link ImageStatus#FAILED}, {@link ImageStatus#PENDING} und
     *                    {@link ImageStatus#SKIPPED}, sonst {@code null}
     * @param description Beschreibung bei {@link ImageStatus#DESCRIBED}, sonst {@code null}; maschinell erzeugt, kein
     *                    bestätigter Teil der Seite
     * @param describer   Beschreiber samt Analyseversion bei {@link ImageStatus#DESCRIBED}
     * @param model       Modell bei {@link ImageStatus#DESCRIBED}
     * @param describedAt Zeitpunkt der Beschreibung bei {@link ImageStatus#DESCRIBED}
     */
    public record Entry(int index, String assetId, String sha256, String filename, String title, String mediaType,
                        ImageStatus status, String reason, String description, String describer, String model,
                        Instant describedAt) {

        public Entry {
            Objects.requireNonNull(status, "status");
            filename = filename == null ? "" : filename;
            title = title == null ? "" : title;
            if ((status == ImageStatus.DESCRIBED) != (description != null)) {
                throw new IllegalArgumentException("description genau dann, wenn status DESCRIBED ist");
            }
        }

        /** Ohne Beschreibung, mit Grund. */
        public static Entry without(int index, String assetId, String sha256, String filename, String title,
                                    String mediaType, ImageStatus status, String reason) {
            return new Entry(index, assetId, sha256, filename, title, mediaType, status, reason, null, null, null,
                    null);
        }
    }

    /** Schreibt ein Ergebnis. */
    public static String write(List<Entry> entries) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schema", SCHEMA);
        ArrayNode images = root.putArray("images");
        for (Entry entry : entries) {
            ObjectNode node = images.addObject();
            node.put("index", entry.index());
            put(node, "assetId", entry.assetId());
            put(node, "sha256", entry.sha256());
            node.put("filename", entry.filename());
            node.put("title", entry.title());
            put(node, "mediaType", entry.mediaType());
            node.put("status", entry.status().key());
            put(node, "reason", entry.reason());
            put(node, "description", entry.description());
            put(node, "describer", entry.describer());
            put(node, "model", entry.model());
            put(node, "describedAt", entry.describedAt() == null ? null : entry.describedAt().toString());
        }
        return JSON.writeValueAsString(root);
    }

    /**
     * Liest ein Ergebnis.
     *
     * @throws IllegalArgumentException wenn es nicht diesem Format entspricht
     */
    public static List<Entry> parse(String payloadJson) {
        JsonNode images;
        try {
            images = JSON.readTree(payloadJson).path("images");
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Ergebnis nicht lesbar", e);
        }
        if (!images.isArray()) {
            throw new IllegalArgumentException("images fehlt");
        }
        List<Entry> parsed = new ArrayList<>(images.size());
        for (JsonNode node : images) {
            String describedAt = text(node, "describedAt");
            try {
                parsed.add(new Entry(node.path("index").asInt(parsed.size()), text(node, "assetId"),
                        text(node, "sha256"), text(node, "filename"), text(node, "title"), text(node, "mediaType"),
                        ImageStatus.of(String.valueOf(text(node, "status"))), text(node, "reason"),
                        text(node, "description"), text(node, "describer"), text(node, "model"),
                        describedAt == null ? null : Instant.parse(describedAt)));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Bild nicht lesbar", e);
            }
        }
        return parsed;
    }

    private static void put(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : null;
    }
}
