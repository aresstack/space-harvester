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
                    new PassagesTab.Passage("p0", "Architektur und Komponenten", "Die Systemarchitektur …", 0, 120),
                    new PassagesTab.Passage("p1", "Authentifizierung und Berechtigungen", null, 120, 300)), false);
            seen.add(tab.stateLabel());
            seen.add(tab.mode());
            seen.addAll(tab.titles());
            tab.click(1);
            tab.show(PassagesTab.State.CURRENT, List.of(new PassagesTab.Passage("p0", "Einleitung", null, 0, 9)), true);
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
    void aPassageIsSelectedByItsIdAndNewPassagesDropTheSelection() throws Exception {
        List<Object> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            PassagesTab tab = new PassagesTab();
            tab.show(PassagesTab.State.CURRENT, List.of(new PassagesTab.Passage("p0", "Einleitung", null, 0, 9),
                    new PassagesTab.Passage("p1", "Betrieb", null, 9, 20)), false);
            seen.add(tab.select("p1"));
            seen.add(tab.selectedIndex());
            seen.add(String.valueOf(tab.select("unbekannt")));
            seen.add(tab.selectedIndex());
            tab.select("p0");
            tab.show(PassagesTab.State.CURRENT, List.of(new PassagesTab.Passage("p0", "Einleitung", null, 0, 9)),
                    false);
            seen.add(tab.selectedIndex());
        });
        assertThat(seen).containsExactly(new PassagesTab.Passage("p1", "Betrieb", null, 9, 20), 1, "null", -1, -1);
    }

    /** Codex zu #573: Themenzeile und Anriss brechen um und bleiben trotzdem anklickbar. */
    @Test
    void longTitlesWrapAndStayClickable() throws Exception {
        List<List<TermsTab.Span>> jumped = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            PassagesTab tab = new PassagesTab();
            tab.onJump(jumped::add);
            tab.show(PassagesTab.State.CURRENT, List.of(new PassagesTab.Passage("p0",
                    "Eine sehr lange Themenzeile ".repeat(8), "Anriss ".repeat(60), 0, 50)), false);
            tab.setSize(360, 400);
            tab.addNotify();
            tab.validate();
            tab.validate();
            java.awt.Container row = (java.awt.Container) tab.rowForTest(0);
            assertThat(row.getWidth()).isLessThanOrEqualTo(360);
            java.awt.Container lines = (java.awt.Container) row.getComponent(1);
            java.awt.Component title = lines.getComponent(0);
            assertThat(title.getHeight()).as("die Themenzeile bricht um").isGreaterThan(30);
            java.awt.event.MouseEvent click = new java.awt.event.MouseEvent(title,
                    java.awt.event.MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, 1, 1, false,
                    java.awt.event.MouseEvent.BUTTON1);
            for (java.awt.event.MouseListener listener : title.getMouseListeners()) {
                listener.mouseClicked(click);
            }
        });
        assertThat(jumped).containsExactly(List.of(new TermsTab.Span(0, 50)));
    }

    @Test
    void thePreviewIsTheTextAfterTheTopicLineShortened() {
        String text = "Architektur und Komponenten. " + "Die Bausteine sprechen über REST. ".repeat(10);
        PagePassages.Passage passage = new PagePassages.Passage("p0", 0, 0, text.length(),
                "Architektur und Komponenten.", text, null, 0, 11, null, null, null);
        String preview = PassagesTab.preview(passage);
        assertThat(preview).startsWith("Die Bausteine sprechen über REST.").endsWith(" …");
        assertThat(preview.length()).isLessThanOrEqualTo(PassagesTab.PREVIEW_CHARS + 2);
    }

    @Test
    void theRecomputeButtonFollowsAcronymsAndPassages() {
        assertThat(PageDetailPanel.recomputeOffered(com.aresstack.spaceharvester.application.enrichment.PageAcronyms
                .State.CURRENT, PagePassages.State.STALE)).isTrue();
        assertThat(PageDetailPanel.recomputeOffered(com.aresstack.spaceharvester.application.enrichment.PageAcronyms
                .State.CURRENT, PagePassages.State.FAILED)).isTrue();
        assertThat(PageDetailPanel.recomputeOffered(com.aresstack.spaceharvester.application.enrichment.PageAcronyms
                .State.CURRENT, PagePassages.State.CURRENT)).isFalse();
        assertThat(PageDetailPanel.recomputeOffered(com.aresstack.spaceharvester.application.enrichment.PageAcronyms
                .State.STALE, PagePassages.State.CURRENT)).isTrue();
    }

    @Test
    void titleAndPreviewCarryTheClickOfTheirRow() throws Exception {
        // Themenzeile und Anriss sind umbrechende Textfelder, die Mausklicks selbst abfangen; deshalb tragen sie den
        // Klick der Zeile selbst (Codex zu #573).
        List<Integer> listeners = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            PassagesTab tab = new PassagesTab();
            tab.show(PassagesTab.State.CURRENT, List.of(new PassagesTab.Passage("p0", "Einleitung", "Anriss", 0, 9)), false);
            java.awt.Container row = (java.awt.Container) tab.rowForTest(0);
            listeners.add(row.getMouseListeners().length);
            collect(row, listeners);
        });
        assertThat(listeners.get(0)).isPositive();
        assertThat(listeners.subList(1, listeners.size())).as("Titel und Anriss").hasSize(2).allMatch(n -> n > 0);
    }

    private static void collect(java.awt.Container parent, List<Integer> into) {
        for (java.awt.Component child : parent.getComponents()) {
            if (child instanceof javax.swing.text.JTextComponent) {
                into.add(child.getMouseListeners().length);
            }
            if (child instanceof java.awt.Container container) {
                collect(container, into);
            }
        }
    }
}
