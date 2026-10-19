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
    void theButtonsOfTheTabsAreAcronymsIdentifiersRelationsGlossary() throws Exception {
        List<String> names = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            TermsView view = new TermsView(null);
            for (String name : List.of(TermsView.ACRONYMS, TermsView.IDENTIFIERS, TermsView.RELATIONS,
                    TermsView.GLOSSARY)) {
                names.add(view.tabs().button(name).getText());
            }
        });
        assertThat(names).containsExactly("Akronyme", "Bezeichner", "Beziehungen", "Fachglossar");
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
}
