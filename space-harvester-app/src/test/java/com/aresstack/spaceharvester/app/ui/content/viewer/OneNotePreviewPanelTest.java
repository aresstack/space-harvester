package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.aresstack.spaceharvester.application.assets.NotePageLocation;
import com.aresstack.spaceharvester.application.assets.OneNotePreview;
import com.aresstack.spaceharvester.application.assets.Outcome;
import com.aresstack.spaceharvester.application.assets.ExtractionStatus;
import com.aresstack.spaceharvester.application.assets.StatusReason;
import java.util.List;
import java.util.Optional;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OneNotePreviewPanelTest {
    @Test
    void recognizeSectionFiles() {
        assertThat(ReaderAttachmentKind.of("application/octet-stream", "notebook.one"))
                .isEqualTo(ReaderAttachmentKind.NOTEBOOK);
        assertThat(ReaderAttachmentKind.of("application/onenote; format=one", "file"))
                .isEqualTo(ReaderAttachmentKind.NOTEBOOK);
    }

    @Test
    void commandDeckRoutesOneNoteToReadOnlySharedViewer() {
        assertThat(com.aresstack.spaceharvester.app.deck.DocumentPreviewStore.isDocument("Notes.ONE")).isTrue();
        assertThat(new com.aresstack.spaceharvester.app.deck.DocumentPreviewStore().writable()).isFalse();
        assertThat(com.aresstack.spaceharvester.app.ui.deck.DeckFileViewer.shows("Notes.ONE")).isTrue();
    }

    @Test
    void explainInsufficientTemporarySpace() {
        Outcome outcome = Outcome.of(ExtractionStatus.FAILED, StatusReason.TEMP_SPACE_LOW);
        assertThat(OneNotePreviewPanel.unavailableMessage(outcome))
                .contains("Speicherplatz", "temporäre Dateien", "temp-space-low");
    }

    @Test
    void showUnassignedTextWithoutInventingPages() throws Exception {
        OneNotePreview preview = new OneNotePreview(List.of(
                new OneNotePreview.Section(Optional.empty(), "Legacy content")), Outcome.ok(null));
        assertThat(preview.hasPageBoundaries()).isFalse();
        SwingUtilities.invokeAndWait(() -> {
            OneNotePreviewPanel panel = new OneNotePreviewPanel(preview);
            assertThat(panel.sectionCount()).isEqualTo(1);
            assertThat(panel.displayedText()).isEqualTo("Legacy content");
            assertThat(panel.getComponent(0))
                    .isInstanceOf(com.aresstack.enterpriseai.ui.comic.control.ComicScrollPane.class);
        });
    }

    @Test
    void selectExtractedPages() throws Exception {
        OneNotePreview preview = new OneNotePreview(List.of(
                new OneNotePreview.Section(Optional.of(new NotePageLocation(1)), "First page"),
                new OneNotePreview.Section(Optional.of(new NotePageLocation(2)), "Second page")),
                Outcome.ok(null));
        SwingUtilities.invokeAndWait(() -> {
            OneNotePreviewPanel panel = new OneNotePreviewPanel(preview);
            assertThat(panel.sectionCount()).isEqualTo(2);
            assertThat(panel.displayedText()).isEqualTo("First page");
            assertThat(panel.getComponent(0))
                    .isInstanceOf(com.aresstack.enterpriseai.ui.comic.control.ComicSplitPane.class);
            javax.swing.JSplitPane split = (javax.swing.JSplitPane) panel.getComponent(0);
            assertThat(split.getLeftComponent())
                    .isInstanceOf(com.aresstack.enterpriseai.ui.comic.control.ComicScrollPane.class);
            assertThat(split.getRightComponent())
                    .isInstanceOf(com.aresstack.enterpriseai.ui.comic.control.ComicScrollPane.class);
            javax.swing.JList<?> list = (javax.swing.JList<?>) ((javax.swing.JScrollPane)
                    split.getLeftComponent()).getViewport().getView();
            javax.swing.Action next = list.getActionMap().get(list.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                    .get(javax.swing.KeyStroke.getKeyStroke("RIGHT")));
            javax.swing.Action previous = list.getActionMap().get(list.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                    .get(javax.swing.KeyStroke.getKeyStroke("LEFT")));
            assertThat(next).isNotNull();
            assertThat(previous).isNotNull();
            next.actionPerformed(new java.awt.event.ActionEvent(list, 0, "next"));
            assertThat(panel.displayedText()).isEqualTo("Second page");
            next.actionPerformed(new java.awt.event.ActionEvent(list, 0, "next"));
            assertThat(panel.displayedText()).isEqualTo("Second page");
            previous.actionPerformed(new java.awt.event.ActionEvent(list, 0, "previous"));
            assertThat(panel.displayedText()).isEqualTo("First page");
        });
    }
}
