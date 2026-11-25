package com.aresstack.spaceharvester.app.ui.deck.betaview;

import com.aresstack.spaceharvester.app.ui.controls.StatusNote;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.controls.ComicDivider;
import com.aresstack.spaceharvester.deck.betaview.BetaViewSession;
import com.aresstack.spaceharvester.deck.betaview.BetaViewSessions;
import com.aresstack.spaceharvester.deck.betaview.Bookmark;
import com.aresstack.spaceharvester.deck.betaview.ConnectionState;
import com.aresstack.spaceharvester.deck.betaview.DocumentTab;
import com.aresstack.spaceharvester.deck.betaview.DownloadOptions;
import com.aresstack.spaceharvester.deck.betaview.DownloadedFile;
import com.aresstack.spaceharvester.deck.betaview.ResultTable;
import com.aresstack.spaceharvester.deck.betaview.SavedSearch;
import com.aresstack.spaceharvester.deck.betaview.SearchFilter;
import java.awt.BorderLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;

/**
 * Eine angemeldete BetaView-Sitzung: links Suche und Treffer, rechts die Dokumente, geteilt durch den Trenner der App;
 * abgemeldet wird über das Kreuz am Reiter der Sitzung; was die Sitzung tut (Fehler rot mit Grund), steht in der Statuszeile der App. Jeder Vorgang läuft
 * über {@link BetaViewSession} im Hintergrund; Ergebnisse kommen auf dem EDT an.
 */
final class SessionPanel extends JPanel implements SearchPane.Actions, DocumentPane.Actions {

    private final BetaViewSession session;
    private final Prompts prompts;
    final SearchPane search;
    final JSplitPane split;
    final DocumentPane documents;
    /** Stand der Sitzung; die Arbeitsfläche zeigt den der gewählten Sitzung in der Statuszeile der App. */
    private StatusNote status = StatusNote.plain(" ");
    private java.util.function.Consumer<StatusNote> onStatus = note -> { };
    private boolean tabsFetched;

    SessionPanel(BetaViewSession session, Prompts prompts, Runnable logout) {
        super(new BorderLayout());
        this.session = session;
        this.prompts = prompts;
        setOpaque(false);
        search = new SearchPane(this);
        documents = new DocumentPane(this);
        // Zweigeteilt wie im Space Harvester (Angelo 24.11.2026): unter der Suche die Fußleiste der Seitenleiste, unter
        // den Dokumenten die Leiste mit der Lupe.
        javax.swing.JPanel side = new javax.swing.JPanel(new java.awt.BorderLayout());
        side.setOpaque(false);
        side.add(search, java.awt.BorderLayout.CENTER);
        side.add(new com.aresstack.spaceharvester.app.ui.controls.SideFoot(null, search.footTabs(), null),
                java.awt.BorderLayout.SOUTH);
        split = new com.aresstack.spaceharvester.app.ui.controls.LeftSidebarPane(side,
                com.aresstack.spaceharvester.app.ui.deck.DeckBars.withFind(documents),
                Math.max(380, search.headerWidth())).withDividerToggle();
        documents.setMinimumSize(new java.awt.Dimension(320, 0));
        add(split, BorderLayout.CENTER);
        stateChanged();
    }

    BetaViewSession session() {
        return session;
    }

    /** Der Zustand der Sitzung hat sich geändert (auf dem EDT). */
    void stateChanged() {
        ConnectionState state = session.state();
        boolean live = state == ConnectionState.CONNECTED;
        search.searchButton.setEnabled(live);
        search.saveSearchButton.setEnabled(live);
        switch (state) {
            case CONNECTING -> info(session.profile().label() + " · Melde an …");
            case CONNECTED -> {
                info(session.profile().label() + " · Angemeldet");
                if (!tabsFetched) {
                    // Wie MainframeMate: offene Reiter des Servers als Pillen, geladen erst beim Anklicken.
                    tabsFetched = true;
                    after(session.openTabs(), documents::known, "Offene Dokumente nicht lesbar");
                }
            }
            case FAILED -> error(session.profile().label() + " · Anmeldung gescheitert – "
                    + session.failure().orElse("ohne Grund"));
            case DISCONNECTED -> info(session.profile().label() + " · Abgemeldet");
        }
    }

    // ---- Suche ----

    @Override
    public void search(SearchFilter filter) {
        search.busy(true, "Suche läuft …");
        info("Suche läuft …");
        after(session.search(filter), result -> {
            search.busy(false, null);
            search.show(result, hitLabel(filter), filter);
            serverOrigin = filter;
            info(result.rows().size() + " Treffer");
        }, "Suche gescheitert", () -> search.busy(false, null));
    }

    @Override
    public void saveSearch() {
        prompts.nameAndDescription(this, "Unter welchem Namen speichern?",
                new com.aresstack.spaceharvester.deck.betaview.NameAndDescription("", ""), "Speichern")
                .ifPresent(entry -> {
                    info("Speichere die Suche …");
                    after(session.saveSearch(entry), done -> {
                        info("Suche gespeichert: " + entry.name());
                        loadSaved();
                    }, "Speichern gescheitert");
                });
    }

