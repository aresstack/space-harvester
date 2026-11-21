package com.aresstack.enterpriseai.proasteion.emporion.holkas.jira;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-Memory-Jira hinter {@link JiraHttpTransport}: beantwortet die REST-Endpunkte, die der Adapter benutzt, mit JSON
 * in der Form von Jira Data Center (REST-API 2) und protokolliert alle Requests. Die JQL wird nur so weit ausgewertet,
 * wie der Adapter sie bildet: {@code project = "KEY"}, {@code project in (…)}, {@code project not in (…)},
 * {@code text ~ "…"}, {@code summary ~ "…"}, {@code key in (…)}.
 */
final class FakeJira implements JiraHttpTransport {

    static final URI BASE = URI.create("https://jira.example.org/jira");

    private static final Pattern PROJECT_EQ = Pattern.compile("project = \"([^\"]+)\"");
    private static final Pattern PROJECT_IN = Pattern.compile("project in \\(([^)]*)\\)");
    private static final Pattern PROJECT_NOT_IN = Pattern.compile("project not in \\(([^)]*)\\)");
    private static final Pattern TEXT = Pattern.compile("(text|summary) ~ \"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern KEY_IN = Pattern.compile("key in \\(([^)]*)\\)");
    private static final Pattern KEY_EQ = Pattern.compile("\\bkey = ([A-Z][A-Z0-9_]*-[0-9]+)");
    private static final Pattern RESOLUTION = Pattern.compile("resolution = Unresolved");

    final Map<String, FakeProject> projects = new LinkedHashMap<String, FakeProject>();
    final Map<String, FakeIssue> issues = new LinkedHashMap<String, FakeIssue>();
    final Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();
    /** Content-Type je Datei-Pfad; ohne Eintrag {@code image/png}. */
    final Map<String, String> fileTypes = new LinkedHashMap<String, String>();
    final List<URI> requests = new ArrayList<URI>();
    final List<Map<String, String>> requestHeaders = new ArrayList<Map<String, String>>();
    final List<String> jqls = new ArrayList<String>();
    /** Feste JSON-Antworten je Pfad samt Query ({@code RestQuerySource}). */
    final Map<String, String> stubs = new LinkedHashMap<String, String>();
    /** Vorgänge, die das Konto nicht sehen darf: 403, und sie fehlen in Suchen. */
    final Set<String> restricted = new LinkedHashSet<String>();
    /** Erzwingt eine feste Antwort für alle Requests (Fehlerfälle). */
    JiraHttpResponse forcedResponse;
    IOException forcedFailure;
    /** Kommentare je Vorgang, die {@code /issue} höchstens mitliefert; der Rest kommt über {@code /comment}. */
    int inlineCommentLimit = Integer.MAX_VALUE;
    /** Statt {@code renderedFields} liefert {@code /issue} nur Wiki-Markup. */
    boolean withoutRenderedFields;
    /** Schreibende Aufrufe der Jira-Werkzeuge: Pfad (ohne Basis) und JSON-Körper. */
    final List<String[]> posts = new ArrayList<String[]>();
    /** Angebotene Übergänge je Vorgang: ID, Name, Zielstatus. */
    final List<String[]> transitionOffers = new ArrayList<String[]>();
    /** Feste Antwort auf jeden POST (Fehlerfälle); sonst wie Jira. */
    JiraHttpResponse forcedPostResponse;

    FakeProject project(String key, String name) {
        FakeProject project = new FakeProject(key, name);
        projects.put(key, project);
        return project;
    }

    FakeIssue issue(String key, String summary, String description) {
        FakeIssue issue = new FakeIssue(key, summary, description);
        issues.put(key, issue);
        return issue;
    }

    void link(String fromKey, String relation, String toKey) {
        issues.get(fromKey).links.add(new String[] {relation, toKey});
    }

