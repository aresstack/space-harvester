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
            panel.selectSection(1);
            assertThat(panel.displayedText()).isEqualTo("Second page");
        });
    }
}
