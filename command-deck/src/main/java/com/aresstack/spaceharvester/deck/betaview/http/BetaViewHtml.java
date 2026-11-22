package com.aresstack.spaceharvester.deck.betaview.http;

import com.aresstack.spaceharvester.deck.betaview.Bookmark;
import com.aresstack.spaceharvester.deck.betaview.DocumentTab;
import com.aresstack.spaceharvester.deck.betaview.InfoLine;
import com.aresstack.spaceharvester.deck.betaview.NameAndDescription;
import com.aresstack.spaceharvester.deck.betaview.OpenedDocument;
import com.aresstack.spaceharvester.deck.betaview.ResultTable;
import com.aresstack.spaceharvester.deck.betaview.SavedSearch;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

/**
 * Liest die Seiten des BetaView-Servers. Jede Regel stammt aus MainframeMate ({@code HiddenInputExtractor},
 * {@code AppContextCsrfTokenParser}, {@code HtmlResultsTableModel}, {@code DocumentTabParser},
 * {@code DocumentPreviewPanel}, {@code SidebarPanel}, {@code BetaViewDocumentTab}, {@code BetaViewDocumentTabManager}),
 * dort gegen den echten Server erprobt; hier nur ohne Swing zusammengefasst.
 */
final class BetaViewHtml {

