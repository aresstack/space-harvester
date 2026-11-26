package com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint;

import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Read a bounded SharePoint change-log window using list-scoped tokens. */
final class SharePointChangeLog {
    private static final int PAGE_SIZE = 500;
    private static final long DOTNET_EPOCH = 621355968000000000L;

    private SharePointChangeLog() { }

    interface Query {
        JsonObject execute(byte[] body) throws KnowledgeSourceException;
    }

    static String guid(String value) throws KnowledgeSourceException {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw invalid("Ungültige Änderungs-ID");
        }
        return UUID.fromString(value).toString();
    }

    /** Prefix of every change token of {@code list}. */
    static String prefix(String list) {
        return "1;3;" + list + ";";
    }

    /**
     * One request of the change log of {@code list} after {@code start} (exclusive) up to {@code end}, as the server
     * delivers it: each change with its own token, so that a caller can continue after any of them (ADR 0042, point 6).
     */
    static Page page(String list, String start, String end, Query query) throws KnowledgeSourceException {
        if (Thread.currentThread().isInterrupted()) {
            throw new KnowledgeSourceException(Kind.UNAVAILABLE, "SharePoint: Änderungsabfrage abgebrochen");
        }
        String prefix = prefix(list);
        JsonObject response = query.execute(body(start, end));
        JsonElement rows = response.get("results");
        if (rows == null || !rows.isJsonArray()) throw invalid("Änderungsliste fehlt");
        JsonArray events = rows.getAsJsonArray();
        List<Change> changes = new ArrayList<Change>(events.size());
        String previous = start;
        for (JsonElement row : events) {
            if (!row.isJsonObject()) throw invalid("Ungültiger Änderungsdatensatz");
            JsonObject change = row.getAsJsonObject();
            JsonElement token = change.get("ChangeToken");
            JsonElement value = token != null && token.isJsonObject() ? token.getAsJsonObject().get("StringValue") : null;
            if (value == null || !value.isJsonPrimitive()) throw invalid("Änderungstoken fehlt");
            String next = value.getAsString();
            if (!next.toLowerCase(java.util.Locale.ROOT).startsWith(prefix) || next.equals(previous)) {
                throw invalid("Fremdes oder wiederholtes Änderungstoken");
            }
            changes.add(new Change(change, next));
            previous = next;
        }
        return new Page(changes, events.size() >= PAGE_SIZE);
    }

    /** The changes of one request and whether the server may have more after the last of them. */
    static final class Page {
        final List<Change> changes;
        final boolean more;

        Page(List<Change> changes, boolean more) {
            this.changes = changes;
            this.more = more && !changes.isEmpty();
        }
    }

    /** One change with the token after which the log continues. */
    static final class Change {
        final JsonObject event;
        final String token;

        Change(JsonObject event, String token) {
            this.event = event;
            this.token = token;
        }
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
