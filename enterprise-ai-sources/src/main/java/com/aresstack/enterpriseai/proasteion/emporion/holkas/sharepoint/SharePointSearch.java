package com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint;

import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.SourceQuery;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Build literal KQL queries and decode verbose OData search rows. */
final class SharePointSearch {
    private SharePointSearch() {}

    static String query(SourceQuery query, List<String> roots, Function<String, URI> location) throws KnowledgeSourceException {
        String text = query.text().replace('"', ' ').replace('\\', ' ').replaceAll("\\p{Cntrl}", " ").trim();
        if (text.isEmpty()) throw invalid("Leerer Suchbegriff");
        StringBuilder kql = new StringBuilder(query.titleOnly() ? "Filename:" : "")
                .append('"').append(text).append("\" AND IsDocument:1 AND (");
        for (int i = 0; i < roots.size(); i++) {
            if (i > 0) kql.append(" OR ");
            URI uri = location.apply(roots.get(i));
            if (uri == null) throw invalid("Ungültiger Suchpfad");
            kql.append("Path:\"").append(uri.toASCIIString()).append("/\"");
        }
        return kql.append(')').toString();
    }

    static List<Map<String, String>> rows(JsonObject data) throws KnowledgeSourceException {
        try {
            JsonObject relevant = data.getAsJsonObject("query").getAsJsonObject("PrimaryQueryResult")
                    .getAsJsonObject("RelevantResults");
            List<Map<String, String>> rows = new ArrayList<Map<String, String>>();
            for (JsonElement element : relevant.getAsJsonObject("Table").getAsJsonObject("Rows").getAsJsonArray("results")) {
                Map<String, String> row = new LinkedHashMap<String, String>();
                for (JsonElement cell : element.getAsJsonObject().getAsJsonObject("Cells").getAsJsonArray("results")) {
                    JsonObject item = cell.getAsJsonObject();
                    JsonElement key = item.get("Key");
                    JsonElement value = item.get("Value");
                    if (key != null && key.isJsonPrimitive() && value != null && value.isJsonPrimitive()) {
                        row.put(key.getAsString(), value.getAsString());
                    }
                }
                rows.add(row);
            }
            return rows;
        } catch (NullPointerException | IllegalStateException | ClassCastException e) {
            throw invalid("Unerwartete Suchantwort");
        }
    }

    private static KnowledgeSourceException invalid(String message) {
        return new KnowledgeSourceException(KnowledgeSourceException.Kind.INVALID_RESPONSE, "SharePoint: " + message);
    }
}
