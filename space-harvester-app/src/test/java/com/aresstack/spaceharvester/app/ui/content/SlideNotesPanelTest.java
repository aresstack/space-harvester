package com.aresstack.spaceharvester.app.ui.content;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Angelo 20.11.2026: Unter dem Standbild einer Marke die Folienkommentare, je Modell eine Fassung zum Umschalten. */
final class SlideNotesPanelTest {

    @Test
    void theNotesOfAMarkSwitchByModel() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SlideNotesPanel panel = new SlideNotesPanel();
            assertThat(panel.shownText()).isEqualTo(SlideNotesPanel.NONE);

            panel.show(List.of(new SlideNotesPanel.Version("Kommentar a", "Text a"),
                    new SlideNotesPanel.Version("Kommentar b", "Text b")));
            assertThat(panel.buttons()).extracting(button -> button.getText())
                    .containsExactly("Kommentar a", "Kommentar b");
            assertThat(panel.shownText()).isEqualTo("Text a");
            panel.buttons().get(1).doClick();
            assertThat(panel.shownText()).isEqualTo("Text b");
        });
    }
}
