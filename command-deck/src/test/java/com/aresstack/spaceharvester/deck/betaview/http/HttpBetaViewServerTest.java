package com.aresstack.spaceharvester.deck.betaview.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.deck.betaview.BetaViewConnection;
import com.aresstack.spaceharvester.deck.betaview.BetaViewProfile;
import com.aresstack.spaceharvester.deck.betaview.Bookmark;
import com.aresstack.spaceharvester.deck.betaview.DocumentTab;
import com.aresstack.spaceharvester.deck.betaview.DownloadOptions;
import com.aresstack.spaceharvester.deck.betaview.DownloadedFile;
import com.aresstack.spaceharvester.deck.betaview.NameAndDescription;
import com.aresstack.spaceharvester.deck.betaview.OpenedDocument;
import com.aresstack.spaceharvester.deck.betaview.ResultTable;
import com.aresstack.spaceharvester.deck.betaview.SavedSearch;
import com.aresstack.spaceharvester.deck.betaview.SearchFilter;
import com.aresstack.spaceharvester.deck.http.HttpClientFactory;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Der HTTP-Adapter gegen den Schein-Server: dieselben Schritte, die MainframeMate gegen den echten Server macht. */
class HttpBetaViewServerTest {

    private FakeBetaViewServer fake;
    private HttpBetaViewServer server;