    @Override
    public void loadSaved() {
        info("Lade gespeicherte Suchen …");
        after(session.savedSearches(), items -> {
            search.showSaved(items);
            info(items.size() + (items.size() == 1 ? " gespeicherte Suche" : " gespeicherte Suchen"));
        }, "Gespeicherte Suchen nicht lesbar");
    }

    @Override
    public void runSaved(SavedSearch saved) {
        search.busy(true, "Suche „" + saved.name() + "“ läuft …");
        after(session.run(saved), result -> {
            search.busy(false, null);
            search.show(result, saved.name(), saved);
            serverOrigin = saved;
            info("Gespeicherte Suche „" + saved.name() + "“: " + result.rows().size() + " Treffer");
            // Wie MainframeMate: die Liste neu lesen, denn jede Ausführung verbraucht ihr Formular-Token.
            loadSaved();
        }, "Gespeicherte Suche gescheitert", () -> {
            search.busy(false, null);
            // Auch nach einem Fehler die Liste mit frischen Formularen neu lesen, still: der Fehler bleibt stehen.
            session.savedSearches().thenAccept(items -> SwingUtilities.invokeLater(() -> search.showSaved(items)));
        });
    }

    @Override
    public void loadBookmarks() {
        info("Lade gemerkte Dokumente …");
        after(session.bookmarks(), items -> {
            search.showBookmarks(items);
            info(items.size() + (items.size() == 1 ? " gemerktes Dokument" : " gemerkte Dokumente"));
        }, "Gemerkte Dokumente nicht lesbar");
    }

    @Override
    public void openBookmark(Bookmark bookmark) {
        if (bookmark.action().isEmpty()) {
            return;
        }
        openAction(bookmark.action(), bookmark.name());
    }

    @Override
    public void open(ResultTable.Row row, Object origin) {
        if (origin == null || origin == serverOrigin) {
            openAction(row.action(), "Dokument");
            return;
        }
        // Treffer gehen beim Server über ihre Nummer in der zuletzt gesuchten Liste: eine ältere Liste erst neu suchen.
        documents.busy(true, "Stelle die Trefferliste wieder her …");
        java.util.concurrent.CompletableFuture<ResultTable> again = origin instanceof SavedSearch saved
                ? session.run(saved) : session.search((SearchFilter) origin);
        after(again, result -> {
            serverOrigin = origin;
            documents.busy(false, null);
            openAction(row.action(), "Dokument");
            if (origin instanceof SavedSearch) {
                // Jede Ausführung verbraucht das Formular-Token der gespeicherten Suche: Liste still neu lesen.
                session.savedSearches().thenAccept(items -> SwingUtilities.invokeLater(() -> search.showSaved(items)));
            }
        }, "Trefferliste nicht wiederherstellbar", () -> documents.busy(false, null));
    }

    /** Aus welcher Suche die Trefferliste des Servers gerade stammt (Auswahl oder gespeicherte Suche). */
    private Object serverOrigin;

    /** Name der Trefferliste einer Suche: Favorit und Uhrzeit, etwa „A158 · 03:20“. */
    static String hitLabel(SearchFilter filter) {
        String time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        return filter.favoriteId().isBlank() ? "Treffer · " + time : filter.favoriteId().strip() + " · " + time;
    }

    @Override
    public void problem(String message) {
        error(message);
    }

    private void openAction(String action, String name) {
        documents.busy(true, "Öffne " + name + " …");
        info("Öffne " + name + " …");
        after(session.open(action), opened -> {
            documents.busy(false, null);
            documents.opened(opened);
            info("Geöffnet: " + opened.document().label() + " · " + opened.pageCount()
                    + (opened.pageCount() == 1 ? " Seite" : " Seiten"));
        }, "Öffnen gescheitert", () -> documents.busy(false, null));
    }

    // ---- Dokumente ----

    @Override
    public void open(DocumentTab document) {
        documents.busy(true, "Öffne " + document.label() + " …");
        after(session.open(document), opened -> {
            documents.busy(false, null);
            documents.opened(opened);
            info("Geöffnet: " + opened.document().label());
        }, "Öffnen gescheitert", () -> documents.busy(false, null));
    }

    @Override
    public void page(DocumentTab document, int page) {
        documents.busy(true, "Lade Seite " + page + " …");
        after(session.page(document, page), content -> {
            documents.busy(false, null);
            documents.page(document, page, content);
        }, "Seite " + page + " nicht lesbar", () -> {
            documents.busy(false, null);
            documents.pageFailed(document, page);
        });
    }

    @Override
    public void download(DocumentTab document) {
        info("Lade " + document.label() + " herunter …");
        after(session.download(document, DownloadOptions.quick()),
                file -> save(document, file), "Herunterladen gescheitert");
    }

