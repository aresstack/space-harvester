package com.aresstack.spaceharvester.application.convert;

import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Der Space-Name für {@link ConversionInput#spaceName()} aus der nativen Payload einer Seite. */
public final class SpaceNames {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SpaceNames() {
    }

    /** {@code space.name} einer Confluence-Payload, nur wenn {@code space.key} zum Space passt. */
    public static Optional<String> of(PageSnapshot snapshot, String spaceKey) {
        if (!"application/json".equals(snapshot.payload().mediaType())) {
            return Optional.empty();
        }
        try {
            JsonNode space = JSON.readTree(snapshot.payload().bytes()).path("space");
            String name = space.path("name").isString() ? space.path("name").asString().strip() : "";
            return space.path("key").isString() && space.path("key").asString().equals(spaceKey) && !name.isEmpty()
                    ? Optional.of(name) : Optional.empty();
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }
}
