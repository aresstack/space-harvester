package com.aresstack.spaceharvester.app.ui.deck.betaview;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.deck.betaview.Bookmark;
import com.aresstack.spaceharvester.deck.betaview.FilterPatterns;
import com.aresstack.spaceharvester.deck.betaview.ResultTable;
import com.aresstack.spaceharvester.deck.betaview.SavedSearch;
import com.aresstack.spaceharvester.deck.betaview.SearchFilter;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Die Auswahllisten der Suche (wie MainframeMate) ergeben die Werte, die zum Server gehen. */
class SearchPaneTest {

    private static final class Quiet implements SearchPane.Actions {
        @Override
        public void search(SearchFilter filter) {
        }

        @Override
        public void saveSearch() {
        }

        @Override
        public void loadSaved() {
        }

        @Override
        public void runSaved(SavedSearch search) {
        }

        @Override
        public void loadBookmarks() {
        }

        @Override
        public void openBookmark(Bookmark bookmark) {
        }

        @Override
        public void open(ResultTable.Row row, Object origin) {
        }

        @Override
        public void problem(String message) {
        }
    }

    @Test
    void theListsBuildTheServerValues() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SearchPane pane = new SearchPane(new Quiet());
            pane.favorite.setText("F001");
            pane.form.apply("ATAB");
            pane.extension.mode.setSelectedItem(FilterPatterns.ExtensionMode.BK_BANK);
            pane.extension.bankNumber.setText("18");
            pane.extension.bankSuffix.setText("*");
            pane.report.mode.setSelectedItem(FilterPatterns.ReportMode.BA);
            PatternFields.select(pane.report.baList, "BA002");
            PatternFields.select(pane.report.baType, "V");
            PatternFields.select(pane.jobName.box, FilterPatterns.CUSTOM);
            pane.jobName.custom.setText("APABXYZ1");
            pane.folder.apply("*");
            pane.recipient.apply("POST1");

            SearchFilter filter = pane.filter();
            assertThat(filter.form()).isEqualTo("ATAB");
            assertThat(filter.extension()).isEqualTo("BK18BANK*");
            assertThat(filter.report()).isEqualTo("BA002V");
            assertThat(filter.jobName()).isEqualTo("APABXYZ1");
            assertThat(filter.folder()).isEqualTo("*");
            assertThat(filter.recipient()).isEqualTo("POST1");
            assertThat(pane.recipient.custom.isVisible()).isTrue();
            assertThat(pane.folder.custom.isVisible()).isFalse();
        });
    }

    @Test
    void storedValuesComeBackIntoTheRightList() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SearchPane pane = new SearchPane(new Quiet());
            pane.extension.apply("B4711F13LB");
            pane.report.apply("K9999BA101E");
            assertThat(pane.extension.mode.getSelectedItem()).isEqualTo(FilterPatterns.ExtensionMode.BEW_LB);
            assertThat(pane.extension.value()).isEqualTo("B4711F13LB");
            assertThat(pane.report.mode.getSelectedItem()).isEqualTo(FilterPatterns.ReportMode.K9999);
            assertThat(pane.report.value()).isEqualTo("K9999BA101E");
        });
    }
}
