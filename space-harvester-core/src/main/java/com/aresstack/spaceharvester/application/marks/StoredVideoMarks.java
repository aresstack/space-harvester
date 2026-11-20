package com.aresstack.spaceharvester.application.marks;

import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Die Marken eines Videos als ein Eintrag {@value Enrichments#VIDEO_MARKS} je Seite in der Anreicherungsablage. */
final class StoredVideoMarks implements VideoMarks {

    static final String SCHEMA = "video-marks/v1";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final EnrichmentStore store;

    StoredVideoMarks(EnrichmentStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public List<Long> find(PageKey page, String version) {
        for (Enrichment found : store.find(page)) {
            if (Enrichments.VIDEO_MARKS.equals(found.processorId()) && found.status() == Enrichment.Status.OK
                    && Objects.equals(found.revision(), version)) {
                try {
                    JsonNode payload = JSON.readTree(found.payloadJson());
                    if (!SCHEMA.equals(payload.path("schema").asString())) {
                        return List.of();
                    }
                    List<Long> marks = new ArrayList<>();
                    payload.path("marks").forEach(mark -> marks.add(mark.asLong()));
                    return List.copyOf(marks);
                } catch (JacksonException e) {
                    return List.of();
                }
            }
        }
        return List.of();
    }

    @Override
    public void save(PageKey page, String version, List<Long> marks) {
        if (marks.isEmpty()) {
            store.remove(page, Enrichments.VIDEO_MARKS);
            store.flush();
            return;
        }
        ObjectNode payload = JSON.createObjectNode();
        payload.put("schema", SCHEMA);
        ArrayNode list = payload.putArray("marks");
        marks.stream().distinct().sorted().forEach(list::add);
        String json = JSON.writeValueAsString(payload);
        store.save(new Enrichment(page, Enrichments.VIDEO_MARKS, SCHEMA,
                Assets.sha256(json.getBytes(StandardCharsets.UTF_8)), version, Enrichment.Status.OK, json, List.of(),
                null, Instant.now()));
        store.flush();
    }
}
