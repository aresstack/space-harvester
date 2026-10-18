package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-Memory-Confluence hinter {@link ConfluenceHttpTransport}: beantwortet die REST-Endpunkte, die der Adapter
 * benutzt, mit JSON in der Form von Confluence Data Center und protokolliert alle Requests.
 */
public final class FakeConfluence implements ConfluenceHttpTransport {

    /** Basis-URL, die der Fake bedient; {@link FakeConfluenceServer} bildet echte HTTP-Anfragen darauf ab. */
    public static final URI BASE = URI.create("https://confluence.example.org/wiki");

    private static final String ID_CQL = "type=page AND id in (";
    private static final java.util.regex.Pattern CHANGED_CQL = java.util.regex.Pattern.compile(
            "space=\"([^\"]+)\" AND type=page AND lastmodified >= now\\(\"-([0-9]+)m\"\\) ORDER BY lastmodified DESC");

    private static java.time.Instant modified(FakePage page) {
        return java.time.OffsetDateTime.parse(page.when).toInstant();
    }

    final Map<String, FakePage> pages = new LinkedHashMap<String, FakePage>();
    final Map<String, String> spaceHomepages = new LinkedHashMap<String, String>();
    /** Spaces mit Namen für {@code /rest/api/space}; ohne Eintrag in {@link #spaceHomepages} ohne Startseite. */
    final Map<String, String> spaceNames = new LinkedHashMap<String, String>();
    final Map<String, FakeAttachment> attachments = new LinkedHashMap<String, FakeAttachment>();
    final List<URI> requests = new ArrayList<URI>();
    final List<Map<String, String>> requestHeaders = new ArrayList<Map<String, String>>();
    /** Ergebnis-IDs der nächsten CQL-Suche; die letzte gesendete CQL steht in {@link #lastCql}. */
    final List<String> searchResults = new ArrayList<String>();
    /**
     * Treffer, die die Suche wie die reale Confluence-Antwort in einer Such-Hülle liefert:
     * {@code {content: {...}, title, excerpt, url}}; Wert ist der Ausschnitt mit Hervorhebungsmarken.
     */
    final Map<String, String> wrappedSearchResults = new LinkedHashMap<String, String>();
    String lastCql;
    /** Query parameters of the last request to the search endpoint {@code /rest/api/search}. */
    Map<String, String> lastSearchQuery;
    /** Search pages carry no {@code _links.next}; the client must page by {@code start} and {@code totalSize}. */
    boolean searchWithoutNextLinks;
    /** Search results that lack {@code content.version} and {@code _links.webui} (wrapper fields only). */
    boolean searchContentWithoutVersionAndLinks;
    /** Pages the user may not see: 403 on reading, missing from lists and searches. */
    final Set<String> restricted = new LinkedHashSet<String>();
    /** Erzwingt eine feste Antwort für alle Requests (Fehlerfälle). */
    ConfluenceHttpResponse forcedResponse;
    IOException forcedFailure;
    /** Körper der letzten Antwort auf {@code /rest/api/content/<seiten-id>}, genau wie gesendet. */
    byte[] lastPageBody;
    /** Liefert statt des echten Folgelinks einen Link außerhalb der REST-API. */
    String nextLinkOverride;
    /** Seiten im Papierkorb: nur mit {@code status=trashed} gelistet, sonst nicht gefunden. */
    final Set<String> trashed = new LinkedHashSet<String>();
    /** Uhr von Confluence für {@code now("-Nm")} in CQL. */
    java.time.Instant now = java.time.Instant.parse("2026-10-18T12:00:00Z");
    /** Fehler für die nächsten Anfragen, in Reihenfolge: HTTP-Status ({@link Integer}) oder {@link IOException}. */
    final Deque<Object> scriptedFailures = new ArrayDeque<Object>();

    public FakePage page(String id, String title, String spaceKey, String html) {
        FakePage page = new FakePage(id, title, spaceKey, html);
        pages.put(id, page);
        return page;
    }

