package com.aresstack.spaceharvester.app.ui.deck.betaview;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.deck.betaview.DocumentTab;
import com.aresstack.spaceharvester.deck.betaview.DownloadOptions;
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
        }

        @Override
        public void closeAll() {
        }
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
}
