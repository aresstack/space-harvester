package com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint;

import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Read a bounded SharePoint change-log window using list-scoped tokens. */
final class SharePointChangeLog {
    private static final int PAGE_SIZE = 500;
    private static final long DOTNET_EPOCH = 621355968000000000L;

    private SharePointChangeLog() { }

    interface Query {
        JsonObject execute(byte[] body) throws KnowledgeSourceException;
    }

    interface Event {
        void accept(JsonObject event) throws KnowledgeSourceException;
    }

    static String guid(String value) throws KnowledgeSourceException {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw invalid("Ungültige Änderungs-ID");
        }
        return UUID.fromString(value).toString();
    }

    static void visit(String list, Instant start, Instant end, Query query, Event event)
            throws KnowledgeSourceException {
        String prefix = "1;3;" + list + ";";
        String cursor = timeToken(prefix, start);
        String last = timeToken(prefix, end);
        Set<String> visited = new HashSet<String>();
        while (visited.add(cursor)) {
            if (Thread.currentThread().isInterrupted()) {
                throw new KnowledgeSourceException(Kind.UNAVAILABLE, "SharePoint: Änderungsabfrage abgebrochen");
            }
            JsonObject response = query.execute(body(cursor, last));
            JsonElement rows = response.get("results");
            if (rows == null || !rows.isJsonArray()) throw invalid("Änderungsliste fehlt");
            JsonArray events = rows.getAsJsonArray();
            if (events.size() == 0) return;
            String next = null;
            for (JsonElement row : events) {
                if (!row.isJsonObject()) throw invalid("Ungültiger Änderungsdatensatz");
                JsonObject change = row.getAsJsonObject();
                JsonElement token = change.get("ChangeToken");
                JsonElement value = token != null && token.isJsonObject() ? token.getAsJsonObject().get("StringValue") : null;
                if (value == null || !value.isJsonPrimitive()) throw invalid("Änderungstoken fehlt");
                next = value.getAsString();
                if (!next.toLowerCase(java.util.Locale.ROOT).startsWith(prefix) || next.equals(cursor)) {
                    throw invalid("Fremdes oder wiederholtes Änderungstoken");
                }
                event.accept(change);
            }
            if (events.size() < PAGE_SIZE) return;
            cursor = next;
        }
        throw invalid("Wiederholtes Änderungstoken");
    }

    static String timeToken(String prefix, Instant time) throws KnowledgeSourceException {
        try {
            long ticks = Math.addExact(DOTNET_EPOCH,
                    Math.addExact(Math.multiplyExact(time.getEpochSecond(), 10000000L), time.getNano() / 100));
            if (ticks < 0) throw invalid("Ungültiger Änderungszeitraum");
            return prefix + ticks + ";-1";
        } catch (ArithmeticException e) {
            throw invalid("Ungültiger Änderungszeitraum");
        }
    }

    private static byte[] body(String start, String end) {
        JsonObject query = typed("SP.ChangeQuery");
        for (String key : new String[]{"Item", "File", "Folder", "List", "Add", "Update", "DeleteObject",
                "Rename", "Move", "Restore", "SystemUpdate", "RoleAssignmentAdd", "RoleAssignmentDelete"}) {
            query.addProperty(key, true);
        }
        query.addProperty("FetchLimit", PAGE_SIZE);
        query.add("ChangeTokenStart", token(start));
        query.add("ChangeTokenEnd", token(end));
        JsonObject envelope = new JsonObject();
        envelope.add("query", query);
        return envelope.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject token(String value) {
        JsonObject token = typed("SP.ChangeToken");
        token.addProperty("StringValue", value);
        return token;
    }

    private static JsonObject typed(String type) {
        JsonObject result = new JsonObject();
        JsonObject meta = new JsonObject();
        meta.addProperty("type", type);
        result.add("__metadata", meta);
        return result;
    }

    private static KnowledgeSourceException invalid(String message) {
        return new KnowledgeSourceException(Kind.INVALID_RESPONSE, "SharePoint: " + message);
    }
}
