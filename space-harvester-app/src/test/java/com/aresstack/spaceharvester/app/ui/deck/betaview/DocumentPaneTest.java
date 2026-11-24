package com.aresstack.spaceharvester.app.ui.deck.betaview;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.deck.betaview.DocumentTab;
import com.aresstack.spaceharvester.deck.betaview.DownloadOptions;
import com.aresstack.spaceharvester.deck.betaview.InfoLine;
import com.aresstack.spaceharvester.deck.betaview.OpenedDocument;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Dokumentansicht: Seitenwechsel mit Fehler, „Alle schließen“, „Aktuelle Seite“ beim Herunterladen. */
class DocumentPaneTest {

    private static final DocumentTab A = new DocumentTab("A1", "F1", "LA", "Lauf A", "", "open A", true);
    private static final DocumentTab B = new DocumentTab("B1", "F1", "LB", "Lauf B", "", "open B", false);

    /** Merkt sich, was die Ansicht von der Sitzung will. */
    private static final class Recording implements DocumentPane.Actions {
        final List<String> calls = new ArrayList<>();

        @Override
        public void open(DocumentTab document) {
            calls.add("open " + document.title());
        }

        @Override
        public void page(DocumentTab document, int page) {
            calls.add("page " + page);
        }

        @Override
        public void download(DocumentTab document) {
        }

        @Override
        public void downloadWithOptions(DocumentTab document) {
        }

        @Override
        public void bookmark(DocumentTab document) {
        }

        @Override
        public void info(DocumentTab document) {
        }

        @Override
        public void close(DocumentTab document) {
            calls.add("close " + document.title());
        }

        @Override
        public void closeAll() {
            calls.add("closeAll");
        }

        @Override
        public void quickInfo(DocumentTab document, String headline, List<InfoLine> lines) {
            calls.add("quickInfo " + headline + " " + lines.size());
        }

        @Override
        public void print(DocumentTab document) {
            calls.add("print " + document.title());
        }

        @Override
        public void notes(DocumentTab document) {
            calls.add("notes " + document.title());
        }

        @Override
        public void marks(DocumentTab document) {
            calls.add("marks " + document.title());
        }

        @Override
        public void searchText(DocumentTab document, String query) {
            calls.add("search " + document.title() + " " + query);
        }
    }

    @Test
    void theDrawersLoadNotesAndMarksAndSearchInTheShownDocument() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Recording actions = new Recording();
            DocumentPane pane = new DocumentPane(actions);
            pane.known(List.of(A, B));
            pane.opened(new OpenedDocument(A, List.of(A, B), 2, "Lauf A vom Montag",
                    List.of(new InfoLine("Seiten", "2"))));
            actions.calls.clear();

            pane.notesToggle.doClick();
            assertThat(pane.activeDrawer()).isEqualTo(DocumentPane.DRAWER_NOTES);
            assertThat(actions.calls).containsExactly("notes Lauf A");
            pane.notes(A, "Bitte prüfen");
            pane.notes(B, "gehört nicht hierher");
            assertThat(pane.notesText.getText()).isEqualTo("Bitte prüfen");
            // Zweites Öffnen lädt nicht erneut; Zuklappen und wieder Aufklappen auch nicht.
            pane.notesToggle.doClick();
            assertThat(pane.activeDrawer()).isEqualTo(DocumentPane.DRAWER_NONE);
            pane.notesToggle.doClick();
            assertThat(actions.calls).containsExactly("notes Lauf A");

            pane.marksToggle.doClick();
            assertThat(actions.calls).endsWith("marks Lauf A");
            pane.searchToggle.doClick();
            pane.searchField.setText("PRUEF");
            pane.searchField.postActionEvent();
            assertThat(actions.calls).endsWith("search Lauf A PRUEF");

            pane.print.doClick();
            pane.quickInfo.doClick();
            assertThat(actions.calls).endsWith("print Lauf A", "quickInfo Lauf A vom Montag 1");

            // Schließen macht das Kreuz im Reiter des Dokuments (Angelo, 24.11.2026), kein Knopf in der Leiste.
            com.aresstack.spaceharvester.app.ui.controls.ClosableTab tab = closableTab(pane);
            assertThat(tab).as("Reiter mit Kreuz").isNotNull();
            tab.cross().doClick();
            assertThat(actions.calls).endsWith("close Lauf A");
            assertThat(pane.footTools()).as("Werkzeuge für die Fußleiste").isNotNull();
            assertThat(java.util.Arrays.asList(((java.awt.Container) pane.footTools()).getComponents()))
                    .contains(pane.download, pane.searchToggle);
            // Schmal: die hinteren Werkzeuge gehen ins Menü, die Leiste bricht nicht um.
            var row = (com.aresstack.spaceharvester.app.ui.controls.OverflowRow) pane.footTools();
            row.setSize(row.getPreferredSize().width, 40);
            assertThat(row.hiddenParts()).isEmpty();
            row.setSize(300, 40);
            assertThat(row.hiddenParts()).contains(pane.searchToggle).doesNotContain(pane.refresh);
            pane.clear();
            assertThat(pane.activeDrawer()).isEqualTo(DocumentPane.DRAWER_NONE);
            assertThat(pane.notesText.getText()).isEmpty();
        });
    }

    @Test
    void aFailedPageKeepsTheLoadedOneAndCanBeRetried() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Recording actions = new Recording();
            DocumentPane pane = new DocumentPane(actions);
            pane.opened(new OpenedDocument(A, List.of(A), 3, "", List.of()));
            pane.page(A, 1, "SEITE EINS");
            pane.next.doClick();
            assertThat(actions.calls).endsWith("page 2");
            pane.pageFailed(A, 2);
            assertThat(pane.text.getText()).isEqualTo("SEITE EINS");
            assertThat(pane.pageField.getText()).isEqualTo("1");
            assertThat(pane.shownPage(A)).isEqualTo(1);
            pane.next.doClick();
            assertThat(actions.calls).endsWith("page 2", "page 2");
            pane.page(A, 2, "SEITE ZWEI");
            assertThat(pane.text.getText()).isEqualTo("SEITE ZWEI");
            assertThat(pane.shownPage(A)).isEqualTo(2);
        });
    }

    @Test
    void closingAllLoadsNothingElse() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Recording actions = new Recording();
            DocumentPane pane = new DocumentPane(actions);
            pane.known(List.of(A, B));
            pane.opened(new OpenedDocument(A, List.of(A, B), 1, "", List.of()));
            actions.calls.clear();
            pane.clear();
            assertThat(actions.calls).isEmpty();
            assertThat(pane.documents()).isEmpty();
            assertThat(pane.current()).isEmpty();
        });
    }

    @Test
    void currentPageBecomesAnExplicitRange() {
        DownloadOptions current = new DownloadOptions(DownloadOptions.Pages.CURRENT, 1, 1, null, null, null, "",
                false, null, null);
        DownloadOptions chosen = current.withCurrentPage(2);
        assertThat(chosen.pages()).isEqualTo(DownloadOptions.Pages.RANGE);
        assertThat(chosen.from()).isEqualTo(2);
        assertThat(chosen.to()).isEqualTo(2);
        assertThat(DownloadOptions.quick().withCurrentPage(2)).isEqualTo(DownloadOptions.quick());
    }

    private static com.aresstack.spaceharvester.app.ui.controls.ClosableTab closableTab(java.awt.Container root) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof com.aresstack.spaceharvester.app.ui.controls.ClosableTab tab) {
                return tab;
            }
            if (child instanceof java.awt.Container inner) {
                var found = closableTab(inner);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
