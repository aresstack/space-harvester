package com.aresstack.enterpriseai.proasteion.emporion.holkas.betaview;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lesen der BetaView-Seiten (aus MainframeMate {@code HiddenInputExtractor}, {@code AppContextCsrfTokenParser},
 * {@code HtmlResultsTableModel} und {@code DocumentTabParser}).
 */
final class BetaViewHtml {

    private static final Pattern CSRF = Pattern.compile(
            "\"csrfToken\"\\s*:\\s*\\{\\s*\"field\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,"
                    + "\\s*\"value\"\\s*:\\s*\"([^\"]+)\"\\s*\\}", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_IN_SCRIPT = Pattern.compile("['\"]([^'\"]+\\.action[^'\"]*)['\"]");
    private static final Pattern DOC_ID = Pattern.compile("docid=([A-Za-z0-9]+)");
    private static final Pattern FAV_ID = Pattern.compile("favid=([A-Za-z0-9]+)");

    private BetaViewHtml() {
    }

    /** Name und Wert aller versteckten Eingabefelder, in Reihenfolge. */
    static Map<String, String> hiddenInputs(String html) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (html == null) {
            return result;
        }
        for (Element input : Jsoup.parse(html).select("input[type=hidden][name]")) {
            result.put(input.attr("name"), input.attr("value"));
        }
        return result;
    }

    /** Wert des CSRF-Tokens aus {@code getAppContext.action} oder {@code null}. */
    static String csrfToken(String appContextJson) {
        if (appContextJson == null) {
            return null;
        }
        Matcher m = CSRF.matcher(appContextJson);
        return m.find() ? m.group(3) : null;
    }

    /** Die Antwort ist (wieder) das Anmeldeformular. */
    static boolean isLoginPage(String html) {
        if (html == null) {
            return false;
        }
        Document doc = Jsoup.parse(html);
        return !doc.select("input[name=userName]").isEmpty() && !doc.select("input[type=password]").isEmpty();
    }

    /** Eine Zeile der Ergebnisliste: Zellen (Spaltenname → Text) und die Aktion, die das Dokument öffnet. */
    static final class ResultRow {

        private final Map<String, String> cells;
        private final String openAction;

        ResultRow(Map<String, String> cells, String openAction) {
            this.cells = Collections.unmodifiableMap(new LinkedHashMap<String, String>(cells));
            this.openAction = openAction;
        }

        Map<String, String> cells() {
            return cells;
        }

        String openAction() {
            return openAction;
        }
    }

    /** Zeilen der ersten Tabelle in {@code showResult.action}; Zeilen ohne Aktion fehlen. */
    static List<ResultRow> resultRows(String html) {
        List<ResultRow> rows = new ArrayList<ResultRow>();
        if (html == null || html.trim().isEmpty()) {
            return rows;
        }
        Element table = Jsoup.parse(html).selectFirst("table");
        if (table == null) {
            return rows;
        }
        List<String> columns = new ArrayList<String>();
        for (Element th : table.select("thead th")) {
            columns.add(th.text().trim());
        }
        for (Element tr : table.select("tbody tr")) {
            Elements cells = tr.select("td");
            if (cells.isEmpty()) {
                continue;
            }
            Map<String, String> values = new LinkedHashMap<String, String>();
            for (int i = 0; i < cells.size(); i++) {
                String column = i < columns.size() && !columns.get(i).isEmpty() ? columns.get(i) : "#" + (i + 1);
                values.put(column, cells.get(i).text().trim());
            }
            String action = openAction(tr.selectFirst("a"));
            if (action != null) {
                rows.add(new ResultRow(values, action));
            }
        }
        return rows;
    }

    private static String openAction(Element link) {
        if (link == null) {
            return null;
        }
        String onclick = link.attr("onclick");
        if (!onclick.isEmpty()) {
            Matcher m = ACTION_IN_SCRIPT.matcher(onclick);
            if (m.find()) {
                return relative(Parser.unescapeEntities(m.group(1), false).trim());
            }
        }
        String href = link.attr("href").trim();
        if (href.startsWith("javascript:")) {
            Matcher m = ACTION_IN_SCRIPT.matcher(href);
            return m.find() ? relative(Parser.unescapeEntities(m.group(1), false).trim()) : null;
        }
        return href.isEmpty() || "#".equals(href) ? null : relative(Parser.unescapeEntities(href, false));
    }

    /** Aktionen relativ zur Basis-URL ({@code /betaview/open.action} → {@code open.action}). */
    private static String relative(String action) {
        int query = action.indexOf('?');
        String path = query < 0 ? action : action.substring(0, query);
        int slash = path.lastIndexOf('/');
        return slash < 0 ? action : action.substring(slash + 1);
    }

    /** Das aktive Dokument der Dokumentreiter nach dem Öffnen: Dokument-ID, Favoriten-ID, Titel. */
    static final class OpenedDocument {

        private final String docId;
        private final String favId;
        private final String title;

        OpenedDocument(String docId, String favId, String title) {
            this.docId = docId;
            this.favId = favId;
            this.title = title;
        }

        String docId() {
            return docId;
        }

        String favId() {
            return favId;
        }

        String title() {
            return title;
        }
    }

    /** Aktiver Reiter in {@code #we_id_docbrowser_tabs}, sonst der erste mit Dokument-ID; {@code null} ohne Reiter. */
    static OpenedDocument activeDocument(String html) {
        if (html == null || html.isEmpty()) {
            return null;
        }
        OpenedDocument first = null;
        for (Element li : Jsoup.parse(html).select("#we_id_docbrowser_tabs li")) {
            Element content = li.selectFirst(".el_tab_content");
            Element link = content == null ? null : content.selectFirst("a");
            if (link == null) {
                continue;
            }
            String href = Parser.unescapeEntities(link.attr("href"), false);
            String docId = find(DOC_ID, href);
            if (docId == null) {
                continue;
            }
            Elements parts = content.select(".el_tab_element");
            String title = parts.size() > 1 ? parts.get(1).text().trim() : link.text().trim();
            OpenedDocument document = new OpenedDocument(docId, find(FAV_ID, href), title);
            if (li.hasClass("active")) {
                return document;
            }
            if (first == null) {
                first = document;
            }
        }
        return first;
    }

    private static String find(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
