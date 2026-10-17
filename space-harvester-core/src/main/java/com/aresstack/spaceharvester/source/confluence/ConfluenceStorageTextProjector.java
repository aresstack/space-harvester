package com.aresstack.spaceharvester.source.confluence;

import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Projiziert Confluence-Snapshots ({@code confluence-rest-content+json}) auf den sichtbaren Text ihres
 * Storage-Formats ({@code body.storage.value}), siehe {@link ConfluenceStorageText}. Die Payload selbst bleibt
 * unverändert; die Projektion ist nur Eingabe der Enrichment-Prozessoren.
 */
public final class ConfluenceStorageTextProjector implements PageTextProjector {

    static final String NATIVE_FORMAT_PREFIX = "confluence-rest-content+json";

    private final JsonMapper json = JsonMapper.builder().build();

    @Override
    public Optional<PageText> project(PageSnapshot snapshot) {
        String format = snapshot.payload().nativeFormat();
        if (!format.equals(NATIVE_FORMAT_PREFIX) && !format.startsWith(NATIVE_FORMAT_PREFIX + ";")) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = json.readTree(snapshot.payload().bytes());
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Payload ist kein JSON");
        }
        JsonNode value = root == null ? null : root.path("body").path("storage").path("value");
        if (value == null || !value.isString()) {
            throw new IllegalArgumentException("Payload enthält kein body.storage.value");
        }
        return Optional.of(ConfluenceStorageText.project(value.asString()));
    }
}
