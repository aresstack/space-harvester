package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.application.holdings.Selection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** K6: Bereich der Suche als Auswahl im Core und der Text für die Statuszeile. */
class LocalShareTest {

    @Test
    void withoutChoiceAllSearchedSourcesCount() {
        Selection area = LocalShare.selection(new java.util.LinkedHashSet<>(List.of("wiki", "ablage")), List.of());
        assertEquals(List.of(Selection.Root.source("wiki"), Selection.Root.source("ablage")), area.roots());
    }

    @Test
    void chosenSpacesOnlyUnderSearchedSourcesAndWholeSourceWins() {
        Selection area = LocalShare.selection(Set.of("wiki", "ablage"), List.of(
                new Explorer.Scope("wiki", "ALPHA"), new Explorer.Scope("wiki", "BETA"),
                new Explorer.Scope("ablage", "X"), new Explorer.Scope("ablage", null),
                new Explorer.Scope("abgehakt", "Y")));
        assertEquals(List.of(Selection.Root.source("ablage"), Selection.Root.space("wiki", "ALPHA"),
                Selection.Root.space("wiki", "BETA")), area.roots());
    }

    @Test
    void choiceOutsideSearchedSourcesFallsBackToSearched() {
        Selection area = LocalShare.selection(Set.of("wiki"), List.of(new Explorer.Scope("abgehakt", "Y")));
        assertEquals(List.of(Selection.Root.source("wiki")), area.roots());
    }

    @Test
    void nothingSearchedIsEmpty() {
        assertEquals(List.of(), LocalShare.selection(Set.of(), List.of()).roots());
    }

    @Test
    void lineNamesStoredOfAll() {
        assertEquals("1.204 von 3.512 Seiten des Bereichs sind lokal gespeichert", LocalShare.line(1204, 3512));
        assertEquals("0 von 1 Seite des Bereichs ist lokal gespeichert", LocalShare.line(0, 1));
        assertEquals("", LocalShare.line(0, 0));
    }
}