    @BeforeEach
    void start() throws IOException {
        fake = new FakeBetaViewServer();
        server = new HttpBetaViewServer(HttpClientFactory.direct(), Duration.ofSeconds(10));
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private BetaViewConnection login() throws IOException {
        return server.login(new BetaViewProfile(fake.address(), FakeBetaViewServer.USER),
                FakeBetaViewServer.PASSWORD.toCharArray());
    }

    private static SearchFilter filter(String jobName) {
        return new SearchFilter(FakeBetaViewServer.FAVORITE, "de", null, null, null, null, jobName, null, null, null,
                false, null, null);
    }

    @Test
    void logsInWithTokenCookieAndCsrf() throws IOException {
        login();
        assertThat(fake.requests()).containsSubsequence("GET login.action", "POST login.action",
                "GET welcome.action", "GET getAppContext.action");
    }

    @Test
    void aWrongPasswordIsRejectedWithAReadableReason() {
        assertThatThrownBy(() -> server.login(new BetaViewProfile(fake.address(), FakeBetaViewServer.USER),
                "falsch".toCharArray()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Benutzer oder Kennwort falsch");
    }

    @Test
    void searchesWithTheFilterAndReadsTheResultTable() throws IOException {
        BetaViewConnection connection = login();
        ResultTable all = connection.search(filter(null));
        assertThat(all.columns()).containsExactly("Erstellt", "Titel", "Jobname", "Form", "Seiten");
        assertThat(all.rows()).hasSize(3);
        assertThat(all.rows().get(0).action()).isEqualTo("open.action?index=0&source=resulttable");

        ResultTable some = connection.search(filter("LADE*"));
        assertThat(some.rows()).extracting(row -> row.cell(1)).containsExactly("Tagesabschluss Ladelauf");
    }

    @Test
    void anUnknownFavoriteSaysSo() throws IOException {
        BetaViewConnection connection = login();
        SearchFilter unknown = new SearchFilter("XXXX", "de", null, null, null, null, null, null, null, null, false,
                null, null);
        assertThatThrownBy(() -> connection.search(unknown)).isInstanceOf(IOException.class)
                .hasMessageContaining("XXXX");
    }

    @Test
    void opensADocumentAndReadsItsPages() throws IOException {
        BetaViewConnection connection = login();
        ResultTable result = connection.search(filter(null));
        OpenedDocument opened = connection.open(result.rows().get(2).action());
        assertThat(opened.pageCount()).isEqualTo(3);
        assertThat(opened.document().title()).isEqualTo("Statistik Monatslauf");
        assertThat(opened.document().linkId()).isEqualTo("LC3D4E5F6");
        assertThat(opened.info()).extracting(line -> line.label() + "=" + line.value())
                .contains("Dokument-ID=C3D4E5F6", "Seiten=3", "Jobname=STATJOB1");

        assertThat(connection.page(1)).startsWith("1STATISTIK MONATSLAUF").contains("SEITE 1");
        assertThat(connection.page(2)).contains("VERTEILUNG NACH TAGEN").contains("SEITE 2");
    }

    @Test
    void readsNotesMarksPrintInfoAndSearchesInTheDocument() throws IOException {
        BetaViewConnection connection = login();
        ResultTable result = connection.search(filter(null));
        connection.open(result.rows().get(0).action());
        assertThat(connection.notes()).isEqualTo("Bitte bis Freitag prüfen");
        assertThat(connection.markedRanges()).isEqualTo("Keine markierten Bereiche.");
        assertThat(connection.printInfo()).isEqualTo("Drucker: Etage 3, 2 Seiten");
        assertThat(connection.searchText("pruefziffer")).contains("Seite 2").contains("PRUEFZIFFER FALSCH");
        assertThat(fake.requests()).contains("GET document.notes.list.action", "GET holdhide.list.action",
                "POST document.search.action", "GET printDialog.action");

        connection.open(result.rows().get(1).action());
        assertThat(connection.notes()).isEqualTo("Keine Notizen vorhanden.");
    }

    @Test
    void downloadsQuicklyAndWithOptions() throws IOException {
        BetaViewConnection connection = login();
        connection.open(connection.search(filter(null)).rows().get(0).action());
        assertThat(connection.downloadChoices().pages()).isEqualTo(2);

        DownloadedFile all = connection.download(DownloadOptions.quick());
        assertThat(new String(all.data())).contains("SEITE 1").contains("SEITE 2");
        assertThat(all.extension()).isEqualTo(".txt");
        assertThat(fake.lastDownload()).containsEntry("printStyle", "printAllPages").containsEntry("rangeEnd", "2")
                .containsEntry("downloadFormat", "ORIG");

        DownloadedFile second = connection.download(new DownloadOptions(DownloadOptions.Pages.RANGE, 2, 2,
                DownloadOptions.Format.CSV, DownloadOptions.Delimiter.SEMICOLON,
                DownloadOptions.Split.AFTER_CHARACTERS, "12", true, null, null));
        assertThat(new String(second.data())).contains("SEITE 2").doesNotContain("SEITE 1");
        assertThat(second.extension()).isEqualTo(".csv");
        assertThat(fake.lastDownload()).containsEntry("printStyle", "printRange").containsEntry("rangeBegin", "2")
                .containsEntry("delimiter", "semicolon").containsEntry("csv_int", "12")
                .containsEntry("csv_trim", "true");
    }

    @Test
    void bookmarksAndReopensADocument() throws IOException {
        BetaViewConnection connection = login();
        connection.open(connection.search(filter(null)).rows().get(1).action());
        NameAndDescription proposal = connection.bookmarkProposal();
        assertThat(proposal.name()).isEqualTo("Protokoll Entladelauf");
        connection.bookmark(new NameAndDescription("Entladen", "zum Nachsehen"));

        List<Bookmark> bookmarks = connection.bookmarks();
        assertThat(bookmarks).extracting(Bookmark::name).containsExactly("Entladen");
        assertThat(bookmarks.get(0).id()).isEqualTo(1);
        OpenedDocument reopened = connection.open(bookmarks.get(0).action());
        assertThat(reopened.document().title()).isEqualTo("Protokoll Entladelauf");
        assertThat(connection.properties()).extracting(line -> line.label() + "=" + line.value())
                .contains("Jobname=ENTLADE1");
    }

    @Test
    void savesListsAndRunsASearch() throws IOException {
        BetaViewConnection connection = login();
        connection.search(filter("STAT*"));
        connection.saveSearch(new NameAndDescription("Statistik", "nur Monatsläufe"));
        List<SavedSearch> saved = connection.savedSearches();
        assertThat(saved).extracting(SavedSearch::name).containsExactly("Statistik");
        assertThat(saved.get(0).action()).isEqualTo("executeSavedSelection.action?ssID=1");

        connection.search(filter(null));
        ResultTable rerun = connection.run(saved.get(0));
        assertThat(rerun.rows()).extracting(row -> row.cell(1)).containsExactly("Statistik Monatslauf");
        // Dieselbe Suche noch einmal und nach einer neu gezeichneten Liste: das Token der alten Liste gilt nicht mehr,
        // der Server antwortet darauf mit HTTP 400. Vor dem Absenden wird das Formular frisch gelesen.
        connection.savedSearches();
        assertThat(connection.run(saved.get(0)).rows()).hasSize(1);
        assertThat(connection.run(saved.get(0)).rows()).hasSize(1);
    }

    @Test
    void aRejectedSavedSearchReportsTheReasonOfTheServer() throws IOException {
        BetaViewConnection connection = login();
        connection.search(filter(null));
        connection.saveSearch(new NameAndDescription(FakeBetaViewServer.BROKEN_LAYOUT, ""));
        SavedSearch broken = connection.savedSearches().get(0);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> connection.run(broken))
                .isInstanceOf(IOException.class)
                .hasMessage("Server antwortet HTTP 400 auf executeSavedSelection.action: Das Layout existiert nicht "
                        + "(in Ihrer Sprache). Bitte löschen Sie die gespeicherte Suche.");
    }

    @Test
    void anOfflineDocumentReportsTheReasonFromTheSessionStatus() throws IOException {
        BetaViewConnection connection = login();
        String link = "opendocumentlink.action?docid=" + FakeBetaViewServer.OFFLINE_DOC + "&favid="
                + FakeBetaViewServer.FAVORITE;
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> connection.open(link))
                .isInstanceOf(IOException.class)
                .hasMessage("Server antwortet HTTP 400 auf opendocumentlink.action: Das Dokument ist offline.");
    }

    @Test
    void decodesDoublyEncodedNamesOfSavedSearchesAndSendsTheWholeForm() {
        String html = "<div class=\"el_wall_btn\"><div name=\"we_id_selection_name\">R&amp;uuml;cknachrichten</div>"
                + "<div name=\"selection_desc\">Gr&uuml;n</div><form action=\"/betaview/executeSavedSelection.action?ssID=7\">"
                + "<input type=\"hidden\" name=\"struts.token.name\" value=\"token\">"
                + "<input type=\"hidden\" name=\"token\" value=\"T1\"><input type=\"hidden\" name=\"ssIDForm\" value=\"7\">"
                + "<input type=\"text\" name=\"days\" value=\"60\"><input type=\"checkbox\" name=\"off\" value=\"1\">"
                + "<input type=\"submit\" name=\"go\" value=\"Los\"></form></div>";
        List<SavedSearch> saved = BetaViewHtml.savedSearches(html);
        assertThat(saved).extracting(SavedSearch::name).containsExactly("Rücknachrichten");
        assertThat(saved).extracting(SavedSearch::description).containsExactly("Grün");
        assertThat(BetaViewHtml.savedSearchForm(html, saved.get(0))).containsExactly(
                java.util.Map.entry("struts.token.name", "token"), java.util.Map.entry("token", "T1"),
                java.util.Map.entry("ssIDForm", "7"), java.util.Map.entry("days", "60"));
    }

    @Test
    void listsAndClosesTheOpenTabsOfTheServer() throws IOException {
        BetaViewConnection connection = login();
        ResultTable result = connection.search(filter(null));
        connection.open(result.rows().get(0).action());
        OpenedDocument second = connection.open(result.rows().get(1).action());
        assertThat(second.tabs()).hasSize(2).filteredOn(DocumentTab::active).hasSize(1);

        List<DocumentTab> open = connection.openTabs();
        assertThat(open).extracting(DocumentTab::title)
                .containsExactly("Tagesabschluss Ladelauf", "Protokoll Entladelauf");
        connection.close(open.get(0));
        assertThat(connection.openTabs()).extracting(DocumentTab::title).containsExactly("Protokoll Entladelauf");
        connection.closeAll();
        assertThat(connection.openTabs()).isEmpty();
    }
}
