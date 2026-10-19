package com.aresstack.enterpriseai.proasteion.emporion.holkas.mediawiki;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fake der {@code api.php}: eine kleine In-Memory-Wiki, die die vom Adapter genutzten Action-API-Aufrufe
 * im Format {@code formatversion=2} beantwortet. Kein Netz.
 */
public final class FakeMediaWikiTransport implements MediaWikiTransport {

    final Map<String, Page> pages = new LinkedHashMap<String, Page>();
    final Map<String, String> redirects = new LinkedHashMap<String, String>();
    final List<Map<String, String>> requests = new ArrayList<Map<String, String>>();
    final List<String> failLinksFor = new ArrayList<String>();
    boolean readOnly;
    final List<String> unavailableLinksFor = new ArrayList<String>();
    int linkPageSize = 500;
    int sessionResets;

    /** Ohne Login-Pflicht {@code null}; sonst Benutzername und Passwort, die {@code action=login} akzeptiert. */
    public String requiredUser;
    public String requiredPassword;
    /** Ob die aktuelle Sitzung angemeldet ist; ein HTTP-Wrapper setzt das je Anfrage anhand seines Cookies. */
    public boolean sessionValid;
    /** Zahl der Login-Versuche. */
    public int logins;
    /** Läuft einmal, bevor eine Leseanfrage wegen fehlender Sitzung abgelehnt wird. */
    Runnable beforeFirstDenial;

    /** Legt eine Seite an; {@code links} sind Titel verlinkter Seiten (für {@code prop=links}). */
    public Page page(String title, String html, long pageId, long revId, String... links) {
        Page page = new Page(title, html, pageId, revId, links);
        pages.put(title, page);
        return page;
    }

    @Override
    public Response get(String query) throws IOException {
        return handle(params(query));
    }

    @Override
    public Response postForm(String formBody) throws IOException {
        return handle(params(formBody));
    }

    @Override
    public void resetSession() {
        sessionResets++;
        sessionValid = false;
    }

    private Response handle(Map<String, String> p) throws IOException {
        requests.add(p);
        String action = p.get("action");
        if (readOnly && ("login".equals(action) || "tokens".equals(p.get("meta")))) {
            return ok("{\"error\":{\"code\":\"readonly\",\"info\":\"The wiki is in read-only mode.\"}}");
        }
        if ("login".equals(action)) {
            return login(p);
        }
        if ("query".equals(action) && "tokens".equals(p.get("meta"))) {
            return ok("{\"batchcomplete\":true,\"query\":{\"tokens\":{\"logintoken\":\"tok+\\\\\"}}}");
        }
        if ("clientlogin".equals(action)) {
            return ok("{\"clientlogin\":{\"status\":\"FAIL\",\"message\":\"nope\"}}");
        }
        if (requiredUser != null && !sessionValid) {
            Runnable hook = beforeFirstDenial;
            beforeFirstDenial = null;
            if (hook != null) {
                hook.run();
            }
            return ok("{\"error\":{\"code\":\"readapidenied\",\"info\":\"You need read permission.\"}}");
        }
        if ("parse".equals(action)) {
            Page page = resolve(p.get("page"));
            if (page == null) {
                return ok("{\"error\":{\"code\":\"missingtitle\",\"info\":\"The page you specified doesn't exist.\"}}");
            }
            return ok("{\"parse\":{\"title\":" + q(page.title) + ",\"pageid\":" + page.pageId + ",\"revid\":"
                    + page.revId + ",\"text\":" + q(page.html) + "}}");
        }
        if ("query".equals(action) && "info".equals(p.get("prop"))) {
            return ok(info(p.get("titles").split("\\|")));
        }
        if ("query".equals(action) && "links".equals(p.get("prop"))) {
            return ok(links(p.get("titles"), p.get("plcontinue")));
        }
        if ("query".equals(action) && "search".equals(p.get("list"))) {
            StringBuilder sb = new StringBuilder("{\"query\":{\"search\":[");
            int limit = Integer.parseInt(p.get("srlimit"));
            int n = 0;
            for (Page page : pages.values()) {
                if (n < limit && page.html.toLowerCase().contains(p.get("srsearch").toLowerCase())) {
                    if (n++ > 0) {
                        sb.append(',');
                    }
                    sb.append("{\"ns\":0,\"title\":").append(q(page.title)).append(",\"pageid\":")
                            .append(page.pageId).append(",\"snippet\":")
                            .append(q("… <span class=\"searchmatch\">" + p.get("srsearch") + "</span> …"))
                            .append(",\"timestamp\":\"2026-01-02T03:04:05Z\"}");
                }
            }
            return ok(sb.append("]}}").toString());
        }
        return ok("{\"error\":{\"code\":\"badvalue\",\"info\":\"unsupported\"}}");
    }