    @Override
    public JiraHttpResponse get(URI uri, Map<String, String> headers, int maxBytes) throws IOException {
        requests.add(uri);
        requestHeaders.add(headers);
        if (forcedFailure != null) {
            throw forcedFailure;
        }
        if (forcedResponse != null) {
            return forcedResponse;
        }
        String path = uri.getRawPath().substring(BASE.getRawPath().length());
        Map<String, String> query = query(uri.getRawQuery());
        String pathAndQuery = path + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        if (stubs.containsKey(pathAndQuery)) {
            return json(200, stubs.get(pathAndQuery));
        }
        if (path.equals("/rest/api/2/project")) {
            JsonArray array = new JsonArray();
            for (FakeProject project : projects.values()) {
                array.add(project.json());
            }
            return json(200, array.toString());
        }
        if (path.startsWith("/rest/api/2/project/")) {
            FakeProject project = projects.get(path.substring("/rest/api/2/project/".length()));
            return project == null ? json(404, "{\"errorMessages\":[\"No project\"]}") : json(200, project.json().toString());
        }
        if (path.equals("/rest/api/2/search")) {
            return search(query);
        }
        Matcher transitions = Pattern.compile("/rest/api/2/issue/([^/]+)/transitions").matcher(path);
        if (transitions.matches()) {
            if (!issues.containsKey(transitions.group(1))) {
                return json(404, "{\"errorMessages\":[\"Issue Does Not Exist\"]}");
            }
            JsonArray array = new JsonArray();
            for (String[] offer : transitionOffers) {
                JsonObject transition = new JsonObject();
                transition.addProperty("id", offer[0]);
                transition.addProperty("name", offer[1]);
                JsonObject to = new JsonObject();
                to.addProperty("name", offer[2]);
                transition.add("to", to);
                array.add(transition);
            }
            JsonObject page = new JsonObject();
            page.add("transitions", array);
            return json(200, page.toString());
        }
        Matcher comments = Pattern.compile("/rest/api/2/issue/([^/]+)/comment").matcher(path);
        if (comments.matches()) {
            FakeIssue issue = issues.get(comments.group(1));
            if (issue == null || restricted.contains(issue.key)) {
                return json(issue == null ? 404 : 403, "{}");
            }
            int startAt = Integer.parseInt(query.getOrDefault("startAt", "0"));
            int max = Integer.parseInt(query.getOrDefault("maxResults", "50"));
            JsonArray array = new JsonArray();
            for (int i = startAt; i < Math.min(issue.comments.size(), startAt + max); i++) {
                JsonObject comment = issue.comments.get(i).json(i);
                comment.addProperty("renderedBody", issue.comments.get(i).html);
                array.add(comment);
            }
            JsonObject page = new JsonObject();
            page.addProperty("startAt", startAt);
            page.addProperty("maxResults", max);
            page.addProperty("total", issue.comments.size());
            page.add("comments", array);
            return json(200, page.toString());
        }
        if (path.startsWith("/rest/api/2/issue/")) {
            FakeIssue issue = issues.get(path.substring("/rest/api/2/issue/".length()));
            if (issue == null) {
                return json(404, "{\"errorMessages\":[\"Issue Does Not Exist\"],\"errors\":{}}");
            }
            if (restricted.contains(issue.key)) {
                return json(403, "{\"errorMessages\":[\"You do not have the permission to see the specified issue.\"]}");
            }
            return json(200, issue.json(true, inlineCommentLimit, !withoutRenderedFields).toString());
        }
        if (files.containsKey(path)) {
            String type = fileTypes.containsKey(path) ? fileTypes.get(path) : "image/png";
            return new JiraHttpResponse(200, type, files.get(path));
        }
        return json(404, "{}");
    }