    private static final Pattern HIDDEN_INPUT = Pattern.compile(
            "<input\\s+[^>]*type\\s*=\\s*['\"]hidden['\"][^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME = Pattern.compile("name\\s*=\\s*['\"]([^'\"]+)['\"]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VALUE = Pattern.compile("value\\s*=\\s*['\"]([^'\"]*)['\"]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CSRF = Pattern.compile(
            "\"csrfToken\"\\s*:\\s*\\{\\s*\"field\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,"
                    + "\\s*\"value\"\\s*:\\s*\"([^\"]+)\"\\s*\\}", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_IN_SCRIPT = Pattern.compile("['\"]([^'\"]+\\.action[^'\"]*)['\"]");
    private static final Pattern LINK_ID = Pattern.compile("closeSingleDocument\\.action\\?linkID=([A-F0-9]+)");
    private static final Pattern DOC_ID = Pattern.compile("docid=([A-F0-9]+)");
    private static final Pattern FAV_ID = Pattern.compile("favid=([A-Za-z0-9]+)");
    private static final Pattern EXECUTE_BY_ID = Pattern.compile("executeByID\\((\\d+)\\)");
    private static final Pattern EDIT_BY_ID = Pattern.compile("editByID\\((\\d+)\\)");

    private BetaViewHtml() {
    }

    /** Alle versteckten Felder ({@code <input type="hidden">}) mit Name und Wert, in Reihenfolge. */
    static Map<String, String> hiddenInputs(String html) {
        Map<String, String> result = new LinkedHashMap<>();
        if (html == null) {
            return result;
        }
        Matcher inputs = HIDDEN_INPUT.matcher(html);
        while (inputs.find()) {
            String tag = inputs.group();
            String name = first(NAME, tag);
            if (name == null) {
                continue;
            }
            String value = first(VALUE, tag);
            result.put(name, value == null ? "" : value);
        }
        return result;
    }

    /** Das CSRF-Token aus dem JSON von {@code getAppContext.action}. */
    static Optional<String> csrfToken(String appContextJson) {
        if (appContextJson == null) {
            return Optional.empty();
        }
        Matcher matcher = CSRF.matcher(appContextJson);
        return matcher.find() ? Optional.of(matcher.group(3)) : Optional.empty();
    }

    /** Ob die Seite die Anmeldemaske ist (Formular an {@code login.action} mit Kennwortfeld). */
    static boolean looksLikeLoginForm(String html) {
        if (html == null || html.isBlank()) {
            return false;
        }
        Document doc = Jsoup.parse(html);
        return doc.selectFirst("form[action*=login.action] input[name=password]") != null;
    }

    /** Die Trefferliste: erste Tabelle, Spalten aus {@code thead th} (sonst aus der ersten Zeile). */
    static ResultTable resultTable(String html) {
        if (html == null || html.isBlank()) {
            return ResultTable.empty();
        }
        Element table = Jsoup.parse(html).selectFirst("table");
        if (table == null) {
            return ResultTable.empty();
        }
        List<String> columns = new ArrayList<>();
        Elements headers = table.select("thead th");
        if (headers.isEmpty()) {
            Element firstRow = table.selectFirst("tbody tr");
            if (firstRow != null) {
                firstRow.select("td").forEach(cell -> columns.add(cell.text().trim()));
            }
        } else {
            headers.forEach(header -> columns.add(header.text().trim()));
        }
        List<ResultTable.Row> rows = new ArrayList<>();
        for (Element tr : table.select("tbody tr")) {
            Elements cells = tr.select("td");
            if (cells.isEmpty()) {
                continue;
            }
            List<String> values = new ArrayList<>();
            for (int i = 0; i < cells.size() && i < columns.size(); i++) {
                Element cell = cells.get(i);
                Element link = cell.selectFirst("a");
                values.add((link != null ? link.text() : cell.text()).trim());
            }
            String action = "";
            Element link = tr.selectFirst("a");
            if (link != null) {
                String onclick = link.attr("onclick");
                String found = onclick.isEmpty() ? null : first(ACTION_IN_SCRIPT, onclick);
                if (found == null && !link.attr("href").isEmpty()) {
                    found = link.attr("href");
                }
                if (found != null) {
                    action = Parser.unescapeEntities(found, false).trim();
                }
            }
            rows.add(new ResultTable.Row(values, action));
        }
        return new ResultTable(columns, rows);
    }

    /** Die Reiter des Dokumentbrowsers ({@code #we_id_docbrowser_tabs li}). */
    static List<DocumentTab> documentTabs(String html) {
        List<DocumentTab> tabs = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return tabs;
        }
        for (Element li : Jsoup.parse(html).select("#we_id_docbrowser_tabs li")) {
            Element content = li.selectFirst(".el_tab_content");
            Element link = content == null ? null : content.selectFirst("a");
            if (link == null) {
                continue;
            }
            String href = link.attr("href").trim();
            Elements parts = content.select(".el_tab_element");
            String timestamp = parts.size() > 0 ? parts.get(0).text().trim() : "";
            String title = parts.size() > 1 ? parts.get(1).text().trim() : "";
            String linkId = "";
            Element close = content.selectFirst("button.el_tab_close");
            if (close != null) {
                linkId = orEmpty(first(LINK_ID, close.attr("onclick")));
                if (linkId.isEmpty() && close.id().startsWith("we_id_close_btn_")) {
                    linkId = close.id().substring("we_id_close_btn_".length());
                }
            }
            tabs.add(new DocumentTab(orEmpty(first(DOC_ID, href)), orEmpty(first(FAV_ID, href)), linkId, title,
                    timestamp, Parser.unescapeEntities(href, false).trim(), li.hasClass("active")));
        }
        return tabs;
    }

    /**
     * Ein geöffnetes Dokument: Reiter, Seitenzahl ({@code #numberPages}), Kennung ({@code #we-documentid}) und die
     * Kurzinfo. Ohne aktiven Reiter steht das Dokument unter der Aktion, mit der es geöffnet wurde.
     */
    static OpenedDocument openedDocument(String html, String action, String fallbackTitle) {
        Document doc = Jsoup.parse(html == null ? "" : html);
        List<DocumentTab> tabs = documentTabs(html);
        DocumentTab active = tabs.stream().filter(DocumentTab::active).findFirst()
                .orElseGet(() -> new DocumentTab("", "", "", fallbackTitle, "", action, true));
        if (!tabs.contains(active)) {
            tabs.add(active);
        }
        Element docIdField = doc.selectFirst("#we-documentid");
        String documentId = docIdField == null ? "" : docIdField.attr("value").trim();
        int pages = 1;
        Element numberPages = doc.selectFirst("#numberPages");
        if (numberPages != null) {
            try {
                pages = Integer.parseInt(numberPages.attr("value").trim());
            } catch (NumberFormatException ignored) {
                pages = 1;
            }
        }
        Element headlineElement = doc.selectFirst("#we_id_docprops_headline");
        List<InfoLine> info = new ArrayList<>();
        if (!documentId.isEmpty()) {
            info.add(new InfoLine("Dokument-ID", documentId));
        }
        info.add(new InfoLine("Seiten", String.valueOf(Math.max(1, pages))));
        for (Element row : doc.select(".el_panel_info .row")) {
            Elements labels = row.select(".el_label_desc");
            Elements values = row.select(".el_label_value");
            for (int i = 0; i < Math.min(labels.size(), values.size()); i++) {
                InfoLine line = new InfoLine(labels.get(i).text(), values.get(i).text());
                if (!line.label().isEmpty() || !line.value().isEmpty()) {
                    info.add(line);
                }
            }
        }
        Element block = doc.selectFirst("#we_id_document_info");
        if (block != null) {
            for (Element row : block.select("div.row")) {
                Elements cols = row.select("div[class*=col]");
                if (cols.size() >= 2) {
                    InfoLine line = new InfoLine(cols.get(0).text(), cols.get(1).text());
                    if (!line.label().isEmpty() || !line.value().isEmpty()) {
                        info.add(line);
                    }
                }
            }
        }
        return new OpenedDocument(active, tabs, pages, headlineElement == null ? "" : headlineElement.text(), info);
    }

    /**
     * Text einer Seite aus dem JSON von {@code document.page.get.action}: das Feld {@code "text"} ist eine Liste von
     * Zeilen. Ohne dieses Feld kommt die Antwort unverändert zurück (wie in MainframeMate).
     */
    static String pageText(String json) {
        if (json == null) {
            return "";
        }
        int textIndex = json.indexOf("\"text\"");
        if (textIndex < 0) {
            return json;
        }
        int start = json.indexOf('[', textIndex);
        if (start < 0) {
            return json;
        }
        StringBuilder out = new StringBuilder();
        StringBuilder line = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        for (int i = start + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n' -> line.append('\n');
                    case 't' -> line.append('\t');
                    case 'r' -> { }
                    case 'u' -> {
                        if (i + 4 < json.length()) {
                            try {
                                line.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                line.append('u');
                            }
                        }
                    }
                    default -> line.append(c);
                }
                escaped = false;
            } else if (inString && c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString && c == ',') {
                out.append(line).append('\n');
                line.setLength(0);
            } else if (!inString && c == ']') {
                break;
            } else if (inString) {
                line.append(c);
            }
        }
        if (line.length() > 0) {
            out.append(line).append('\n');
        }
        return out.toString();
    }

