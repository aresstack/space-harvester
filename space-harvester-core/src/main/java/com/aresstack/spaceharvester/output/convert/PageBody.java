package com.aresstack.spaceharvester.output.convert;

import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Der Seiteninhalt im internen Confluence-Format ({@code body.storage.value}) aus der nativen Payload. */
final class PageBody {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PageBody() {
    }

    /** @return leer, wenn die Payload kein JSON ist oder kein Storage-Format enthält */
    static Optional<String> storage(NativePayload payload) {
        if (!PageJson.isJson(payload.mediaType(), payload.bytes())) {
            return Optional.empty();
        }
        try {
            JsonNode value = JSON.readTree(payload.bytes()).path("body").path("storage").path("value");
            return value.isString() ? Optional.of(value.asString()) : Optional.empty();
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }
}