    @Override
    public JiraHttpResponse post(URI uri, Map<String, String> headers, byte[] body, int maxBytes) throws IOException {
        requests.add(uri);
        requestHeaders.add(headers);
        String path = uri.getRawPath().substring(BASE.getRawPath().length());
        posts.add(new String[] {path, new String(body, StandardCharsets.UTF_8)});
        if (forcedFailure != null) {
            throw forcedFailure;
        }
        if (forcedPostResponse != null) {
            return forcedPostResponse;
        }
        Matcher comment = Pattern.compile("/rest/api/2/issue/([^/]+)/comment").matcher(path);
        if (comment.matches()) {
            FakeIssue issue = issues.get(comment.group(1));
            if (issue == null) {
                return json(404, "{\"errorMessages\":[\"Issue Does Not Exist\"]}");
            }
            String text = com.google.gson.JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
                    .getAsJsonObject().get("body").getAsString();
            issue.comment("Agent", text, "<p>" + text + "</p>");
            return json(201, "{\"id\":\"" + (10000 + issue.comments.size()) + "\"}");
        }
        Matcher transition = Pattern.compile("/rest/api/2/issue/([^/]+)/transitions").matcher(path);
        if (transition.matches()) {
            FakeIssue issue = issues.get(transition.group(1));
            if (issue == null) {
                return json(404, "{}");
            }
            String id = com.google.gson.JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("transition").get("id").getAsString();
            for (String[] offer : transitionOffers) {
                if (offer[0].equals(id)) {
                    issue.status = offer[2];
                    return new JiraHttpResponse(204, "application/json", new byte[0]);
                }
            }
            return json(400, "{\"errorMessages\":[\"It seems that you have tried to perform a workflow operation "
                    + "that is not valid\"],\"errors\":{}}");
        }
        return json(404, "{}");
    }

    private JiraHttpResponse search(Map<String, String> query) {
        String jql = query.getOrDefault("jql", "");
        jqls.add(jql);
        int startAt = Integer.parseInt(query.getOrDefault("startAt", "0"));
        int max = Integer.parseInt(query.getOrDefault("maxResults", "50"));
        List<FakeIssue> matching = new ArrayList<FakeIssue>();
        for (FakeIssue issue : issues.values()) {
            if (!restricted.contains(issue.key) && matches(issue, jql)) {
                matching.add(issue);
            }
        }
        if (jql.contains("ORDER BY updated DESC")) {
            Collections.reverse(matching);
        }
        JsonArray array = new JsonArray();
        for (int i = startAt; i < Math.min(matching.size(), startAt + max); i++) {
            array.add(matching.get(i).json(false, 0, false));
        }
        JsonObject page = new JsonObject();
        page.addProperty("startAt", startAt);
        page.addProperty("maxResults", max);
        page.addProperty("total", matching.size());
        page.add("issues", array);
        return json(200, page.toString());
    }

    private static boolean matches(FakeIssue issue, String jql) {
        Matcher eq = PROJECT_EQ.matcher(jql);
        if (eq.find() && !issue.project.equals(eq.group(1))) {
            return false;
        }
        Matcher in = PROJECT_IN.matcher(jql);
        if (in.find() && !list(in.group(1)).contains(issue.project)) {
            return false;
        }
        Matcher notIn = PROJECT_NOT_IN.matcher(jql);
        if (notIn.find() && list(notIn.group(1)).contains(issue.project)) {
            return false;
        }
        Matcher keys = KEY_IN.matcher(jql);
        if (keys.find() && !list(keys.group(1)).contains(issue.key)) {
            return false;
        }
        Matcher key = KEY_EQ.matcher(jql);
        if (key.find() && !key.group(1).equals(issue.key)) {
            return false;
        }
        if (RESOLUTION.matcher(jql).find() && issue.resolution != null) {
            return false;
        }
        Matcher text = TEXT.matcher(jql);
        if (text.find()) {
            String needle = text.group(2).replace("\\\"", "\"").replace("\\\\", "\\").toLowerCase();
            String haystack = ("summary".equals(text.group(1)) ? issue.summary
                    : issue.summary + " " + issue.description + " " + issue.commentText()).toLowerCase();
            if (!haystack.contains(needle)) {
                return false;
            }
        }
        return true;
    }

    private static List<String> list(String group) {
        List<String> values = new ArrayList<String>();
        for (String item : group.split(",")) {
            values.add(item.trim().replace("\"", ""));
        }
        return values;
    }

