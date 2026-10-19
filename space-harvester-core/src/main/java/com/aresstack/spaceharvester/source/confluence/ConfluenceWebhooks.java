package com.aresstack.spaceharvester.source.confluence;

import com.aresstack.spaceharvester.application.sources.ChangeEvent;
import com.aresstack.spaceharvester.application.sources.UnreadableChangeEventException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Liest die Webhook-Aufrufe von Confluence Data Center (ab 7.7, ADR 0014): Ereignisname im Kopf {@code X-Event-Key}
 * (ersatzweise {@code event} im Rumpf), die Seite unter {@code page} bzw. {@code content} mit {@code id},
 * {@code spaceKey} (oder {@code space.key}) und {@code version} (Zahl oder {@code version.number}). Blogbeiträge,
 * Kommentare, Anhänge und Space-Ereignisse fallen weg; was davon fehlt, holen Nachholen und Inventur.
 */
public final class ConfluenceWebhooks {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private static final Set<String> CHANGED = Set.of("page_created", "page_updated", "page_moved", "page_restored",
            "page_children_reordered", "page_archived", "page_unarchived");
    private static final Set<String> REMOVED = Set.of("page_trashed", "page_removed");

    private ConfluenceWebhooks() {
    }

    /**
     * @param eventKey Wert von {@code X-Event-Key} oder {@code null}
     * @return die Meldung als Ereignis, leer bei Ereignissen ohne Seite
     * @throws UnreadableChangeEventException wenn der Rumpf kein JSON-Objekt ist
     */
    public static List<ChangeEvent> parse(byte[] body, String eventKey) {
        JsonNode root;
        try {
            root = body == null || body.length == 0 ? null : JSON.readTree(body);
        } catch (JacksonException e) {
            throw new UnreadableChangeEventException("Der Webhook-Aufruf ist kein JSON");
        }
        if (root == null || !root.isObject()) {
            throw new UnreadableChangeEventException("Der Webhook-Aufruf ist kein JSON-Objekt");
        }
        String event = eventKey != null && !eventKey.isBlank() ? eventKey.trim() : text(root.get("event"));
        event = event == null ? "" : event.toLowerCase(Locale.ROOT);
        ChangeEvent.Kind kind = kind(event);
        JsonNode page = root.has("page") ? root.get("page") : root.get("content");
        if (kind == null || page == null || !page.isObject()) {
            return List.of();
        }
        String type = text(page.has("contentType") ? page.get("contentType") : page.get("type"));
        if (type != null && !"page".equalsIgnoreCase(type)) {
            return List.of();
        }
        String pageId = text(page.get("id"));
        if (pageId == null) {
            return List.of();
        }
        String spaceKey = text(page.get("spaceKey"));
        if (spaceKey == null) {
            spaceKey = text(page.path("space").get("key"));
        }
        if (spaceKey == null) {
            spaceKey = text(root.path("space").get("key"));
        }
        JsonNode version = page.get("version");
        String number = version != null && version.isObject() ? text(version.get("number")) : text(version);
        return List.of(new ChangeEvent(kind, pageId, spaceKey, number));
    }

    private static ChangeEvent.Kind kind(String event) {
        if (CHANGED.contains(event)) {
            return ChangeEvent.Kind.CHANGED;
        }
        if (REMOVED.contains(event)) {
            return ChangeEvent.Kind.REMOVED;
        }
        if (event.startsWith("page_") || event.startsWith("content_")) {
            if (event.contains("permission") || event.contains("restriction")) {
                return ChangeEvent.Kind.PERMISSIONS;
            }
        }
        return null;
    }

    private static String text(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asString();
        }
        return value.isString() && !value.asString().isBlank() ? value.asString().trim() : null;
    }
}