    /** Die gespeicherten Suchen ({@code .el_wall_btn} mit Formular an {@code executeSavedSelection}). */
    static List<SavedSearch> savedSearches(String html) {
        List<SavedSearch> result = new ArrayList<>();
        if (html == null) {
            return result;
        }
        for (Element item : Jsoup.parse(html).select(".el_wall_btn")) {
            Element name = item.selectFirst("[name=we_id_selection_name]");
            if (name == null) {
                continue;
            }
            Element description = item.selectFirst("[name=selection_desc]");
            String action = "";
            String tokenName = "";
            String tokenValue = "";
            Element form = item.selectFirst("form[action*=executeSavedSelection]");
            if (form != null) {
                action = form.attr("action");
                if (action.contains("/")) {
                    action = action.substring(action.lastIndexOf('/') + 1);
                }
                Element tokenNameInput = form.selectFirst("input[name=struts.token.name]");
                if (tokenNameInput != null) {
                    tokenName = tokenNameInput.attr("value");
                    for (Element input : form.select("input")) {
                        if (input.attr("name").equals(tokenName)) {
                            tokenValue = input.attr("value");
                        }
                    }
                }
            }
            if (action.isEmpty()) {
                Element link = item.selectFirst("a[href*=savedSelection]");
                String id = link == null ? null : first(EXECUTE_BY_ID, link.attr("href"));
                if (id != null) {
                    action = "executeSavedSelection.action?ssID=" + id;
                }
            }
            Element idInput = item.selectFirst("input[name=ssIDForm]");
            result.add(new SavedSearch(name.text(), description == null ? "" : description.text(),
                    Parser.unescapeEntities(action, false), tokenName, tokenValue,
                    idInput == null ? "" : idInput.attr("value")));
        }
        return result;
    }