    private static Map<String, String> query(String rawQuery) {
        Map<String, String> query = new LinkedHashMap<String, String>();
        if (rawQuery == null) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            try {
                query.put(URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), "UTF-8"),
                        eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            } catch (java.io.UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }
        return query;
    }

    static JiraHttpResponse json(int status, String body) {
        return new JiraHttpResponse(status, "application/json;charset=UTF-8", body.getBytes(StandardCharsets.UTF_8));
    }

    static final class FakeProject {
        final String key;
        final String name;

        FakeProject(String key, String name) {
            this.key = key;
            this.name = name;
        }

        JsonObject json() {
            JsonObject project = new JsonObject();
            project.addProperty("id", String.valueOf(10000 + key.hashCode() % 1000));
            project.addProperty("key", key);
            project.addProperty("name", name);
            project.addProperty("self", BASE + "/rest/api/2/project/" + key);
            return project;
        }
    }

    static final class FakeComment {
        final String author;
        final String body;
        final String html;
        final String created;

        FakeComment(String author, String body, String html, String created) {
            this.author = author;
            this.body = body;
            this.html = html;
            this.created = created;
        }

        JsonObject json(int index) {
            JsonObject comment = new JsonObject();
            comment.addProperty("id", String.valueOf(50000 + index));
            comment.add("author", user(author));
            comment.addProperty("body", body);
            comment.addProperty("created", created);
            comment.addProperty("updated", created);
            return comment;
        }
    }

    static JsonObject user(String name) {
        JsonObject user = new JsonObject();
        user.addProperty("name", name.toLowerCase());
        user.addProperty("displayName", name);
        return user;
    }

    static JsonObject named(String name) {
        JsonObject object = new JsonObject();
        object.addProperty("id", String.valueOf(Math.abs(name.hashCode())));
        object.addProperty("name", name);
        return object;
    }

    static final class FakeIssue {
        final String key;
        final String project;
        String summary;
        String description;
        String descriptionHtml;
        String type = "Bug";
        String status = "Open";
        String priority = "Medium";
        String resolution;
        String assignee = "Bob";
        String reporter = "Alice";
        String created = "2026-01-05T10:15:30.000+0100";
        String updated = "2026-02-01T08:00:00.000+0100";
        String parent;
        final List<String> labels = new ArrayList<String>();
        final List<String> components = new ArrayList<String>();
        final List<String> subtasks = new ArrayList<String>();
        final List<String[]> links = new ArrayList<String[]>();
        final List<FakeComment> comments = new ArrayList<FakeComment>();
        final List<JsonObject> attachments = new ArrayList<JsonObject>();

        FakeIssue(String key, String summary, String description) {
            this.key = key;
            this.project = key.substring(0, key.indexOf('-'));
            this.summary = summary;
            this.description = description;
            this.descriptionHtml = description == null ? null : "<p>" + description + "</p>";
        }

        FakeIssue comment(String author, String body, String html) {
            comments.add(new FakeComment(author, body, html, "2026-01-10T12:00:00.000+0100"));
            return this;
        }

        FakeIssue attachment(String id, String fileName, String mediaType, long size) {
            JsonObject attachment = new JsonObject();
            attachment.addProperty("id", id);
            attachment.addProperty("filename", fileName);
            attachment.addProperty("mimeType", mediaType);
            attachment.addProperty("size", size);
            attachment.addProperty("content", BASE + "/secure/attachment/" + id + "/" + fileName);
            attachment.add("author", user("Alice"));
            attachment.addProperty("created", created);
            attachments.add(attachment);
            return this;
        }

        String commentText() {
            StringBuilder sb = new StringBuilder();
            for (FakeComment comment : comments) {
                sb.append(comment.body).append(' ');
            }
            return sb.toString();
        }

        JsonObject json(boolean full, int inlineCommentLimit, boolean rendered) {
            JsonObject issue = new JsonObject();
            issue.addProperty("id", String.valueOf(Math.abs(key.hashCode())));
            issue.addProperty("key", key);
            issue.addProperty("self", BASE + "/rest/api/2/issue/" + key);
            JsonObject fields = new JsonObject();
            fields.addProperty("summary", summary);
            fields.add("issuetype", named(type));
            fields.add("status", named(status));
            fields.add("priority", named(priority));
            if (resolution != null) {
                fields.add("resolution", named(resolution));
                fields.addProperty("resolutiondate", updated);
            } else {
                fields.add("resolution", null);
            }
            fields.add("assignee", assignee == null ? null : user(assignee));
            fields.add("reporter", user(reporter));
            fields.addProperty("created", created);
            fields.addProperty("updated", updated);
            JsonObject project = new JsonObject();
            project.addProperty("key", this.project);
            project.addProperty("name", "Projekt " + this.project);
            fields.add("project", project);
            JsonArray labelArray = new JsonArray();
            for (String label : labels) {
                labelArray.add(label);
            }
            fields.add("labels", labelArray);
            JsonArray componentArray = new JsonArray();
            for (String component : components) {
                componentArray.add(named(component));
            }
            fields.add("components", componentArray);
            fields.add("fixVersions", new JsonArray());
            if (parent != null) {
                JsonObject parentIssue = new JsonObject();
                parentIssue.addProperty("key", parent);
                JsonObject parentFields = new JsonObject();
                parentFields.addProperty("summary", "Eltern von " + key);
                parentIssue.add("fields", parentFields);
                fields.add("parent", parentIssue);
            }
            fields.addProperty("description", description);
            if (full) {
                JsonArray subtaskArray = new JsonArray();
                for (String subtask : subtasks) {
                    JsonObject sub = new JsonObject();
                    sub.addProperty("key", subtask);
                    JsonObject subFields = new JsonObject();
                    subFields.addProperty("summary", "Teil " + subtask);
                    sub.add("fields", subFields);
                    subtaskArray.add(sub);
                }
                fields.add("subtasks", subtaskArray);
                JsonArray linkArray = new JsonArray();
                for (String[] link : links) {
                    JsonObject issueLink = new JsonObject();
                    JsonObject type = new JsonObject();
                    type.addProperty("name", "Relates");
                    type.addProperty("outward", link[0]);
                    type.addProperty("inward", "is " + link[0] + " by");
                    issueLink.add("type", type);
                    JsonObject target = new JsonObject();
                    target.addProperty("key", link[1]);
                    JsonObject targetFields = new JsonObject();
                    targetFields.addProperty("summary", "Ziel " + link[1]);
                    target.add("fields", targetFields);
                    issueLink.add("outwardIssue", target);
                    linkArray.add(issueLink);
                }
                fields.add("issuelinks", linkArray);
                JsonArray attachmentArray = new JsonArray();
                for (JsonObject attachment : attachments) {
                    attachmentArray.add(attachment);
                }
                fields.add("attachment", attachmentArray);
                JsonObject comment = new JsonObject();
                JsonArray commentArray = new JsonArray();
                JsonArray renderedComments = new JsonArray();
                for (int i = 0; i < Math.min(comments.size(), inlineCommentLimit); i++) {
                    commentArray.add(comments.get(i).json(i));
                    JsonObject renderedComment = new JsonObject();
                    renderedComment.addProperty("body", comments.get(i).html);
                    renderedComments.add(renderedComment);
                }
                comment.add("comments", commentArray);
                comment.addProperty("total", comments.size());
                comment.addProperty("maxResults", Math.min(comments.size(), inlineCommentLimit));
                fields.add("comment", comment);
                if (rendered) {
                    JsonObject renderedFields = new JsonObject();
                    renderedFields.addProperty("description", descriptionHtml);
                    JsonObject renderedCommentField = new JsonObject();
                    renderedCommentField.add("comments", renderedComments);
                    renderedFields.add("comment", renderedCommentField);
                    issue.add("renderedFields", renderedFields);
                }
            }
            issue.add("fields", fields);
            return issue;
        }
    }
}
