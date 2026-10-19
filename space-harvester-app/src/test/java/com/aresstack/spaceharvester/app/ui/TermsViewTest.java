package com.aresstack.spaceharvester.app.ui;

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
            TermsView view = new TermsView();
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
            TermsView view = new TermsView();
            for (String name : List.of(TermsView.ACRONYMS, TermsView.IDENTIFIERS, TermsView.RELATIONS,
                    TermsView.GLOSSARY)) {
                names.add(view.tabs().button(name).getText());
            }
        });
        assertThat(names).containsExactly("Akronyme", "Bezeichner", "Beziehungen", "Fachglossar");
    }
}
