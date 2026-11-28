package com.aresstack.spaceharvester.app.ui.proving;

import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.provingground.model.TestCaseEntry;
import com.aresstack.spaceharvester.provingground.model.TestProject;
import com.aresstack.spaceharvester.provingground.model.TestSuite;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.DefaultListModel;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JPanel;

/**
 * Die Vorbedingungen einer Ebene: Testfälle, die vorher gespielt werden (WD4J). Oben wählt man einen Testfall des
 * Projekts und fügt ihn mit ＋ an; ▲ ▼ ordnen, der Papierkorb entfernt. Gezeigt wird „Suite › Testfall“, gespeichert
 * der Verweis {@code <suite>/<fall>}.
 */
final class PreconditionsEditor extends JPanel {

    static final String ADD_TIP = "Gewählten Testfall als Vorbedingung anfügen";
    static final String UP_TIP = "Vorbedingung nach oben";
    static final String DOWN_TIP = "Vorbedingung nach unten";
    static final String DELETE_TIP = "Vorbedingung entfernen";

    private final DefaultListModel<Item> items = new DefaultListModel<>();
    private final JList<Item> list = new JList<>(items);
    private final JComboBox<Item> choices = Comic.combo();
    private final IconButton add = new IconButton(ComposerIcons.plus(12), ADD_TIP).compact();
    private final IconButton up = new IconButton(ComposerIcons.chevronUp(), UP_TIP).compact();
    private final IconButton down = new IconButton(ComposerIcons.chevronDown(), DOWN_TIP).compact();
    private final IconButton delete = new IconButton(Glyph.TRASH, DELETE_TIP).compact();
    private Consumer<List<String>> changed = references -> { };

    PreconditionsEditor() {
        super(new BorderLayout(0, 4));
        setOpaque(false);
        list.setFont(Comic.regular(13f));
        list.setSelectionBackground(Comic.YELLOW);
        list.setSelectionForeground(Comic.INK);
        list.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        add.addActionListener(event -> {
            Item chosen = (Item) choices.getSelectedItem();
            if (chosen != null && !references().contains(chosen.reference())) {
                items.addElement(chosen);
                publish();
            }
        });
        up.addActionListener(event -> move(-1));
        down.addActionListener(event -> move(1));
        delete.addActionListener(event -> {
            int index = list.getSelectedIndex();
            if (index >= 0) {
                items.remove(index);
                publish();
            }
        });
        JPanel top = new JPanel(new BorderLayout(4, 0));
        top.setOpaque(false);
        top.add(choices, BorderLayout.CENTER);
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        tools.setOpaque(false);
        tools.add(add);
        tools.add(up);
        tools.add(down);
        tools.add(delete);
        top.add(tools, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);
        add(Comic.scroll(list), BorderLayout.CENTER);
    }

    /**
     * @param project    das Projekt (seine Testfälle sind die Auswahl)
     * @param references die Vorbedingungen der Ebene
     * @param self       der Verweis des gezeigten Testfalls (nicht wählbar), oder null
     * @param listener   bekommt nach jeder Änderung die neue Liste
     */
    void show(TestProject project, List<String> references, String self, Consumer<List<String>> listener) {
        changed = listener;
        items.clear();
        choices.removeAllItems();
        for (TestSuite suite : project.suites()) {
            for (TestCaseEntry testCase : suite.cases()) {
                String reference = TestProject.reference(suite, testCase);
                if (!reference.equals(self)) {
                    choices.addItem(new Item(reference, suite.name() + " › " + testCase.name()));
                }
            }
        }
        for (String reference : references) {
            items.addElement(new Item(reference, label(project, reference)));
        }
    }

    /** „Suite › Testfall“, oder der Verweis mit Hinweis, wenn es ihn nicht mehr gibt. */
    static String label(TestProject project, String reference) {
        int slash = reference.indexOf('/');
        TestSuite suite = slash < 0 ? null : project.suite(reference.substring(0, slash));
        TestCaseEntry testCase = project.testCase(reference);
        return suite == null || testCase == null ? reference + " (unbekannt)" : suite.name() + " › " + testCase.name();
    }

    List<String> references() {
        List<String> references = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            references.add(items.get(i).reference());
        }
        return references;
    }

    JComboBox<Item> choices() {
        return choices;
    }

    void addChosen() {
        add.doClick();
    }

    private void move(int delta) {
        int index = list.getSelectedIndex();
        if (index < 0 || index + delta < 0 || index + delta >= items.size()) {
            return;
        }
        Item item = items.remove(index);
        items.add(index + delta, item);
        list.setSelectedIndex(index + delta);
        publish();
    }

    private void publish() {
        changed.accept(references());
    }

    /** Ein Eintrag: Verweis und Anzeige. */
    record Item(String reference, String label) {
        @Override
        public String toString() {
            return label;
        }
    }
}