    private Response login(Map<String, String> p) {
        logins++;
        if (!"tok+\\".equals(p.get("lgtoken"))) {
            return ok("{\"login\":{\"result\":\"WrongToken\"}}");
        }
        if (p.get("lgname").equals(requiredUser) && p.get("lgpassword").equals(requiredPassword)) {
            sessionValid = true;
            return ok("{\"login\":{\"result\":\"Success\",\"lgusername\":" + q(requiredUser) + "}}");
        }
        return ok("{\"login\":{\"result\":\"Failed\",\"reason\":\"Incorrect username or password entered.\"}}");
    }

    private String info(String[] titles) {
        StringBuilder normalized = new StringBuilder();
        StringBuilder redirected = new StringBuilder();
        StringBuilder out = new StringBuilder();
        List<String> seen = new ArrayList<String>();
        int missingId = -1;
        for (String requested : titles) {
            String norm = normalize(requested);
            if (!norm.equals(requested)) {
                append(normalized, "{\"from\":" + q(requested) + ",\"to\":" + q(norm) + "}");
            }
            String target = norm;
            if (redirects.containsKey(norm)) {
                target = redirects.get(norm);
                append(redirected, "{\"from\":" + q(norm) + ",\"to\":" + q(target) + "}");
            }
            if (seen.contains(target)) {
                continue;
            }
            seen.add(target);
            Page page = pages.get(target);
            if (page == null) {
                append(out, "{\"ns\":0,\"title\":" + q(target) + ",\"missing\":true,\"pageid\":" + missingId-- + "}");
            } else {
                append(out, "{\"pageid\":" + page.pageId + ",\"ns\":0,\"title\":" + q(page.title)
                        + ",\"contentmodel\":\"wikitext\",\"touched\":\"2026-03-04T05:06:07Z\",\"lastrevid\":"
                        + page.revId + ",\"fullurl\":" + q("https://wiki.example/wiki/" + page.title.replace(' ', '_'))
                        + "}");
            }
        }
        return "{\"batchcomplete\":true,\"query\":{\"normalized\":[" + normalized + "],\"redirects\":["
                + redirected + "],\"pages\":[" + out + "]}}";
    }

    private String links(String title, String cont) {
        Page page = resolve(title);
        if (failLinksFor.contains(title)) {
            return "{\"error\":{\"code\":\"badcontinue\",\"info\":\"broken\"}}";
        }
        if (unavailableLinksFor.contains(title)) {
            return "{\"error\":{\"code\":\"internal_api_error_DBQueryError\",\"info\":\"db\"}}";
        }
        if (page == null) {
            return "{\"query\":{\"pages\":[{\"ns\":0,\"title\":" + q(title) + ",\"missing\":true}]}}";
        }
        int start = cont == null ? 0 : Integer.parseInt(cont);
        int end = Math.min(page.links.length, start + linkPageSize);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            append(sb, "{\"ns\":0,\"title\":" + q(page.links[i]) + "}");
        }
        String body = "\"query\":{\"pages\":[{\"pageid\":" + page.pageId + ",\"ns\":0,\"title\":" + q(page.title)
                + ",\"links\":[" + sb + "]}]}";
        if (end < page.links.length) {
            return "{\"continue\":{\"plcontinue\":\"" + end + "\",\"continue\":\"||\"}," + body + "}";
        }
        return "{\"batchcomplete\":true," + body + "}";
    }

    private Page resolve(String title) {
        String norm = normalize(title);
        if (redirects.containsKey(norm)) {
            norm = redirects.get(norm);
        }
        return pages.get(norm);
    }

    static String normalize(String title) {
        String t = title.replace('_', ' ').trim();
        return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    private static void append(StringBuilder sb, String value) {
        if (sb.length() > 0) {
            sb.append(',');
        }
        sb.append(value);
    }

    private static Response ok(String body) {
        return new Response(200, body);
    }

    static String q(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    static Map<String, String> params(String query) throws UnsupportedEncodingException {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                result.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            }
        }
        return result;
    }

    static final class Page {

        final String title;
        final String html;
        final long pageId;
        long revId;
        final String[] links;

        Page(String title, String html, long pageId, long revId, String[] links) {
            this.title = title;
            this.html = html;
            this.pageId = pageId;
            this.revId = revId;
            this.links = links;
        }
    }
}
