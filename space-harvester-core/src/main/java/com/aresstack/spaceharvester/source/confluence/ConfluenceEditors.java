package com.aresstack.spaceharvester.source.confluence;

import com.aresstack.spaceharvester.application.changelog.PageEditors;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bearbeiter einer Confluence-Fassung ({@code confluence-rest-content+json} mit {@code expand=version}) aus
 * {@code version.by}: Anzeigename, sonst Benutzername, sonst Benutzerschlüssel (ADR 0031).
 */
public final class ConfluenceEditors implements PageEditors {

    private final JsonMapper json = JsonMapper.builder().build();

    @Override
    public Optional<String> editor(PageSnapshot snapshot) {
        String format = snapshot.payload().nativeFormat();
        if (!format.equals(ConfluenceStorageTextProjector.NATIVE_FORMAT_PREFIX)
                && !format.startsWith(ConfluenceStorageTextProjector.NATIVE_FORMAT_PREFIX + ";")) {
            return Optional.empty();
        }
        JsonNode by;
        try {
            JsonNode root = json.readTree(snapshot.payload().bytes());
            by = root == null ? null : root.path("version").path("by");
        } catch (JacksonException e) {
            return Optional.empty();
        }
        if (by == null) {
            return Optional.empty();
        }
        for (String field : new String[]{"displayName", "username", "userKey", "accountId"}) {
            JsonNode value = by.path(field);
            if (value.isString() && !value.asString().isBlank()) {
                return Optional.of(value.asString().strip());
            }
        }
        return Optional.empty();
    }
}
