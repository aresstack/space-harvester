package com.aresstack.spaceharvester.deck.betaview.http;

import com.aresstack.spaceharvester.deck.betaview.BetaViewConnection;
import com.aresstack.spaceharvester.deck.betaview.Bookmark;
import com.aresstack.spaceharvester.deck.betaview.DocumentTab;
import com.aresstack.spaceharvester.deck.betaview.DownloadChoices;
import com.aresstack.spaceharvester.deck.betaview.DownloadOptions;
import com.aresstack.spaceharvester.deck.betaview.DownloadedFile;
import com.aresstack.spaceharvester.deck.betaview.InfoLine;
import com.aresstack.spaceharvester.deck.betaview.NameAndDescription;
import com.aresstack.spaceharvester.deck.betaview.OpenedDocument;
import com.aresstack.spaceharvester.deck.betaview.ResultTable;
import com.aresstack.spaceharvester.deck.betaview.SavedSearch;
import com.aresstack.spaceharvester.deck.betaview.SearchFilter;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Die Vorgänge an einem angemeldeten BetaView-Server, Feld für Feld wie in MainframeMate
 * ({@code LoadResultsHtmlUseCase}, {@code DownloadDocumentUseCase}, {@code BetaViewConnectionTab},
 * {@code BetaViewDocumentTab}, {@code BetaViewDocumentTabManager}).
 */
final class HttpBetaViewConnection implements BetaViewConnection {

    static final String DOWNLOAD_DIALOG = "downloadDialog.action?&init=yes&source=docbrowser";

    private final BetaViewHttp http;

    HttpBetaViewConnection(BetaViewHttp http) {
        this.http = http;
    }