    public void child(String parentId, String childId) {
        pages.get(parentId).children.add(childId);
        pages.get(childId).parentId = parentId;
    }

    public FakeAttachment attachment(String id, String pageId, String title, String mediaType, String content) {
        FakeAttachment attachment = new FakeAttachment(id, pageId, title, mediaType,
                content.getBytes(StandardCharsets.UTF_8));
        attachments.put(id, attachment);
        pages.get(pageId).attachments.add(id);
        return attachment;
    }

    /** Verschiebt eine Seite als Wurzelseite in einen anderen Space; die Content-ID bleibt wie in Confluence. */
    public void move(String pageId, String newSpaceKey) {
        FakePage page = pages.get(pageId);
        if (page.parentId != null) {
            pages.get(page.parentId).children.remove(pageId);
            page.parentId = null;
        }
        page.spaceKey = newSpaceKey;
    }

    /** Körper der letzten Antwort auf eine Seite, genau wie gesendet; {@code null} vor dem ersten Abruf. */
    public byte[] lastPageBody() {
        return lastPageBody == null ? null : lastPageBody.clone();
    }

    /** Startseite eines Bereichs ({@code /rest/api/space/<key>} liefert sie als {@code homepage}). */
    public void homepage(String spaceKey, String pageId) {
        spaceHomepages.put(spaceKey, pageId);
    }

    /** Ein Space mit Namen (Startseite optional über {@link #homepage}). */
    public void space(String spaceKey, String name) {
        spaceNames.put(spaceKey, name);
    }

    /** Verschiebt eine Seite in den Papierkorb ihres Space. */
    public void trash(String pageId) {
        trashed.add(pageId);
    }

    /** Hide a page from the user (permission restriction). */
    public void restrict(String pageId) {
        restricted.add(pageId);
    }

    /** Ergebnis-IDs der nächsten CQL-Suche; Anhang-IDs erscheinen als Nicht-Seiten-Treffer. */
    public void searchResults(String... pageIds) {
        Collections.addAll(searchResults, pageIds);
    }

    /** Wie {@link #searchResults(String...)}, aber als Such-Hülle {@code {content: {...}, excerpt}}. */
    public void wrappedSearchResult(String id, String excerpt) {
        searchResults.add(id);
        wrappedSearchResults.put(id, excerpt);
    }

    /** Die nächsten {@code times} Anfragen beantworten mit {@code status} (z. B. 429, 503) statt mit Daten. */
    public FakeConfluence failNext(int status, int times) {
        for (int i = 0; i < times; i++) {
            scriptedFailures.add(Integer.valueOf(status));
        }
        return this;
    }

    /** Die nächsten {@code times} Anfragen scheitern mit einem Netzfehler (Zeitüberschreitung). */
    public FakeConfluence failNextWithTimeout(int times) {
        for (int i = 0; i < times; i++) {
            scriptedFailures.add(new java.net.SocketTimeoutException("Read timed out"));
        }
        return this;
    }

    /** Kopie aller bisher gesehenen Anfrage-URIs. */
    public List<URI> requests() {
        return new ArrayList<URI>(requests);
    }

    /** Kopie der Header aller bisher gesehenen Anfragen, in Reihenfolge der Anfragen. */
    public List<Map<String, String>> requestHeaders() {
        return new ArrayList<Map<String, String>>(requestHeaders);
    }

