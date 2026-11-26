package com.aresstack.spaceharvester.app.ui.content;

import org.junit.jupiter.api.Test;

import javax.swing.JTextArea;

import static org.assertj.core.api.Assertions.assertThat;

/** Der Live-Text ersetzt sich selbst, nie den Entwurf drumherum; vorläufiger Text ist hinterlegt. */
class LiveDictationRegionTest {

    @Test
    void replacesOnlyItsOwnTextAndHighlightsTheProvisionalRest() {
        JTextArea editor = new JTextArea("Bitte prüfen:und danach senden.");
        int at = editor.getText().indexOf(':') + 1;
        LiveDictationRegion live = LiveDictationRegion.open(editor, at, at);

        live.show("", "Erster");
        assertThat(editor.getText()).isEqualTo("Bitte prüfen: Erster und danach senden.");
        assertThat(editor.getHighlighter().getHighlights()).hasSize(1);

        live.show("Erster Satz.", "Zweiter");
        assertThat(editor.getText()).isEqualTo("Bitte prüfen: Erster Satz. Zweiter und danach senden.");
        assertThat(editor.getHighlighter().getHighlights()[0].getStartOffset())
                .isEqualTo(editor.getText().indexOf("Zweiter"));

        int caret = live.done("Erster Satz. Zweiter Satz.");
        assertThat(editor.getText()).isEqualTo("Bitte prüfen: Erster Satz. Zweiter Satz. und danach senden.");
        assertThat(caret).isEqualTo(editor.getText().indexOf(" und danach"));
        assertThat(editor.getHighlighter().getHighlights()).isEmpty();
    }

    @Test
    void discardingRemovesTheLiveTextAndFailureKeepsOnlyCommittedWords() {
        JTextArea editor = new JTextArea("Hallo");
        LiveDictationRegion live = LiveDictationRegion.open(editor, 5, 5);
        live.show("eins", "zwei");
        assertThat(editor.getText()).isEqualTo("Hallo eins zwei");
        assertThat(live.keepCommitted()).isEqualTo(editor.getText().length());
        assertThat(editor.getText()).isEqualTo("Hallo eins");

        LiveDictationRegion other = LiveDictationRegion.open(editor, 10, 10);
        other.show("", "drei");
        other.remove();
        assertThat(editor.getText()).isEqualTo("Hallo eins");
    }
}