    @Override
    public void downloadWithOptions(DocumentTab document) {
        after(session.downloadChoices(document), choices -> prompts.download(this, choices).ifPresent(options -> {
            info("Lade " + document.label() + " herunter …");
            // „Aktuelle Seite“ = die hier angezeigte; der Server kennt sie nach einem Wiederöffnen nicht mehr.
            DownloadOptions chosen = options.withCurrentPage(documents.shownPage(document));
            after(session.download(document, chosen), file -> save(document, file), "Herunterladen gescheitert");
        }), "Download-Dialog nicht lesbar");
    }

    private void save(DocumentTab document, DownloadedFile file) {
        String name = fileName(document.label()) + file.extension();
        prompts.saveAs(this, name).ifPresentOrElse(target -> {
            try {
                Files.write(target, file.data());
                info("Gespeichert: " + target);
            } catch (Exception e) {
                error("Speichern gescheitert – " + BetaViewSessions.describe(e));
            }
        }, () -> info("Herunterladen abgebrochen"));
    }

    @Override
    public void bookmark(DocumentTab document) {
        after(session.bookmarkProposal(document), proposal -> prompts.nameAndDescription(this,
                "Unter welchem Namen merken?", proposal, "Merken").ifPresent(entry -> {
                    after(session.bookmark(document, entry), done -> {
                        info("Gemerkt: " + entry.name());
                        if (search.bookmarksLoaded()) {
                            loadBookmarks();
                        }
                    }, "Merken gescheitert");
                }), "Merken nicht möglich");
    }

    @Override
    public void info(DocumentTab document) {
        after(session.properties(document), lines -> prompts.show(this, document.label(), lines),
                "Dokumentinformationen nicht lesbar");
    }

    @Override
    public void quickInfo(DocumentTab document, String headline,
                          java.util.List<com.aresstack.spaceharvester.deck.betaview.InfoLine> lines) {
        prompts.show(this, headline.isEmpty() ? document.label() : headline, lines);
    }

    @Override
    public void print(DocumentTab document) {
        after(session.printInfo(document), text -> prompts.showText(this, "Druckinformationen", text),
                "Druckinformationen nicht lesbar");
    }

    @Override
    public void notes(DocumentTab document) {
        info("Lade Notizen …");
        after(session.notes(document), text -> {
            documents.notes(document, text);
            info("Notizen geladen");
        }, "Notizen nicht lesbar", () -> documents.notes(document, ""));
    }

    @Override
    public void marks(DocumentTab document) {
        info("Lade markierte Bereiche …");
        after(session.markedRanges(document), text -> {
            documents.marks(document, text);
            info("Markierte Bereiche geladen");
        }, "Markierte Bereiche nicht lesbar", () -> documents.marks(document, ""));
    }

    @Override
    public void searchText(DocumentTab document, String query) {
        info("Suche „" + query + "“ im Dokument …");
        after(session.searchText(document, query), text -> {
            documents.searchResult(document, text);
            info("Volltextsuche fertig");
        }, "Volltextsuche gescheitert");
    }

    @Override
    public void close(DocumentTab document) {
        after(session.close(document), done -> {
            documents.removed(document);
            info("Geschlossen: " + document.label());
        }, "Schließen gescheitert");
    }

    @Override
    public void closeAll() {
        after(session.closeAll(), done -> {
            documents.clear();
            info("Alle Dokumente geschlossen");
        }, "Schließen gescheitert");
    }

    // ---- Statusbereich ----

    void info(String text) {
        status(text, StatusNote.Tone.PLAIN);
    }

    void error(String text) {
        status(text, StatusNote.Tone.ERROR);
    }

    private void status(String text, StatusNote.Tone tone) {
        status = new StatusNote(text == null || text.isBlank() ? " " : text, tone, null, null);
        onStatus.accept(status);
    }

    /** Der Stand dieser Sitzung, wie er in der Statuszeile steht. */
    StatusNote status() {
        return status;
    }

    /** Wer jeden neuen Stand erfährt (die Arbeitsfläche, auf dem EDT). */
    void onStatus(java.util.function.Consumer<StatusNote> listener) {
        onStatus = listener == null ? note -> { } : listener;
    }

    String statusText() {
        return status.text();
    }

    private <T> void after(CompletableFuture<T> future, Consumer<T> done, String failure) {
        after(future, done, failure, () -> { });
    }

    /** Reicht Ergebnis oder Fehler an den EDT weiter; Fehler stehen rot mit Grund im Statusbereich. */
    private <T> void after(CompletableFuture<T> future, Consumer<T> done, String failure, Runnable always) {
        future.whenComplete((value, error) -> SwingUtilities.invokeLater(() -> {
            if (error != null) {
                always.run();
                error(failure + " – " + BetaViewSessions.describe(error));
            } else {
                done.accept(value);
            }
        }));
    }

    /** Dateiname aus dem Titel: ohne Zeichen, die Windows nicht erlaubt. */
    static String fileName(String title) {
        String cleaned = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        return cleaned.isEmpty() ? "Dokument" : cleaned;
    }
}
