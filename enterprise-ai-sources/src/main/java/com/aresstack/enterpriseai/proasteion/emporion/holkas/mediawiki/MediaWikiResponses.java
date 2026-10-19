package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parst Antworten der MediaWiki-Action-API ({@code formatversion=2}) mit Gson. Statt Jackson (MainframeMate)
 * wird das zentral deklarierte Gson verwendet; die ausgewerteten Felder sind dieselben.
 */
final class MediaWikiResponses {

    private MediaWikiResponses() {
    }

    static JsonObject parseObject(String body) throws KnowledgeSourceException {
        JsonElement element;
        try {
            element = JsonParser.parseString(body);
        } catch (JsonParseException e) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "MediaWiki API returned no valid JSON");
        }
        if (element == null || !element.isJsonObject()) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "MediaWiki API returned no JSON object");
        }
        JsonObject root = element.getAsJsonObject();
        if (root.has("error") && root.get("error").isJsonObject()) {
            JsonObject error = root.getAsJsonObject("error");
            String code = string(error, "code", "unknown");
            String info = string(error, "info", "");
            throw new KnowledgeSourceException(kindOfError(code),
                    "MediaWiki API error '" + code + "'" + (info.isEmpty() ? "" : ": " + abbreviate(info, 200)));
        }
        return root;
    }

    /** Ordnet MediaWiki-Fehlercodes den neutralen Fehlerarten zu. */
    static Kind kindOfError(String code) {
        if ("missingtitle".equals(code) || "missingpage".equals(code) || "nosuchpageid".equals(code)
                || "nosuchrevid".equals(code)) {
            return Kind.NOT_FOUND;
        }
        if ("readapidenied".equals(code) || "permissiondenied".equals(code) || "notloggedin".equals(code)
                || "assertuserfailed".equals(code) || "assertnameduserfailed".equals(code)
                || "badtoken".equals(code)) {
            return Kind.ACCESS_DENIED;
        }
        if ("maxlag".equals(code) || "ratelimited".equals(code) || "readonly".equals(code)
                || code.startsWith("internal_api_error")) {
            return Kind.UNAVAILABLE;
        }
        if ("invalidtitle".equals(code)) {
            return Kind.NOT_FOUND;
        }
        return Kind.INVALID_RESPONSE;
    }

    static WikiParsedPage parsedPage(String body) throws KnowledgeSourceException {
        JsonObject parse = object(parseObject(body), "parse");
        if (parse == null) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "MediaWiki parse response without 'parse'");
        }
        String html;
        JsonElement text = parse.get("text");
        if (text != null && text.isJsonPrimitive()) {
            html = text.getAsString();
        } else if (text != null && text.isJsonObject()) {
            // formatversion=1: {"text": {"*": "..."}}
            html = string(text.getAsJsonObject(), "*", "");
        } else {
            html = "";
        }
        return new WikiParsedPage(string(parse, "title", ""), number(parse, "pageid"), number(parse, "revid"), html);
    }

    static List<WikiPageInfo> pageInfos(String body) throws KnowledgeSourceException {
        JsonObject query = object(parseObject(body), "query");
        if (query == null) {
            return Collections.emptyList();
        }
        // Ziel -> angefragter Titel, über Weiterleitungen und Normalisierung zurückverfolgt.
        java.util.Map<String, String> origin = new java.util.HashMap<String, String>();
        for (JsonObject mapping : objects(query, "normalized")) {
            origin.put(string(mapping, "to", ""), string(mapping, "from", ""));
        }
        java.util.Map<String, String> redirects = new java.util.HashMap<String, String>();
        for (JsonObject mapping : objects(query, "redirects")) {
            redirects.put(string(mapping, "to", ""), string(mapping, "from", ""));
        }
        List<WikiPageInfo> result = new ArrayList<WikiPageInfo>();
        for (JsonObject page : objects(query, "pages")) {
            boolean missing = page.has("missing") || page.has("invalid");
            String title = string(page, "title", "");
            String requested = redirects.containsKey(title) ? redirects.get(title) : title;
            if (origin.containsKey(requested)) {
                requested = origin.get(requested);
            }
            result.add(new WikiPageInfo(title, number(page, "pageid"),
                    (int) number(page, "ns"), number(page, "lastrevid"), stringOrNull(page, "touched"),
                    stringOrNull(page, "fullurl"), stringOrNull(page, "contentmodel"), missing, requested));
        }
        return result;
    }

    /** Links einer Seite plus Fortsetzungstoken ({@code plcontinue}) oder {@code null}. */
    static LinksPage links(String body) throws KnowledgeSourceException {
        JsonObject root = parseObject(body);
        List<String> titles = new ArrayList<String>();
        JsonObject query = object(root, "query");
        if (query != null) {
            for (JsonObject page : objects(query, "pages")) {
                for (JsonObject link : objects(page, "links")) {
                    String title = string(link, "title", "");
                    if (!title.isEmpty()) {
                        titles.add(title);
                    }
                }
            }
        }
        JsonObject cont = object(root, "continue");
        return new LinksPage(titles, cont == null ? null : stringOrNull(cont, "plcontinue"));
    }

    static List<WikiSearchHit> searchHits(String body) throws KnowledgeSourceException {
        JsonObject query = object(parseObject(body), "query");
        if (query == null) {
            return Collections.emptyList();
        }
        List<WikiSearchHit> hits = new ArrayList<WikiSearchHit>();
        for (JsonObject item : objects(query, "search")) {
            String snippet = Jsoup.parse(string(item, "snippet", "")).text();
            hits.add(new WikiSearchHit(string(item, "title", ""), number(item, "pageid"), snippet,
                    stringOrNull(item, "timestamp")));
        }
        return hits;
    }

    static String loginToken(String body) throws KnowledgeSourceException {
        JsonObject tokens = object(object(parseObject(body), "query"), "tokens");
        return tokens == null ? "" : string(tokens, "logintoken", "");
    }

    static final class LinksPage {

        final List<String> titles;
        final String continuation;

        LinksPage(List<String> titles, String continuation) {
            this.titles = titles;
            this.continuation = continuation;
        }
    }

    static JsonObject object(JsonObject parent, String name) {
        if (parent == null) {
            return null;
        }
        JsonElement element = parent.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    static List<JsonObject> objects(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        List<JsonObject> result = new ArrayList<JsonObject>();
        if (element == null) {
            return result;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement item : array) {
                if (item.isJsonObject()) {
                    result.add(item.getAsJsonObject());
                }
            }
        } else if (element.isJsonObject()) {
            // formatversion=1: pages als Objekt nach Page-ID
            for (java.util.Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    result.add(entry.getValue().getAsJsonObject());
                }
            }
        }
        return result;
    }

    static String string(JsonObject object, String name, String fallback) {
        String value = stringOrNull(object, name);
        return value == null ? fallback : value;
    }

    static String stringOrNull(JsonObject object, String name) {
        if (object == null) {
            return null;
        }
        JsonElement element = object.get(name);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    static long number(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            return 0L;
        }
        try {
            return element.getAsLong();
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    static String abbreviate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
