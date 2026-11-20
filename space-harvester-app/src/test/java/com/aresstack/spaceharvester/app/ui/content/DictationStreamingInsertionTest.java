package com.aresstack.spaceharvester.app.ui.content;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Abschnittsweise eingefügter Diktattext ergibt denselben Entwurf wie der ganze Text auf einmal. */
class DictationStreamingInsertionTest {

    @Test
    void insertingWindowsOneAfterAnotherEqualsInsertingTheWholeTranscript() {
        String draft = "Bitte prüfen: und danach senden.";
        int cursor = draft.indexOf(':') + 1;
        String[] windows = {"Erster Absatz endet hier.", "Zweiter Absatz,", "dritter Satz."};

        DictationInsertion streamed = DictationInsertion.insert(draft, cursor, cursor, windows[0]);
        for (int i = 1; i < windows.length; i++) {
            streamed = DictationInsertion.insert(streamed.text, streamed.caret, streamed.caret, windows[i]);
        }
        DictationInsertion whole = DictationInsertion.insert(draft, cursor, cursor, String.join(" ", windows));

        assertThat(streamed.text).isEqualTo(whole.text);
        assertThat(streamed.caret).isEqualTo(whole.caret);
        assertThat(streamed.text)
                .isEqualTo("Bitte prüfen: Erster Absatz endet hier. Zweiter Absatz, dritter Satz. und danach senden.");
    }
}
