package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.application.passages.PagePassages;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Der Reiter „Absätze“ der Leseansicht (#354). */
class PassagesTabTest {

    @Test
    void currentPassagesAreNumberedWithTheirTopicAndAClickMarksThePassage() throws Exception {
        List<String> seen = new ArrayList<>();
        List<List<TermsTab.Span>> jumped = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            PassagesTab tab = new PassagesTab();
            tab.onJump(jumped::add);
            seen.add(tab.stateLabel());
            tab.show(PassagesTab.State.CURRENT, List.of(
                    new PassagesTab.Passage("Architektur und Komponenten", "Die Systemarchitektur …", 0, 120),
                    new PassagesTab.Passage("Authentifizierung und Berechtigungen", null, 120, 300)), false);
            seen.add(tab.stateLabel());
            seen.add(tab.mode());
            seen.addAll(tab.titles());
            tab.click(1);
            tab.show(PassagesTab.State.CURRENT, List.of(new PassagesTab.Passage("Einleitung", null, 0, 9)), true);
            seen.add(tab.mode());
            tab.show(PassagesTab.State.STALE, List.of(), false);
            seen.add(String.valueOf(tab.titles().size()));
            seen.add(tab.hint());
        });
        assertThat(seen).containsExactly("Nicht durchgeführt", "Aktuell", " ", "Architektur und Komponenten",
                "Authentifizierung und Berechtigungen", PassagesTab.STRUCTURAL, "0", PassagesTab.hint(PassagesTab.State.STALE, 0));
        assertThat(jumped).containsExactly(List.of(new TermsTab.Span(120, 300)));
    }

    @Test
    void thePreviewIsTheTextAfterTheTopicLineShortened() {
        String text = "Architektur und Komponenten. " + "Die Bausteine sprechen über REST. ".repeat(10);
        PagePassages.Passage passage = new PagePassages.Passage(0, 0, text.length(), "Architektur und Komponenten.",
                text, null, 0, 11, null, null, null);
        String preview = PassagesTab.preview(passage);
        assertThat(preview).startsWith("Die Bausteine sprechen über REST.").endsWith(" …");
        assertThat(preview.length()).isLessThanOrEqualTo(PassagesTab.PREVIEW_CHARS + 2);
    }
}
