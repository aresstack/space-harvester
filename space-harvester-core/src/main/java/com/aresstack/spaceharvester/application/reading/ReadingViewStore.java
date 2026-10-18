package com.aresstack.spaceharvester.application.reading;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Ablage der Lese-Ansichten in der Ablage der Anreicherungen ({@link EnrichmentStore}), unter der vorbehaltenen
 * Prozessor-ID {@link Enrichments#READING_VIEW}: getrennt von Payload und Kopfdaten, mit der Seite entfernt, nie in der
 * Liste der Anreicherungen. Je Seite höchstens eine, die jüngste ersetzt die vorige.
 */
public final class ReadingViewStore {

    /** Aufbau des abgelegten JSON-Objekts. */
    static final String SCHEMA = "reading-view/v1";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final EnrichmentStore store;

    public ReadingViewStore(EnrichmentStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Die abgelegte Ansicht der Seite, gleich zu welchem Snapshot. */
    public Optional<StoredReadingView> find(PageKey key) {
        for (Enrichment found : store.find(key)) {
            if (Enrichments.READING_VIEW.equals(found.processorId()) && found.status() == Enrichment.Status.OK
                    && found.revision() != null) {
                try {
                    return Optional.of(read(key, found));
                } catch (JacksonException | IllegalArgumentException | NullPointerException e) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    public void save(StoredReadingView view) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("schema", SCHEMA);
        payload.put("html", view.html());
        payload.put("baseUri", view.baseUri());
        payload.put("text", view.text());
        ArrayNode images = payload.putArray("images");
        for (HarvestSource.ViewImage image : view.images()) {
            ObjectNode node = images.addObject();
            node.put("src", image.src());
            node.put("title", image.title());
            node.put("attachmentId", image.attachmentId());
            node.put("mediaType", image.mediaType());
            node.put("version", image.version());
        }
        store.save(new Enrichment(view.key(), Enrichments.READING_VIEW, SCHEMA, view.rawSha256(), view.version(),
                Enrichment.Status.OK, JSON.writeValueAsString(payload), List.of(), null, view.renderedAt()));
        store.flush();
    }

    private static StoredReadingView read(PageKey key, Enrichment found) {
        JsonNode payload = JSON.readTree(found.payloadJson());
        if (!SCHEMA.equals(payload.path("schema").asString())) {
            throw new IllegalArgumentException("Unbekannter Aufbau");
        }
        List<HarvestSource.ViewImage> images = new ArrayList<>();
        for (JsonNode image : payload.path("images")) {
            images.add(new HarvestSource.ViewImage(image.path("src").asString(), text(image, "title"),
                    text(image, "attachmentId"), text(image, "mediaType"), text(image, "version")));
        }
        Instant renderedAt = found.computedAt();
        return new StoredReadingView(key, found.inputSha256(), found.revision(), payload.path("html").asString(),
                payload.path("baseUri").asString(), payload.path("text").asString(), images, renderedAt);
    }

    private static String text(JsonNode node, String name) {
        return node.hasNonNull(name) ? node.path(name).asString() : null;
    }
}
