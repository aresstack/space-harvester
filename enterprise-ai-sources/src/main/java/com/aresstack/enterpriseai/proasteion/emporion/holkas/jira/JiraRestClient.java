package com.aresstack.enterpriseai.proasteion.emporion.holkas.jira;

import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.RateLimitedException;
import com.aresstack.enterpriseai.source.api.ResponseTooLargeException;
import com.aresstack.enterpriseai.source.api.RestResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Jira-Data-Center-REST-Aufrufe (paketintern, REST-API 2, nur GET). JSON über Gson.
 *
 * <p>HTTP-Status werden auf {@link Kind} abgebildet: 401/403 und Weiterleitungen (Anmeldeseite) →
 * {@code ACCESS_DENIED}, 404 → {@code NOT_FOUND}, 429/5xx und Netzfehler → {@code UNAVAILABLE} (429 als
 * {@link RateLimitedException}), alles andere → {@code INVALID_RESPONSE}. Meldungen nennen Vorgang und Status, nie
 * Antwortkörper oder Header. REST-Aufrufe werden bei Netzfehler, 429 und 5xx bis zu {@link JiraConfig#maxRetries()}
 * Mal mit wachsender Wartezeit wiederholt.
 */
final class JiraRestClient {

    private static final Logger LOG = Logger.getLogger(JiraRestClient.class.getName());

    static final String API = "/rest/api/2";
    /** {@code Accept} beim Datei- und Bildrequest (statt {@code application/json} der REST-Aufrufe). */
    static final String FILE_ACCEPT = "*/*";

    /** Felder einer Listenzeile (Suche, Auflistung): ohne Beschreibung und Kommentare. */
    static final String LIST_FIELDS = "summary,issuetype,status,priority,assignee,reporter,created,updated,"
            + "resolution,resolutiondate,labels,components,fixVersions,project,parent";
    /** Felder für Suchtreffer: zusätzlich die Beschreibung für den Ausschnitt. */
    static final String SEARCH_FIELDS = LIST_FIELDS + ",description";
    /** Felder eines ganzen Vorgangs (Text, Lese-Ansicht, Snapshot). */
    static final String ISSUE_FIELDS = SEARCH_FIELDS + ",comment,attachment,issuelinks,subtasks,environment";
    /** Gerenderte Felder (HTML) liefert Jira über {@code expand=renderedFields}. */
    static final String ISSUE_EXPAND = "renderedFields";

    /** Jira Data Center liefert Zeitstempel mit kompaktem Offset ({@code 2026-01-05T10:15:30.000+0100}). */
    private static final DateTimeFormatter COMPACT_OFFSET =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss[.SSS]XX", Locale.ROOT);

    private final JiraConfig config;
    private final JiraHttpTransport transport;

    JiraRestClient(JiraConfig config, JiraHttpTransport transport) {
        this.config = config;
        this.transport = transport;
    }

    /** Alle für das Konto sichtbaren Projekte ({@code /project}), in Jira-Reihenfolge. */
    List<Project> projects(Session session) throws KnowledgeSourceException {
        JsonElement element = json(send(session, API + "/project", "Projekte", config.maxResponseBytes()),
                "Projekte");
        if (!element.isJsonArray()) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "Projekte: JSON-Liste erwartet");
        }
        List<Project> projects = new ArrayList<Project>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject project = item.getAsJsonObject();
            String key = string(project, "key");
            if (JiraIds.isProjectKey(key)) {
                projects.add(new Project(key, string(project, "name"), string(project, "id")));
            }
        }
        return projects;
    }

    /** Ein Projekt oder {@code null}, wenn es das Konto nicht sieht. */
    Project project(Session session, String key) throws KnowledgeSourceException {
        JsonObject project;
        try {
            project = getJson(session, API + "/project/" + pathSegment(key), "Projekt");
        } catch (KnowledgeSourceException e) {
            if (e.kind() == Kind.NOT_FOUND) {
                return null;
            }
            throw e;
        }
        return new Project(string(project, "key"), string(project, "name"), string(project, "id"));
    }

    /**
     * Ein Vorgang mit allen Feldern, gerenderten Feldern und Kommentaren; die Antwort bleibt als Rohdaten erhalten.
     * Fehlen Kommentare in der Antwort (Jira kappt {@code fields.comment}), holt {@link #comments} den Rest nach.
     */
    Issue issue(Session session, String key) throws KnowledgeSourceException {
        String what = "Vorgang " + key;
        JiraHttpResponse response = send(session, API + "/issue/" + pathSegment(key) + "?fields="
                + encode(ISSUE_FIELDS) + "&expand=" + ISSUE_EXPAND, what, config.maxResponseBytes());
        JsonObject json = jsonObject(response, what);
        Issue issue = issue(json, response.bodyAsUtf8());
        if (issue == null) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, what + ": Antwort ohne Schlüssel");
        }
        return issue;
    }

    /** Die Kommentare eines Vorgangs mit gerendertem Text, höchstens {@code max}; blättert über {@code startAt}. */
    List<Comment> comments(Session session, String key, int max) throws KnowledgeSourceException {
        List<Comment> comments = new ArrayList<Comment>();
        int startAt = 0;
        while (comments.size() < max) {
            int batch = Math.min(config.pageSize(), max - comments.size());
            JsonObject page = getJson(session, API + "/issue/" + pathSegment(key) + "/comment?startAt=" + startAt
                    + "&maxResults=" + batch + "&expand=renderedBody", "Kommentare " + key);
            JsonArray items = array(page, "comments");
            if (items == null || items.size() == 0) {
                break;
            }
            for (JsonElement item : items) {
                if (item.isJsonObject() && comments.size() < max) {
                    comments.add(comment(item.getAsJsonObject(), null));
                }
            }
            startAt += items.size();
            int total = integer(page, "total", -1);
            if (total >= 0 && startAt >= total) {
                break;
            }
        }
        return comments;
    }

    /**
     * Eine JQL-Suche ({@code /search}) ab {@code startAt} mit höchstens {@code maxResults} Treffern und den
     * genannten Feldern; die Vorgänge der Antwort kommen ohne Rohdaten je Vorgang.
     */
    SearchResult search(Session session, String jql, int startAt, int maxResults, String fields)
            throws KnowledgeSourceException {
        JsonObject page = getJson(session, API + "/search?jql=" + encode(jql) + "&startAt=" + startAt
                + "&maxResults=" + maxResults + "&fields=" + encode(fields), "JQL-Suche");
        List<Issue> issues = new ArrayList<Issue>();
        JsonArray items = array(page, "issues");
        if (items == null) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "JQL-Suche: Antwort ohne issues");
        }
        for (JsonElement item : items) {
            if (item.isJsonObject()) {
                Issue issue = issue(item.getAsJsonObject(), null);
                if (issue != null) {
                    issues.add(issue);
                }
            }
        }
        int total = integer(page, "total", -1);
        int next = startAt + items.size();
        boolean more = items.size() > 0 && (total < 0 ? items.size() >= maxResults : next < total);
        return new SearchResult(issues, more ? next : -1, total);
    }

    /** Eine GET-Abfrage unter {@code /rest/} mit der Anmeldung der Quelle; die Antwort kommt unverändert. */
    RestResponse restGet(Session session, String pathAndQuery) throws KnowledgeSourceException {
        if (pathAndQuery == null || !pathAndQuery.startsWith("/rest/") || pathAndQuery.contains("..")) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "REST-Abfrage: Pfad muss mit /rest/ beginnen");
        }
        JiraHttpResponse response = send(session, pathAndQuery, "REST-Abfrage", config.maxResponseBytes());
        return new RestResponse(response.contentType(), response.body());
    }

    /**
     * Eine Datei (Bild, Anhang) unter der Basis-URL dieser Jira mit der Anmeldung der Quelle. Fremde Hosts bekommen
     * keinen Header und werden abgelehnt.
     */
    byte[] download(Session session, URI target, int maxBytes) throws KnowledgeSourceException {
        return download(session, target, maxBytes, false);
    }

    /**
     * @param htmlExpected {@code true}, wenn die Datei selbst HTML ist (Anhang mit Medientyp {@code text/html});
     *                     sonst gilt eine HTML-Antwort als Anmeldeseite und wird als {@code ACCESS_DENIED} abgelehnt
     */
    byte[] download(Session session, URI target, int maxBytes, boolean htmlExpected) throws KnowledgeSourceException {
        if (!underBase(target)) {
            throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Datei außerhalb dieser Jira: " + target.getHost());
        }
        JiraHttpResponse response;
        try {
            response = transport.get(target, session.headers(FILE_ACCEPT), maxBytes);
        } catch (IOException e) {
            throw transportFailure(e, "Datei", maxBytes);
        }
        if (!response.isSuccess()) {
            throw statusFailure("Datei", response.status());
        }
        if (!htmlExpected && response.contentType().toLowerCase(Locale.ROOT).contains("html")) {
            throw new KnowledgeSourceException(Kind.ACCESS_DENIED, "Datei: HTML statt Datei (Anmeldeseite?)");
        }
        return response.body();
    }

    /** Ob die Adresse unter der Basis-URL liegt: Schema, Host (ohne Groß-/Kleinschreibung), Port und Kontextpfad. */
    boolean underBase(URI target) {
        URI base = config.baseUrl();
        if (target == null || target.getScheme() == null || target.getHost() == null) {
            return false;
        }
        if (!base.getScheme().equalsIgnoreCase(target.getScheme())
                || !base.getHost().equalsIgnoreCase(target.getHost())
                || port(base) != port(target)) {
            return false;
        }
        String path = target.getRawPath() == null ? "" : target.getRawPath();
        String basePath = base.getRawPath() == null ? "" : base.getRawPath();
        return basePath.isEmpty() || path.equals(basePath) || path.startsWith(basePath + "/");
    }

    private static int port(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    // --- JSON → Modell -------------------------------------------------------------------------------------

    /** Ein Vorgang aus der Antwort von {@code /issue} oder einem Eintrag von {@code /search}; {@code null} ohne Schlüssel. */
    private Issue issue(JsonObject json, String raw) {
        String key = string(json, "key");
        if (!JiraIds.isIssueKey(key)) {
            return null;
        }
        JsonObject fields = object(json, "fields");
        JsonObject rendered = object(json, "renderedFields");
        Issue issue = new Issue(key, string(json, "id"), raw);
        if (fields == null) {
            return issue;
        }
        issue.summary = string(fields, "summary");
        issue.description = string(fields, "description");
        issue.renderedDescription = rendered == null ? null : string(rendered, "description");
        issue.environment = string(fields, "environment");
        issue.renderedEnvironment = rendered == null ? null : string(rendered, "environment");
        issue.issueType = name(object(fields, "issuetype"));
        issue.status = name(object(fields, "status"));
        issue.priority = name(object(fields, "priority"));
        issue.resolution = name(object(fields, "resolution"));
        issue.assignee = displayName(object(fields, "assignee"));
        issue.reporter = displayName(object(fields, "reporter"));
        issue.created = instant(string(fields, "created"));
        issue.updated = instant(string(fields, "updated"));
        issue.resolved = instant(string(fields, "resolutiondate"));
        JsonObject project = object(fields, "project");
        issue.projectKey = project == null ? null : string(project, "key");
        issue.projectName = project == null ? null : string(project, "name");
        JsonObject parent = object(fields, "parent");
        issue.parentKey = parent == null ? null : string(parent, "key");
        issue.labels = strings(array(fields, "labels"));
        issue.components = names(array(fields, "components"));
        issue.fixVersions = names(array(fields, "fixVersions"));
        JsonArray subtasks = array(fields, "subtasks");
        if (subtasks != null) {
            for (JsonElement item : subtasks) {
                if (item.isJsonObject()) {
                    String subKey = string(item.getAsJsonObject(), "key");
                    JsonObject subFields = object(item.getAsJsonObject(), "fields");
                    if (JiraIds.isIssueKey(subKey)) {
                        issue.links.add(new Link(subKey, "Teilvorgang", subFields == null ? ""
                                : string(subFields, "summary")));
                    }
                }
            }
        }
        if (issue.parentKey != null && JiraIds.isIssueKey(issue.parentKey)) {
            JsonObject parentFields = object(parent, "fields");
            issue.links.add(new Link(issue.parentKey, "Übergeordnet", parentFields == null ? ""
                    : string(parentFields, "summary")));
        }
        JsonArray links = array(fields, "issuelinks");
        if (links != null) {
            for (JsonElement item : links) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject link = item.getAsJsonObject();
                JsonObject type = object(link, "type");
                JsonObject outward = object(link, "outwardIssue");
                JsonObject inward = object(link, "inwardIssue");
                if (outward != null) {
                    addLink(issue, outward, type == null ? "" : string(type, "outward"));
                }
                if (inward != null) {
                    addLink(issue, inward, type == null ? "" : string(type, "inward"));
                }
            }
        }
        JsonArray attachments = array(fields, "attachment");
        if (attachments != null) {
            for (JsonElement item : attachments) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject attachment = item.getAsJsonObject();
                String id = string(attachment, "id");
                if (id == null || id.trim().isEmpty()) {
                    continue;
                }
                issue.attachments.add(new Attachment(id, string(attachment, "filename"),
                        string(attachment, "mimeType"), longValue(attachment, "size"), string(attachment, "content"),
                        string(attachment, "thumbnail"), displayName(object(attachment, "author")),
                        instant(string(attachment, "created"))));
            }
        }
        JsonObject comment = object(fields, "comment");
        JsonObject renderedComment = rendered == null ? null : object(rendered, "comment");
        if (comment != null) {
            JsonArray items = array(comment, "comments");
            JsonArray renderedItems = renderedComment == null ? null : array(renderedComment, "comments");
            if (items != null) {
                for (int i = 0; i < items.size(); i++) {
                    if (!items.get(i).isJsonObject()) {
                        continue;
                    }
                    JsonObject renderedItem = renderedItems != null && i < renderedItems.size()
                            && renderedItems.get(i).isJsonObject() ? renderedItems.get(i).getAsJsonObject() : null;
                    issue.comments.add(comment(items.get(i).getAsJsonObject(), renderedItem));
                }
            }
            issue.commentTotal = integer(comment, "total", issue.comments.size());
        }
        return issue;
    }

    private static void addLink(Issue issue, JsonObject target, String relation) {
        String key = string(target, "key");
        if (!JiraIds.isIssueKey(key)) {
            return;
        }
        JsonObject fields = object(target, "fields");
        issue.links.add(new Link(key, relation == null ? "" : relation, fields == null ? "" : string(fields, "summary")));
    }

    /**
     * Ein Kommentar; der gerenderte Text steht bei {@code expand=renderedFields} in {@code renderedFields.comment}
     * ({@code rendered}), bei {@code /comment?expand=renderedBody} im Eintrag selbst ({@code renderedBody}).
     */
    private static Comment comment(JsonObject json, JsonObject rendered) {
        String html = rendered == null ? string(json, "renderedBody") : string(rendered, "body");
        return new Comment(string(json, "id"), displayName(object(json, "author")), instant(string(json, "created")),
                instant(string(json, "updated")), string(json, "body"), html);
    }

    private static String name(JsonObject json) {
        return json == null ? null : string(json, "name");
    }

    private static String displayName(JsonObject json) {
        if (json == null) {
            return null;
        }
        String name = string(json, "displayName");
        return name == null || name.trim().isEmpty() ? string(json, "name") : name;
    }

    private static List<String> names(JsonArray array) {
        List<String> names = new ArrayList<String>();
        if (array != null) {
            for (JsonElement item : array) {
                String name = item.isJsonObject() ? string(item.getAsJsonObject(), "name") : null;
                if (name != null && !name.trim().isEmpty()) {
                    names.add(name.trim());
                }
            }
        }
        return names;
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<String>();
        if (array != null) {
            for (JsonElement item : array) {
                if (item.isJsonPrimitive() && !item.getAsString().trim().isEmpty()) {
                    values.add(item.getAsString().trim());
                }
            }
        }
        return values;
    }

    /** Zeitstempel von Jira (ISO-8601 oder kompakter Offset) oder {@code null}. */
    static Instant instant(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String text = value.trim();
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(text, COMPACT_OFFSET).toInstant();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    // --- JSON-Hilfen -----------------------------------------------------------------------------------------

    static String string(JsonObject json, String member) {
        if (json == null) {
            return null;
        }
        JsonElement element = json.get(member);
        return element == null || element.isJsonNull() || !element.isJsonPrimitive() ? null : element.getAsString();
    }

    static JsonObject object(JsonObject json, String member) {
        if (json == null) {
            return null;
        }
        JsonElement element = json.get(member);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    static JsonArray array(JsonObject json, String member) {
        if (json == null) {
            return null;
        }
        JsonElement element = json.get(member);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    static int integer(JsonObject json, String member, int def) {
        JsonElement element = json == null ? null : json.get(member);
        if (element == null || !element.isJsonPrimitive()) {
            return def;
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long longValue(JsonObject json, String member) {
        JsonElement element = json == null ? null : json.get(member);
        if (element == null || !element.isJsonPrimitive()) {
            return -1;
        }
        try {
            return element.getAsLong();
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 fehlt", e);
        }
    }

    private static String pathSegment(String value) {
        return encode(value);
    }

    // --- HTTP ---------------------------------------------------------------------------------------------------

    private JsonObject getJson(Session session, String path, String what) throws KnowledgeSourceException {
        return jsonObject(send(session, path, what, config.maxResponseBytes()), what);
    }

    private static JsonObject jsonObject(JiraHttpResponse response, String what) throws KnowledgeSourceException {
        JsonElement element = json(response, what);
        if (!element.isJsonObject()) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, what + ": JSON-Objekt erwartet");
        }
        return element.getAsJsonObject();
    }

    private static JsonElement json(JiraHttpResponse response, String what) throws KnowledgeSourceException {
        if (!response.contentType().toLowerCase(Locale.ROOT).contains("json")) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE,
                    what + ": keine JSON-Antwort (Anmeldeseite eines Proxys?)");
        }
        try {
            return JsonParser.parseString(response.bodyAsUtf8());
        } catch (JsonParseException e) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, what + ": JSON nicht lesbar");
        }
    }

    private JiraHttpResponse send(Session session, String path, String what, int maxBytes)
            throws KnowledgeSourceException {
        URI uri = uri(path, what);
        for (int attempt = 0; ; attempt++) {
            KnowledgeSourceException failure;
            try {
                JiraHttpResponse response = transport.get(uri, session.headers, maxBytes);
                if (response.isSuccess()) {
                    return response;
                }
                failure = statusFailure(what, response.status());
                if (!retryable(response.status())) {
                    throw failure;
                }
            } catch (JiraResponseTooLargeException e) {
                throw transportFailure(e, what, maxBytes);
            } catch (IOException e) {
                failure = transportFailure(e, what, maxBytes);
            }
            if (attempt >= config.maxRetries()) {
                throw attempt == 0 ? failure : exhausted(failure, attempt);
            }
            long delay = backoffMillis(attempt, config.retryBackoffMillis(), config.maxRetryBackoffMillis());
            // WARNING nur mit Art des Fehlers; die Meldung kann Hosts oder Proxy-Diagnosen enthalten.
            LOG.warning("[Jira] " + what + ": " + failure.kind() + ", Wiederholung " + (attempt + 1) + " von "
                    + config.maxRetries() + " in " + delay + " ms");
            LOG.fine("[Jira] " + failure.getMessage());
            pause(delay, failure);
        }
    }

    private static boolean retryable(int status) {
        return status == 429 || status >= 500;
    }

    private static KnowledgeSourceException exhausted(KnowledgeSourceException failure, int retries) {
        String message = failure.getMessage() + " (nach " + retries + " Wiederholungen)";
        if (failure instanceof RateLimitedException) {
            return new RateLimitedException(message);
        }
        return new KnowledgeSourceException(failure.kind(), message, failure.getCause());
    }

    static long backoffMillis(int attempt, int base, int max) {
        long delay = Math.min((long) max, (long) base << Math.min(attempt, 20));
        return delay / 2 + (long) (Math.random() * (delay - delay / 2 + 1));
    }

    private static void pause(long millis, KnowledgeSourceException failure) throws KnowledgeSourceException {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure;
        }
    }

    private URI uri(String path, String what) throws KnowledgeSourceException {
        try {
            return URI.create(config.baseUrl() + path);
        } catch (IllegalArgumentException e) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, what + ": ungültiger Pfad");
        }
    }

    private static KnowledgeSourceException transportFailure(IOException error, String what, int maxBytes) {
        if (error instanceof JiraResponseTooLargeException) {
            return new ResponseTooLargeException(what + ": Antwort größer als " + maxBytes + " Bytes", maxBytes);
        }
        return new KnowledgeSourceException(Kind.UNAVAILABLE, what + ": Jira nicht erreichbar (" + cause(error) + ")",
                error);
    }

    /** Bildet einen Fehlerstatus auf {@link Kind} ab (siehe Klassenkommentar). */
    static KnowledgeSourceException statusFailure(String what, int status) {
        if (status == 401 || status == 403) {
            return new KnowledgeSourceException(Kind.ACCESS_DENIED, what + ": Zugriff verweigert (HTTP " + status + ")");
        }
        if (status >= 300 && status < 400) {
            return new KnowledgeSourceException(Kind.ACCESS_DENIED,
                    what + ": Weiterleitung (HTTP " + status + "), vermutlich nicht angemeldet");
        }
        if (status == 404) {
            return new KnowledgeSourceException(Kind.NOT_FOUND, what + ": nicht gefunden (HTTP 404)");
        }
        if (status == 429) {
            return new RateLimitedException(what + ": Jira begrenzt die Anfragen (HTTP 429)");
        }
        if (status >= 500) {
            return new KnowledgeSourceException(Kind.UNAVAILABLE, what + ": Jira antwortet mit HTTP " + status);
        }
        return new KnowledgeSourceException(Kind.INVALID_RESPONSE, what + ": unerwartete Antwort HTTP " + status);
    }

    /** Die tiefste Ursache in einer Zeile, z. B. {@code SSLHandshakeException: Received fatal alert: bad_certificate}. */
    static String cause(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null || message.trim().isEmpty() ? ""
                : ": " + message.trim());
    }

    // --- Modell -------------------------------------------------------------------------------------------------

    /**
     * Header eines Vorgangs (Accept und ggf. Authorization). Lebt nur für einen Port-Aufruf im Stack des Aufrufers
     * und wird nie in Feldern gehalten, geloggt oder ausgegeben.
     */
    static final class Session {

        private final Map<String, String> headers;

        Session(String authorization) {
            Map<String, String> map = new LinkedHashMap<String, String>();
            map.put("Accept", "application/json");
            if (authorization != null) {
                map.put("Authorization", authorization);
            }
            this.headers = Collections.unmodifiableMap(map);
        }

        /** Dieselben Header mit anderem {@code Accept} (Dateien statt JSON). */
        Map<String, String> headers(String accept) {
            Map<String, String> map = new LinkedHashMap<String, String>(headers);
            map.put("Accept", accept);
            return Collections.unmodifiableMap(map);
        }

        @Override
        public String toString() {
            return "Session[***]";
        }
    }

    static final class Project {
        final String key;
        final String name;
        final String id;

        Project(String key, String name, String id) {
            this.key = key;
            this.name = name == null || name.trim().isEmpty() ? key : name.trim();
            this.id = id;
        }
    }

    static final class SearchResult {
        final List<Issue> issues;
        /** {@code startAt} des nächsten Stapels oder -1, wenn es keinen gibt. */
        final int nextStart;
        /** Gesamtzahl laut Jira oder -1. */
        final int total;

        SearchResult(List<Issue> issues, int nextStart, int total) {
            this.issues = issues;
            this.nextStart = nextStart;
            this.total = total;
        }
    }

    static final class Link {
        final String key;
        /** Beziehung aus Sicht dieses Vorgangs, z. B. „blocks“, „Teilvorgang“. */
        final String relation;
        final String summary;

        Link(String key, String relation, String summary) {
            this.key = key;
            this.relation = relation == null ? "" : relation;
            this.summary = summary == null ? "" : summary;
        }
    }

    static final class Attachment {
        final String id;
        final String fileName;
        final String mediaType;
        final long size;
        /** Absolute Download-URL ({@code content}) oder {@code null}. */
        final String content;
        final String thumbnail;
        final String author;
        final Instant created;

        Attachment(String id, String fileName, String mediaType, long size, String content, String thumbnail,
                   String author, Instant created) {
            this.id = id;
            this.fileName = fileName;
            this.mediaType = mediaType;
            this.size = size;
            this.content = content;
            this.thumbnail = thumbnail;
            this.author = author;
            this.created = created;
        }
    }

    static final class Comment {
        final String id;
        final String author;
        final Instant created;
        final Instant updated;
        /** Wiki-Markup, wie eingegeben. */
        final String body;
        /** Gerendertes HTML oder {@code null}, wenn Jira keines lieferte. */
        final String html;

        Comment(String id, String author, Instant created, Instant updated, String body, String html) {
            this.id = id;
            this.author = author;
            this.created = created;
            this.updated = updated;
            this.body = body;
            this.html = html;
        }
    }

    static final class Issue {
        final String key;
        final String id;
        /** Die unveränderte JSON-Antwort von {@code /issue} oder {@code null} bei Listenzeilen. */
        final String raw;
        String summary;
        String description;
        String renderedDescription;
        String environment;
        String renderedEnvironment;
        String issueType;
        String status;
        String priority;
        String resolution;
        String assignee;
        String reporter;
        Instant created;
        Instant updated;
        Instant resolved;
        String projectKey;
        String projectName;
        String parentKey;
        List<String> labels = new ArrayList<String>();
        List<String> components = new ArrayList<String>();
        List<String> fixVersions = new ArrayList<String>();
        final List<Link> links = new ArrayList<Link>();
        final List<Attachment> attachments = new ArrayList<Attachment>();
        final List<Comment> comments = new ArrayList<Comment>();
        int commentTotal;

        Issue(String key, String id, String raw) {
            this.key = key;
            this.id = id;
            this.raw = raw;
        }

        /** Titel wie in Jira: „KEY-123: Zusammenfassung“. */
        String title() {
            return summary == null || summary.trim().isEmpty() ? key : key + ": " + summary.trim();
        }
    }
}