    public int requestCount(String pathPart) {
        int count = 0;
        for (URI uri : requests) {
            if (uri.toString().contains(pathPart)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public ConfluenceHttpResponse get(URI uri, Map<String, String> headers, int maxBytes) throws IOException {
        requests.add(uri);
        requestHeaders.add(headers);
        Object scripted = scriptedFailures.poll();
        if (scripted instanceof IOException) {
            throw (IOException) scripted;
        }
        if (scripted != null) {
            return new ConfluenceHttpResponse((Integer) scripted, "application/json",
                    ("{\"statusCode\":" + scripted + "}").getBytes(StandardCharsets.UTF_8));
        }
        if (forcedFailure != null) {
            throw forcedFailure;
        }
        if (forcedResponse != null) {
            return forcedResponse;
        }
        String base = BASE.getPath();
        String path = uri.getRawPath();
        if (!uri.getHost().equals(BASE.getHost()) || !path.startsWith(base + "/")) {
            return notFound();
        }
        path = path.substring(base.length());
        Map<String, String> query = query(uri.getRawQuery());
        String[] parts = path.split("/");
        if (path.startsWith("/rest/api/space/") && parts.length == 5) {
            String key = decode(parts[4]);
            if (!spaceHomepages.containsKey(key) && !spaceNames.containsKey(key)) {
                return notFound();
            }
            return json(spaceJson(key));
        }
        if (path.startsWith("/rest/api/space/") && parts.length == 7 && "content".equals(parts[5])
                && "page".equals(parts[6])) {
            String key = decode(parts[4]);
            List<FakePage> roots = new ArrayList<FakePage>();
            for (FakePage page : pages.values()) {
                if (page.spaceKey.equals(key) && page.parentId == null && !restricted.contains(page.id)) {
                    roots.add(page);
                }
            }
            int start = Integer.parseInt(query.getOrDefault("start", "0"));
            int limit = Integer.parseInt(query.getOrDefault("limit", "25"));
            JsonArray results = new JsonArray();
            for (int i = start; i < roots.size() && results.size() < limit; i++) {
                results.add(pageJson(roots.get(i), query.get("expand")));
            }
            return json(page(results, nextLink(uri, start + results.size(), roots.size())));
        }
        if (path.equals("/rest/api/space")) {
            Set<String> keys = new LinkedHashSet<String>(spaceNames.keySet());
            keys.addAll(spaceHomepages.keySet());
            List<String> all = new ArrayList<String>(keys);
            int start = Integer.parseInt(query.getOrDefault("start", "0"));
            int limit = Integer.parseInt(query.getOrDefault("limit", "25"));
            JsonArray results = new JsonArray();
            for (int i = start; i < all.size() && results.size() < limit; i++) {
                results.add(spaceJson(all.get(i)));
            }
            return json(page(results, nextLink(uri, start + results.size(), all.size())));
        }
        if (path.equals("/rest/api/content") && query.containsKey("spaceKey")) {
            List<FakePage> inSpace = new ArrayList<FakePage>();
            boolean inTrash = "trashed".equals(query.get("status"));
            for (FakePage page : pages.values()) {
                if (page.spaceKey.equals(query.get("spaceKey")) && !restricted.contains(page.id)
                        && trashed.contains(page.id) == inTrash
                        && (!query.containsKey("title") || page.title.equals(query.get("title")))) {
                    inSpace.add(page);
                }
            }
            int start = Integer.parseInt(query.getOrDefault("start", "0"));
            int limit = Integer.parseInt(query.getOrDefault("limit", "25"));
            JsonArray results = new JsonArray();
            for (int i = start; i < inSpace.size() && results.size() < limit; i++) {
                results.add(pageJson(inSpace.get(i), query.get("expand")));
            }
            return json(page(results, nextLink(uri, start + results.size(), inSpace.size())));
        }
        if (path.equals("/rest/api/search")) {
            lastCql = query.get("cql");
            lastSearchQuery = query;
            int start = Integer.parseInt(query.getOrDefault("start", "0"));
            int limit = Integer.parseInt(query.get("limit"));
            JsonArray results = new JsonArray();
            for (int i = start; i < searchResults.size() && results.size() < limit; i++) {
                results.add(siteSearchEntry(searchResults.get(i), query.get("expand")));
            }
            JsonObject response = page(results, searchWithoutNextLinks ? null
                    : nextLink(uri, start + results.size(), searchResults.size()));
            response.addProperty("start", start);
            response.addProperty("limit", limit);
            response.addProperty("size", results.size());
            response.addProperty("totalSize", searchResults.size());
            return json(response);
        }
        if (path.equals("/rest/api/content/search")) {
            lastCql = query.get("cql");
            List<String> found = searchResults;
            java.util.regex.Matcher changed = lastCql == null ? null : CHANGED_CQL.matcher(lastCql);
            if (changed != null && changed.matches()) {
                java.time.Instant since = now.minus(java.time.Duration.ofMinutes(Long.parseLong(changed.group(2))));
                List<FakePage> recent = new ArrayList<FakePage>();
                for (FakePage page : pages.values()) {
                    if (page.spaceKey.equals(changed.group(1)) && !restricted.contains(page.id)
                            && !trashed.contains(page.id) && !modified(page).isBefore(since)) {
                        recent.add(page);
                    }
                }
                Collections.sort(recent, new java.util.Comparator<FakePage>() {
                    @Override
                    public int compare(FakePage a, FakePage b) {
                        return modified(b).compareTo(modified(a));
                    }
                });
                found = new ArrayList<String>();
                for (FakePage page : recent) {
                    found.add(page.id);
                }
            } else if (lastCql != null && lastCql.startsWith(ID_CQL) && lastCql.endsWith(")")) {
                found = new ArrayList<String>();
                for (String id : lastCql.substring(ID_CQL.length(), lastCql.length() - 1).split(",")) {
                    if (pages.containsKey(id.trim()) && !restricted.contains(id.trim())) {
                        found.add(id.trim());
                    }
                }
            }
            int start = Integer.parseInt(query.getOrDefault("start", "0"));
            int limit = Integer.parseInt(query.get("limit"));
            JsonArray results = new JsonArray();
            for (int i = start; i < found.size() && results.size() < limit; i++) {
                results.add(searchEntry(found.get(i), query.get("expand")));
            }
            return json(page(results, nextLink(uri, start + results.size(), found.size())));
        }
        if (path.startsWith("/rest/api/content/") && parts.length == 5) {
            String id = parts[4];
            if (restricted.contains(id)) {
                return forbidden();
            }
            if (trashed.contains(id)) {
                return notFound();
            }
            if (pages.containsKey(id)) {
                FakePage page = pages.get(id);
                String expand = query.get("expand");
                byte[] body = page.rawJson != null && expand != null && expand.contains("body.storage")
                        ? page.rawJson.getBytes(StandardCharsets.UTF_8)
                        : pageJson(page, expand).toString().getBytes(StandardCharsets.UTF_8);
                if (body.length > maxBytes) {
                    throw new ConfluenceResponseTooLargeException(maxBytes);
                }
                lastPageBody = body;
                return new ConfluenceHttpResponse(200, "application/json;charset=UTF-8", body);
            }
            if (attachments.containsKey(id)) {
                return json(attachmentJson(attachments.get(id)));
            }
            return notFound();
        }
        if (path.startsWith("/rest/api/content/") && parts.length == 7 && "child".equals(parts[5])) {
            FakePage parent = pages.get(parts[4]);
            if (parent == null) {
                return notFound();
            }
            if (restricted.contains(parent.id)) {
                return forbidden();
            }
            List<String> ids = new ArrayList<String>("page".equals(parts[6]) ? parent.children : parent.attachments);
            ids.removeAll(restricted);
            int start = Integer.parseInt(query.get("start"));
            int limit = Integer.parseInt(query.get("limit"));
            JsonArray results = new JsonArray();
            for (int i = start; i < ids.size() && results.size() < limit; i++) {
                results.add("page".equals(parts[6]) ? pageJson(pages.get(ids.get(i)), query.get("expand"))
                        : attachmentJson(attachments.get(ids.get(i))));
            }
            return json(page(results, nextLink(uri, start + results.size(), ids.size())));
        }
        if (path.startsWith("/download/attachments/")) {
            for (FakeAttachment attachment : attachments.values()) {
                if (path.equals(attachment.downloadPath().split("\\?")[0])) {
                    if (attachment.content.length > maxBytes) {
                        throw new ConfluenceResponseTooLargeException(maxBytes);
                    }
                    return new ConfluenceHttpResponse(200, attachment.mediaType, attachment.content);
                }
            }
        }
        return notFound();
    }

    /** Folgelink wie Confluence Data Center: relativ zum Kontextpfad, Query unverändert bis auf {@code start}. */
    private String nextLink(URI uri, int nextStart, int total) {
        if (nextStart >= total) {
            return null;
        }
        if (nextLinkOverride != null) {
            return nextLinkOverride;
        }
        return uri.getRawPath().substring(BASE.getPath().length()) + "?"
                + uri.getRawQuery().replaceAll("start=\\d+", "start=" + nextStart);
    }

    private JsonObject spaceJson(String key) {
        JsonObject space = new JsonObject();
        space.addProperty("key", key);
        space.addProperty("name", spaceNames.containsKey(key) ? spaceNames.get(key) : key + " Space");
        if (spaceHomepages.containsKey(key)) {
            JsonObject home = new JsonObject();
            home.addProperty("id", spaceHomepages.get(key));
            space.add("homepage", home);
        }
        return space;
    }

    private static JsonObject page(JsonArray results, String next) {
        JsonObject page = new JsonObject();
        page.add("results", results);
        page.addProperty("size", results.size());
        JsonObject links = new JsonObject();
        if (next != null) {
            links.addProperty("next", next);
        }
        page.add("_links", links);
        return page;
    }

    /** A result of {@code /rest/api/search} as Confluence Data Center sends it: always a wrapper. */
    private JsonObject siteSearchEntry(String id, String expand) {
        String contentExpand = expand == null ? null : expand.replace("content.", "");
        JsonObject content = pages.containsKey(id) ? pageJson(pages.get(id), contentExpand)
                : attachmentJson(attachments.get(id));
        String title = content.get("title").getAsString();
        if (searchContentWithoutVersionAndLinks) {
            content.remove("version");
            content.remove("_links");
        }
        JsonObject wrapper = new JsonObject();
        wrapper.add("content", content);
        wrapper.addProperty("title", title);
        if (wrappedSearchResults.containsKey(id)) {
            wrapper.addProperty("excerpt", wrappedSearchResults.get(id));
        }
        wrapper.addProperty("url", "/pages/viewpage.action?pageId=" + id);
        wrapper.addProperty("entityType", "content");
        wrapper.addProperty("lastModified", "2026-03-04T05:06:07.000+01:00");
        wrapper.addProperty("friendlyLastModified", "Mär 04, 2026");
        return wrapper;
    }

    private JsonObject searchEntry(String id, String expand) {
        JsonObject content = pages.containsKey(id) ? pageJson(pages.get(id), expand)
                : attachmentJson(attachments.get(id));
        if (!wrappedSearchResults.containsKey(id)) {
            return content;
        }
        JsonObject wrapper = new JsonObject();
        wrapper.add("content", content);
        wrapper.addProperty("title", content.get("title").getAsString());
        wrapper.addProperty("excerpt", wrappedSearchResults.get(id));
        wrapper.addProperty("url", "/pages/viewpage.action?pageId=" + id);
        wrapper.addProperty("entityType", "content");
        return wrapper;
    }

    private JsonObject pageJson(FakePage page, String expand) {
        boolean withView = expand != null && expand.contains("body.view");
        boolean withExport = expand != null && expand.contains("body.export_view");
        boolean withLabels = expand != null && expand.contains("metadata.labels");
        boolean withStorage = expand != null && expand.contains("body.storage") && page.storage != null;
        JsonObject json = new JsonObject();
        json.addProperty("id", page.id);
        json.addProperty("type", "page");
        json.addProperty("title", page.title);
        JsonObject space = new JsonObject();
        space.addProperty("key", page.spaceKey);
        space.addProperty("name", spaceNames.containsKey(page.spaceKey) ? spaceNames.get(page.spaceKey)
                : page.spaceKey + " Space");
        json.add("space", space);
        JsonObject version = new JsonObject();
        version.addProperty("number", page.version);
        version.addProperty("when", page.when);
        json.add("version", version);
        // Like Confluence: the whole chain from the top down, with titles.
        JsonArray ancestors = new JsonArray();
        for (String ancestorId = page.parentId; ancestorId != null; ancestorId = pages.get(ancestorId).parentId) {
            JsonObject ancestor = new JsonObject();
            ancestor.addProperty("id", ancestorId);
            ancestor.addProperty("type", "page");
            ancestor.addProperty("title", pages.get(ancestorId).title);
            JsonArray before = new JsonArray();
            before.add(ancestor);
            before.addAll(ancestors);
            ancestors = before;
        }
        json.add("ancestors", ancestors);
        if (expand != null && expand.contains("childTypes.page")) {
            List<String> visible = new ArrayList<String>(page.children);
            visible.removeAll(restricted);
            JsonObject childPage = new JsonObject();
            childPage.addProperty("value", !visible.isEmpty());
            JsonObject childTypes = new JsonObject();
            childTypes.add("page", childPage);
            json.add("childTypes", childTypes);
        }
        if (withView || withExport || withStorage) {
            JsonObject body = new JsonObject();
            if (withStorage) {
                JsonObject storage = new JsonObject();
                storage.addProperty("value", page.storage);
                storage.addProperty("representation", "storage");
                body.add("storage", storage);
            }
            if (withView) {
                // Wie Confluence: body.view lässt asynchrone Makros (Jira) als Platzhalter stehen.
                JsonObject view = new JsonObject();
                view.addProperty("value", page.html);
                view.addProperty("representation", "view");
                body.add("view", view);
            }
            if (withExport) {
                JsonObject export = new JsonObject();
                export.addProperty("value", page.exportHtml == null ? page.html : page.exportHtml);
                export.addProperty("representation", "export_view");
                body.add("export_view", export);
            }
            json.add("body", body);
        }
        if (withLabels) {
            JsonArray labels = new JsonArray();
            for (String name : page.labels) {
                JsonObject label = new JsonObject();
                label.addProperty("name", name);
                labels.add(label);
            }
            JsonObject labelPage = new JsonObject();
            labelPage.add("results", labels);
            labelPage.addProperty("size", page.labels.size());
            if (page.moreLabels) {
                JsonObject labelLinks = new JsonObject();
                labelLinks.addProperty("next", "/rest/api/content/" + page.id + "/label?start=" + page.labels.size());
                labelPage.add("_links", labelLinks);
            }
            JsonObject metadata = new JsonObject();
            metadata.add("labels", labelPage);
            json.add("metadata", metadata);
        }
        if (expand != null && expand.contains("history")) {
            JsonObject history = new JsonObject();
            history.addProperty("createdDate", page.createdDate);
            JsonObject createdBy = new JsonObject();
            createdBy.addProperty("type", "known");
            createdBy.addProperty("username", page.createdBy);
            createdBy.addProperty("userKey", "key-" + page.createdBy);
            history.add("createdBy", createdBy);
            json.add("history", history);
        }
        JsonObject links = new JsonObject();
        links.addProperty("webui", "/pages/viewpage.action?pageId=" + page.id);
        json.add("_links", links);
        return json;
    }

    private JsonObject attachmentJson(FakeAttachment attachment) {
        JsonObject json = new JsonObject();
        json.addProperty("id", attachment.id);
        json.addProperty("type", "attachment");
        json.addProperty("title", attachment.title);
        JsonObject metadata = new JsonObject();
        metadata.addProperty("mediaType", attachment.mediaType);
        json.add("metadata", metadata);
        JsonObject extensions = new JsonObject();
        extensions.addProperty("mediaType", attachment.mediaType);
        extensions.addProperty("fileSize", attachment.content.length);
        json.add("extensions", extensions);
        JsonObject version = new JsonObject();
        version.addProperty("number", 1);
        version.addProperty("when", "2026-10-02T10:00:00.000Z");
        json.add("version", version);
        JsonObject container = new JsonObject();
        container.addProperty("id", attachment.pageId);
        container.addProperty("type", "page");
        json.add("container", container);
        JsonObject links = new JsonObject();
        links.addProperty("download", attachment.downloadOverride != null
                ? attachment.downloadOverride : attachment.downloadPath());
        links.addProperty("webui", "/pages/viewpageattachments.action?pageId=" + attachment.pageId);
        json.add("_links", links);
        return json;
    }

    private static ConfluenceHttpResponse json(JsonObject json) {
        return new ConfluenceHttpResponse(200, "application/json;charset=UTF-8",
                json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static ConfluenceHttpResponse forbidden() {
        return new ConfluenceHttpResponse(403, "application/json",
                "{\"statusCode\":403,\"message\":\"Not permitted\"}".getBytes(StandardCharsets.UTF_8));
    }

    private static ConfluenceHttpResponse notFound() {
        return new ConfluenceHttpResponse(404, "application/json",
                "{\"statusCode\":404,\"message\":\"No content found\"}".getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        if (raw == null) {
            return map;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            map.put(decode(eq < 0 ? pair : pair.substring(0, eq)), eq < 0 ? "" : decode(pair.substring(eq + 1)));
        }
        return map;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final class FakePage {
        final String id;
        final String title;
        String spaceKey;
        final String html;
        /** {@code body.export_view}, falls es von {@code body.view} abweicht (Makros server-seitig gerendert). */
        String exportHtml;
        final List<String> children = new ArrayList<String>();
        final List<String> attachments = new ArrayList<String>();
        final List<String> labels = new ArrayList<String>();
        /** Confluence hat weitere Labels, die nicht in der Antwort stehen ({@code _links.next}). */
        boolean moreLabels;
        String parentId;
        int version = 3;
        String when = "2026-10-01T08:15:00.000+02:00";
        String createdDate = "2026-09-01T09:00:00.000+02:00";
        String createdBy = "autorin";
        /** {@code body.storage}; ohne Wert fehlt es in der Antwort wie bisher. */
        String storage;
        /** Feste Antwort für Abrufe mit {@code body.storage}, Byte für Byte (Leerraum, Escapes, Reihenfolge). */
        String rawJson;

        FakePage(String id, String title, String spaceKey, String html) {
            this.id = id;
            this.title = title;
            this.spaceKey = spaceKey;
            this.html = html;
        }

        public FakePage storage(String value) {
            this.storage = value;
            return this;
        }

        public FakePage rawJson(String json) {
            this.rawJson = json;
            return this;
        }

        /** Setzt eine neue Version mit Änderungszeitpunkt. */
        public FakePage version(int number, String modified) {
            this.version = number;
            this.when = modified;
            return this;
        }

        /** Setzt {@code body.export_view} abweichend von {@code body.view}. */
        public FakePage exportView(String exportHtml) {
            this.exportHtml = exportHtml;
            return this;
        }

        /** Fügt Labels hinzu. */
        public FakePage labels(String... names) {
            Collections.addAll(labels, names);
            return this;
        }

        /** Die Labels in der Antwort sind nur die erste Seite; Confluence verweist mit {@code _links.next} weiter. */
        public FakePage moreLabels() {
            this.moreLabels = true;
            return this;
        }
    }

    public static final class FakeAttachment {
        final String id;
        final String pageId;
        final String title;
        final String mediaType;
        final byte[] content;
        String downloadOverride;

        FakeAttachment(String id, String pageId, String title, String mediaType, byte[] content) {
            this.id = id;
            this.pageId = pageId;
            this.title = title;
            this.mediaType = mediaType;
            this.content = content;
        }

        String downloadPath() {
            return "/download/attachments/" + pageId + "/" + title + "?version=1&api=v2";
        }
    }
}