    /** Die gemerkten Dokumente ({@code .el_wall_btn} mit {@code a.we_id_bookmark_link}). */
    static List<Bookmark> bookmarks(String html) {
        List<Bookmark> result = new ArrayList<>();
        if (html == null) {
            return result;
        }
        for (Element item : Jsoup.parse(html).select(".el_wall_btn")) {
            Element name = item.selectFirst("[name=bookmark_name]");
            if (name == null) {
                continue;
            }
            Element description = item.selectFirst("[name=bookmark_desc]");
            Element link = item.selectFirst("a.we_id_bookmark_link");
            String action = link == null ? "" : Parser.unescapeEntities(link.attr("href").trim(), false);
            int id = -1;
            Element edit = item.selectFirst("a[id^=we_id_bookmark_edit]");
            String found = edit == null ? null : first(EDIT_BY_ID, edit.attr("href"));
            if (found != null) {
                id = Integer.parseInt(found);
            }
            result.add(new Bookmark(name.text(), description == null ? "" : description.text(), action, id));
        }
        return result;
    }

    /**
     * Offene Reiter des Dokumentbrowsers aus dem JSON von {@code getDBBookmarksJSON.json.action}: Einträge mit
     * {@code "fav":"<docbrowsertab>"}.
     */
    static List<DocumentTab> openTabs(String json) {
        List<DocumentTab> result = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return result;
        }
        int key = json.indexOf("\"dbbookmarks\"");
        if (key < 0) {
            return result;
        }
        int start = json.indexOf('[', key);
        int end = start < 0 ? -1 : json.indexOf(']', start);
        if (end < 0) {
            return result;
        }
        for (String object : json.substring(start + 1, end).split("\\},\\s*\\{")) {
            String entry = object.trim();
            if (entry.startsWith("{")) {
                entry = entry.substring(1);
            }
            if (entry.endsWith("}")) {
                entry = entry.substring(0, entry.length() - 1);
            }
            if (!"<docbrowsertab>".equals(jsonString(entry, "fav"))) {
                continue;
            }
            String link = jsonString(entry, "link");
            String docId = "";
            String favId = "";
            for (String part : link.split("&")) {
                if (part.startsWith("docid=")) {
                    docId = part.substring(6);
                } else if (part.startsWith("favid=")) {
                    favId = part.substring(6);
                }
            }
            result.add(new DocumentTab(docId, favId, jsonString(entry, "linkID"), jsonString(entry, "name"),
                    jsonString(entry, "description"), "opendocumentlink.action?" + link, false));
        }
        return result;
    }

    /** Dokumentinformationen ({@code .row} mit {@code .el_label_desc}/{@code .el_label_value}). */
    static List<InfoLine> properties(String html) {
        Document doc = Jsoup.parse(html == null ? "" : html);
        List<InfoLine> lines = new ArrayList<>();
        for (Element row : doc.select(".row")) {
            Elements labels = row.select(".el_label_desc");
            Elements values = row.select(".el_label_value");
            for (int i = 0; i < Math.min(labels.size(), values.size()); i++) {
                InfoLine line = new InfoLine(labels.get(i).text(), values.get(i).text());
                if (!line.label().isEmpty() || !line.value().isEmpty()) {
                    lines.add(line);
                }
            }
            if (labels.isEmpty() && values.isEmpty() && !row.text().isBlank()) {
                lines.add(new InfoLine("", row.text()));
            }
        }
        if (lines.isEmpty() && !doc.text().isBlank()) {
            lines.add(new InfoLine("", doc.text()));
        }
        return lines;
    }

    /** Vorschlag für ein Lesezeichen ({@code #we_id_bookmark_name}, {@code #we_id_bookmark_desc}). */
    static NameAndDescription bookmarkProposal(String html) {
        Document doc = Jsoup.parse(html == null ? "" : html);
        Element name = doc.selectFirst("#we_id_bookmark_name");
        Element description = doc.selectFirst("#we_id_bookmark_desc");
        return new NameAndDescription(name == null ? "" : name.attr("value"),
                description == null ? "" : description.attr("value"));
    }

    private static String jsonString(String object, String key) {
        String search = "\"" + key + "\":\"";
        int start = object.indexOf(search);
        if (start < 0) {
            return "";
        }
        start += search.length();
        int end = object.indexOf('"', start);
        return end > start ? object.substring(start, end) : "";
    }

    private static String first(Pattern pattern, String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
