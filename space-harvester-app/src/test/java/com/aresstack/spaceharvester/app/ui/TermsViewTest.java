package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.application.terms.TermKind;
import com.aresstack.spaceharvester.application.terms.TermQueries;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Der Bereich „Begriffe“ (#339): Reiter in der beschlossenen Reihenfolge, Verzeichnis mit Suchfeld. */
class TermsViewTest {

    @Test
    void theTabsComeInTheAgreedOrderAndTheDirectoryFiltersAllColumns() throws Exception {
        List<String> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            TermsView view = new TermsView(null);
            seen.add(view.tabs().selected());
            TermsView.Directory acronyms = view.acronyms();
            seen.add(acronyms.hint());
            acronyms.show(List.of(List.of("RSL", "Rückmeldeschlüssel", "28"),
                    List.of("API", "Application Programming Interface", "43")), TermsView.PENDING);
            acronyms.search().setText("programming");
            seen.add(acronyms.table().getRowCount() + " " + acronyms.table().getValueAt(0, 0));
            seen.add(acronyms.table().getColumnName(2));
        });
        assertThat(seen).containsExactly(TermsView.ACRONYMS, TermsView.PENDING, "1 API", "Fundstellen");
    }

    @Test
    void theTabsAreAcronymsIdentifiersRelationsSynonymsGlossaryGraph() throws Exception {
        List<String> names = new ArrayList<>();
        List<String> order = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            TermsView view = new TermsView(null);
            order.addAll(view.tabs().names());
            for (String name : order) {
                names.add(view.tabs().button(name).getText());
            }
        });
        assertThat(order).containsExactly(TermsView.ACRONYMS, TermsView.IDENTIFIERS, TermsView.RELATIONS,
                TermsView.SYNONYMS, TermsView.GLOSSARY, TermsView.GRAPH);
        assertThat(names).containsExactly("Akronyme", "Bezeichner", "Beziehungen", "Synonyme", "Fachglossar",
                "Graph");
    }

    @Test
    void synonymsReadAsTermSynonymKindOriginStatusAndOfferOnlyTheirKinds() throws Exception {
        Relation synonym = new Relation("r2", "Sachbearbeitung", "SYNONYM", "Fallbearbeitung", "REJECTED",
                "AI", 3);
        List<String> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            RelationTab synonyms = new TermsView(null).synonyms();
            synonyms.show(List.of(synonym), TermsView.NO_SYNONYMS);
            javax.swing.JTable table = synonyms.directory().table();
            for (int column = 0; column < table.getColumnCount(); column++) {
                seen.add(table.getColumnName(column) + "=" + table.getValueAt(0, column));
            }
            java.awt.Component cell = table.prepareRenderer(table.getCellRenderer(0, 3), 0, 3);
            seen.add(String.valueOf(((javax.swing.JLabel) cell).getHorizontalAlignment()
                    == javax.swing.SwingConstants.LEFT));
            seen.add(String.valueOf(TermsView.REJECTED_BACKGROUND.equals(cell.getBackground())));
            seen.add(String.valueOf(table.getRowSorter() instanceof javax.swing.table.TableRowSorter));
        });
        assertThat(seen).containsExactly("Begriff=Sachbearbeitung", "Synonym=Fallbearbeitung", "Art=Synonym",
                "Herkunft=KI", "Status=Abgelehnt", "true", "true", "true");
        assertThat(new TermsView(null).synonyms().dialogTypes()).containsExactly(
                com.aresstack.spaceharvester.application.terms.RelationType.SYNONYM,
                com.aresstack.spaceharvester.application.terms.RelationType.VARIANT);
    }

    @Test
    void aRowNamesExpansionsForAcronymsAndSpellingsForIdentifiers() {
        assertThat(TermsView.rows(List.of(
                new TermQueries.TermSummary("API", "api", TermKind.ACRONYM, 43, 12, 2, List.of("API"),
                        List.of("Application Programming Interface", "Automatische Prüfungs Instanz")),
                new TermQueries.TermSummary("RSL", "rsl", TermKind.ACRONYM, 3, 1, 1, List.of("RSL"), List.of()),
                new TermQueries.TermSummary("Sachbearbeitung", "sachbearbeitung", TermKind.NOUN, 5, 3, 1,
                        List.of("Sachbearbeitung", "sachbearbeitung"), List.of()))))
                .containsExactly(
                        List.of("API", "Application Programming Interface · Automatische Prüfungs Instanz", "43"),
                        List.of("RSL", "–", "3"),
                        List.of("Sachbearbeitung", "Sachbearbeitung · sachbearbeitung", "5"));
    }

    @Test
    void identifiersShowNoSpellingsColumnAndOccurrencesReadAsPlaces() {
        assertThat(new TermsView(null).identifiers().table().getColumnCount()).isEqualTo(2);
        assertThat(TermsView.identifierRows(List.of(new TermQueries.TermSummary("Sachbearbeitung",
                "sachbearbeitung", TermKind.NOUN, 5, 3, 1, List.of("Sachbearbeitung"), List.of()))))
                .containsExactly(List.of("Sachbearbeitung", "5", "sachbearbeitung", "NOUN"));
        assertThat(TermsView.occurrenceRows(List.of(new TermQueries.Occurrence("handbuch", "HB", "p1", "Start",
                TermKind.NOUN, "Sachbearbeitung", null, null, 4, 19, "1", "x", "v",
                new TermQueries.Snippet("Die ", "Sachbearbeitung", " prüft\n", true, false)))))
                .containsExactly(List.of("Start", "handbuch · HB", "…Die Sachbearbeitung prüft"));
    }

    @Test
    void aFilteredOccurrenceRowStillNamesItsOwnPage() throws Exception {
        List<TermQueries.Occurrence> hits = List.of(
                new TermQueries.Occurrence("q", "S", "p1", "Eins", TermKind.NOUN, "x", null, null, 0, 1, "1", "h",
                        "v", null),
                new TermQueries.Occurrence("q", "S", "p2", "Zwei", TermKind.NOUN, "x", null, null, 0, 1, "1", "h",
                        "v", null));
        int[] index = new int[1];
        SwingUtilities.invokeAndWait(() -> {
            TermsView.Directory list = new TermsView.Directory("Fundstellen", new String[]{"Seite", "Quelle", "K"});
            list.show(TermsView.indexedOccurrenceRows(hits), "");
            list.search().setText("Zwei");
            assertThat(list.table().getRowCount()).isEqualTo(1);
            index[0] = TermsView.occurrenceIndex(list.visibleRow(list.table().convertRowIndexToModel(0)));
        });
        assertThat(hits.get(index[0]).pageId()).isEqualTo("p2");
    }

    @Test
    void relationsReadAsWordsAndTheReviewButtonsFollowTheSelectedRelation() throws Exception {
        Relation proposed = new Relation("r1", "API", "ABBREVIATION", "Application Programming Interface",
                "PROPOSED", "RULE", 1);
        Relation confirmed = new Relation("r2", "Sachbearbeitung", "SYNONYM", "Fallbearbeitung", "CONFIRMED",
                "MANUAL", 3);
        List<String> seen = new ArrayList<>();
        List<String> reviewed = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            TermsView view = new TermsView(null);
            view.onReview((action, relation) -> reviewed.add(action + " " + relation.id()));
            view.showRelations(List.of(proposed, confirmed), TermsView.EMPTY);
            TermsView.Directory relations = view.relations();
            for (int column = 0; column < relations.table().getColumnCount(); column++) {
                seen.add(String.valueOf(relations.table().getValueAt(0, column)));
            }
            seen.add(enabled(view));
            relations.table().setRowSelectionInterval(0, 0);
            seen.add(enabled(view));
            view.reviewButtons().get(0).doClick();
            relations.table().setRowSelectionInterval(1, 1);
            seen.add(enabled(view));
            view.reviewButtons().get(1).doClick();
        });
        assertThat(seen).containsExactly("API", "Abkürzung für", "Application Programming Interface",
                "Vorgeschlagen", "Regel", "000", "111", "011");
        assertThat(reviewed).containsExactly(TermsView.CONFIRM + " r1", TermsView.REJECT + " r2");
    }

    private static String enabled(TermsView view) {
        StringBuilder flags = new StringBuilder();
        view.reviewButtons().forEach(button -> flags.append(button.isEnabled() ? '1' : '0'));
        return flags.toString();
    }

    @Test
    void thePagerMergesNounsAndIdentifiersAlphabeticallyAcrossCursors() throws Exception {
        java.util.Map<TermKind, List<List<String>>> pages = java.util.Map.of(
                TermKind.NOUN, List.of(List.of("alpha", "delta"), List.of("foxtrott")),
                TermKind.IDENTIFIER, List.of(List.of("bravo", "charlie", "echo")));
        List<String> calls = new ArrayList<>();
        TermsView.IdentifierPager pager = new TermsView.IdentifierPager((kind, cursor) -> {
            calls.add(kind + ":" + cursor);
            int index = cursor == null ? 0 : Integer.parseInt(cursor);
            List<List<String>> kindPages = pages.get(kind);
            List<TermQueries.TermSummary> terms = new ArrayList<>();
            for (String term : kindPages.get(index)) {
                terms.add(summary(term, kind));
            }
            return new TermQueries.TermList(terms, index + 1 < kindPages.size() ? String.valueOf(index + 1) : null);
        });
        List<String> first = pager.next(4).stream().map(TermQueries.TermSummary::normalized).toList();
        boolean moreAfterFirst = pager.hasMore();
        List<String> second = pager.next(4).stream().map(TermQueries.TermSummary::normalized).toList();
        assertThat(first).containsExactly("alpha", "bravo", "charlie", "delta");
        assertThat(moreAfterFirst).isTrue();
        assertThat(second).containsExactly("echo", "foxtrott");
        assertThat(pager.hasMore()).isFalse();
        assertThat(calls).containsExactly("NOUN:null", "IDENTIFIER:null", "NOUN:1");
    }

    @Test
    void lettersToggleAndAllClearsAndAppendedRowsCount() throws Exception {
        List<String> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            TermsView.Directory directory = new TermsView(null).identifiers();
            List<String> chosen = new ArrayList<>();
            directory.letters(letter -> chosen.add(String.valueOf(letter)));
            directory.letterButton("B").doClick();
            seen.add(directory.letter() + " " + directory.letterButton("B").isSelected() + " "
                    + directory.letterButton(TermsView.Directory.ALL).isSelected());
            directory.letterButton("B").doClick();
            seen.add(directory.letter() + " " + directory.letterButton(TermsView.Directory.ALL).isSelected());
            directory.letterButton("C").doClick();
            directory.letterButton(TermsView.Directory.ALL).doClick();
            seen.add(String.valueOf(directory.letter()));
            seen.add(String.join(",", chosen));
            directory.show(List.of(List.of("a", "-", "1")), TermsView.EMPTY);
            directory.more(true);
            directory.append(List.of(List.of("b", "-", "2")));
            seen.add(directory.table().getRowCount() + " " + directory.hint());
        });
        assertThat(seen).containsExactly("B true false", "null true", "null", "B,null,C,null",
                "2 2 Einträge · weitere laden beim Scrollen");
    }

    private static TermQueries.TermSummary summary(String term, TermKind kind) {
        return new TermQueries.TermSummary(term, term, kind, 1, 1, 1, List.of(term), List.of());
    }
}