    @Override
    public ResultTable search(SearchFilter filter) throws IOException {
        String select = http.getText("select.action?favoriteID="
                + URLEncoder.encode(filter.favoriteId(), StandardCharsets.UTF_8));
        Map<String, String> hidden = BetaViewHtml.hiddenInputs(select);
        String tokenName = hidden.get("struts.token.name");
        if (tokenName == null) {
            throw new IOException("Die Auswahlseite des Favoriten „" + filter.favoriteId()
                    + "“ hat kein Formular-Token – gibt es den Favoriten?");
        }
        String tokenValue = hidden.get(tokenName);
        if (tokenValue == null) {
            throw new IOException("Auf der Auswahlseite fehlt der Wert des Tokens " + tokenName);
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("struts.token.name", tokenName);
        form.put(tokenName, tokenValue);
        SearchFilter.Period period = filter.period();
        form.put("getDateMask()", "DD.MM.YYYY");
        form.put("favID", filter.favoriteId());
        form.put("locale", filter.locale());
        form.put("focus", "we_id_searchbtn");
        form.put("lastdate", period.lastdate());
        form.put("timesel", "sub");
        form.put("lastsel", period.lastsel());
        form.put("lasthoursdaysvalue", Integer.toString(period.amount()));
        form.put("timeunit", period.unit().value());
        form.put("datefrom", period.dateFrom());
        form.put("timefrom", "");
        form.put("dateto", period.dateTo());
        form.put("timeto", "");
        form.put("FOLDER", filter.folder());
        form.put("TAB", filter.tab());
        form.put("TITLE", filter.title());
        form.put("FTITLE", filter.exactTitle() ? "1" : "0");
        form.put("RECI", filter.recipient());
        if (filter.needsSelgen()) {
            form.put("SELGEN", "yes");
        }
        form.put("FORM", filter.form());
        form.put("EXTENSION", filter.extension());
        form.put("REPORT", filter.report());
        form.put("JOBNAME", filter.jobName());
        SearchFilter.Status status = filter.status();
        form.put("ONLINE", status.online());
        form.put("LGRNOTE", status.note());
        form.put("LGRXREAD", status.read());
        form.put("ARCHIVE", status.archive());
        form.put("PROCESS", status.process());
        form.put("DELETE", status.delete());
        form.put("LGRSTAT", status.listState());
        form.put("RELOAD", status.reload());
        http.postText("result.action", form);
        return BetaViewHtml.resultTable(http.getText("showResult.action"));
    }

    @Override
    public OpenedDocument open(String action) throws IOException {
        return BetaViewHtml.openedDocument(http.getText(action), action, "Dokument");
    }

    @Override
    public String page(int page) throws IOException {
        if (page < 1) {
            throw new IllegalArgumentException("Seite ab 1, nicht " + page);
        }
        http.postText("document.viewer.navigation.action?directItem=" + page, new LinkedHashMap<>());
        Map<String, String> form = new LinkedHashMap<>();
        form.put("pageIndex", String.valueOf(page - 1));
        return BetaViewHtml.pageText(http.postText("document.page.get.action", form));
    }

    @Override
    public DownloadChoices downloadChoices() throws IOException {
        Map<String, String> hidden = BetaViewHtml.hiddenInputs(http.postText(DOWNLOAD_DIALOG, new LinkedHashMap<>()));
        int pages;
        try {
            pages = Integer.parseInt(hidden.getOrDefault("pages", "1").trim());
        } catch (NumberFormatException e) {
            pages = 1;
        }
        return new DownloadChoices(pages, "true".equals(hidden.getOrDefault("csvEnabled", "false")));
    }

    @Override
    public DownloadedFile download(DownloadOptions options) throws IOException {
        Map<String, String> hidden = BetaViewHtml.hiddenInputs(http.postText(DOWNLOAD_DIALOG, new LinkedHashMap<>()));
        Map<String, String> form = new LinkedHashMap<>();
        token(hidden, form);
        // Feste Felder des Dialogs mit den Vorgaben aus MainframeMate (DownloadDocumentUseCase).
        put(form, hidden, "function", "download");
        put(form, hidden, "product", "BetaDocZ");
        put(form, hidden, "source", "docbrowser");
        put(form, hidden, "pages", "1");
        put(form, hidden, "docLink", "false");
        put(form, hidden, "convert", "N");
        put(form, hidden, "convertEnabled", "false");
        put(form, hidden, "csvEnabled", "true");
        put(form, hidden, "doctype", "txt");
        put(form, hidden, "jobinfo", "false");
        put(form, hidden, "textenco", "UTF-8");
        put(form, hidden, "bss_ukey", "");
        put(form, hidden, "idxstatus", "");
        put(form, hidden, "hasHiddenRanges", "false");
        put(form, hidden, "dbinst", "");
        put(form, hidden, "docid", "");
        put(form, hidden, "lineColumnRanges", "");
        if (hidden.containsKey("index")) {
            form.put("index", hidden.get("index"));
        }
        form.put("markedPages", "");
        form.put("suffix", "");
        form.put("rangePrintStyle", "");
        form.put("printStyle", options.pages().value());
        boolean range = options.pages() == DownloadOptions.Pages.RANGE;
        form.put("rangeBegin", range ? String.valueOf(options.from()) : "1");
        form.put("rangeEnd", range ? String.valueOf(options.to()) : hidden.getOrDefault("pages", "1"));
        boolean csv = options.format() == DownloadOptions.Format.CSV;
        form.put("downloadFormat", options.format().value());
        form.put("csvStyle", options.split().value());
        form.put("delimiter", options.delimiter().value());
        String value = options.csvValue();
        form.put("csv_int", csv && options.split() == DownloadOptions.Split.AFTER_CHARACTERS && !value.isEmpty()
                ? value : hidden.getOrDefault("csv_int", "10"));
        form.put("csv_char", csv && options.split() == DownloadOptions.Split.REPLACE_CHARACTER ? value : "");
        form.put("csv_flex", csv && options.split() == DownloadOptions.Split.VARIABLE ? value : "");
        form.put("dataInput", csv ? value : "");
        form.put("csv_trim", String.valueOf(csv && options.csvTrim()));
        form.put("printWhatStyle", options.notes().value());
        form.put("antsPrintStyle", "antsToDoc");
        form.put("antsType", options.noteKind().value());
        BetaViewHttp.Response response = http.postResponse("downloadWithOptions.action", form);
        return new DownloadedFile(response.body(), response.contentType());
    }

    @Override
    public List<InfoLine> properties() throws IOException {
        return BetaViewHtml.properties(http.getText("docbrowser.documentproperties.get.action"));
    }

    @Override
    public NameAndDescription bookmarkProposal() throws IOException {
        return BetaViewHtml.bookmarkProposal(http.postText("docbrowser.bookmark.get.action", new LinkedHashMap<>()));
    }

    @Override
    public void bookmark(NameAndDescription bookmark) throws IOException {
        Map<String, String> hidden = BetaViewHtml.hiddenInputs(
                http.postText("docbrowser.bookmark.get.action", new LinkedHashMap<>()));
        Map<String, String> form = new LinkedHashMap<>();
        token(hidden, form);
        form.put("bookmark_name", bookmark.name());
        form.put("bookmark_desc", bookmark.description());
        form.put("indexDocResultList", hidden.getOrDefault("indexDocResultList", "-1"));
        form.put("source", hidden.getOrDefault("source", "docbrowser"));
        http.postText("bookmarks.add.action", form);
    }

    @Override
    public List<Bookmark> bookmarks() throws IOException {
        return BetaViewHtml.bookmarks(http.postText("bookmarks.list.action", csrfForm()));
    }

    @Override
    public List<SavedSearch> savedSearches() throws IOException {
        return BetaViewHtml.savedSearches(http.postText("savedSelection.list.action", csrfForm()));
    }

    @Override
    public ResultTable run(SavedSearch search) throws IOException {
        if (search.action().isEmpty()) {
            throw new IOException("Die gespeicherte Suche „" + search.name() + "“ hat keine Aktion");
        }
        Map<String, String> form = new LinkedHashMap<>();
        if (!search.tokenName().isEmpty()) {
            form.put("struts.token.name", search.tokenName());
            form.put(search.tokenName(), search.tokenValue());
        }
        if (!search.id().isEmpty()) {
            form.put("ssIDForm", search.id());
        }
        String html = http.postText(search.action(), form);
        if (!html.contains("resulttable") && !html.contains("showResult")) {
            html = http.getText("showResult.action");
        }
        return BetaViewHtml.resultTable(html);
    }

    @Override
    public void saveSearch(NameAndDescription search) throws IOException {
        Map<String, String> hidden = BetaViewHtml.hiddenInputs(
                http.postText("savedSelection.fetchAddDialog.action", csrfForm()));
        Map<String, String> form = new LinkedHashMap<>();
        token(hidden, form);
        form.put("selection_name", search.name());
        form.put("selection_desc", search.description());
        http.postText("savedSelection.add.action", form);
    }

    @Override
    public List<DocumentTab> openTabs() throws IOException {
        return BetaViewHtml.openTabs(http.postText("getDBBookmarksJSON.json.action", csrfForm()));
    }

    @Override
    public void close(DocumentTab document) throws IOException {
        if (!document.linkId().isEmpty()) {
            http.getText("closeSingleDocument.action?linkID="
                    + URLEncoder.encode(document.linkId(), StandardCharsets.UTF_8));
        }
    }

    @Override
    public void closeAll() throws IOException {
        http.getText("closeAllDocuments.action");
    }

    /** Ein Formular nur mit dem CSRF-Token, wie MainframeMate es für Listen schickt. */
    private Map<String, String> csrfForm() {
        Map<String, String> form = new LinkedHashMap<>();
        http.csrfToken().ifPresent(token -> form.put("csrfToken", token));
        return form;
    }

    private static void token(Map<String, String> hidden, Map<String, String> form) {
        String tokenName = hidden.get("struts.token.name");
        if (tokenName != null) {
            form.put("struts.token.name", tokenName);
            String tokenValue = hidden.get(tokenName);
            if (tokenValue != null) {
                form.put(tokenName, tokenValue);
            }
        }
    }

    private static void put(Map<String, String> form, Map<String, String> hidden, String key, String fallback) {
        form.put(key, hidden.getOrDefault(key, fallback));
    }
}
