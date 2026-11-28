package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.PillTabs;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.util.List;
import java.util.Map;

/**
 * Die eine Reiterzeile der Analysen für jede Ebene (Angelo 27.11.2026: „Das muss polymorph funktionieren, nicht
 * überall verschiedener Code“). Eine Seite und ein oberer Knoten (Quelle, Space, Ordner) haben teils dieselben, teils
 * eigene Reiter; die Zeile kennt alle in einer festen Reihenfolge mit einem Symbol je Name und zeigt die, die die
 * gerade gezeigte Ebene anbietet. Eine Ebene ist ihr Satz Ansichten ({@link PillTabs}, ohne eigene Leiste): Die Zeile
 * fragt sie nur nach ihren Namen und ihrer Wahl und wählt in ihr, ohne zu wissen, welche Ebene es ist.
 *
 * <p>Jede Ebene merkt sich ihren gewählten Reiter selbst; die Zeile zeigt ihn. Wählt die Ebene selbst (ein Sprung,
 * ein Programm mit eigenen Reitern), zieht die Zeile nach. Nur auf dem EDT.
 */
final class AnalysisTabRow {

    private final PillTabs row;
    private final JComponent bar;
    private PillTabs level;
    private boolean syncing;
    private Runnable onChange = () -> { };

    /** @param icons alle bekannten Reiter in der Reihenfolge der Zeile, je mit ihrem Symbol */
    AnalysisTabRow(Map<String, Icon> icons) {
        // Dieselben Umschalter wie in jeder Fußleiste ({@link com.aresstack.spaceharvester.app.ui.FootTabs}): Die
        // Leiste bleibt überall gleich hoch, Reiter ohne Platz stehen hinter dem Menü am Ende der Reihe.
        com.aresstack.spaceharvester.app.ui.FootTabs foot = new com.aresstack.spaceharvester.app.ui.FootTabs(icons);
        row = foot.tabs();
        bar = foot.bar();
        row.onSelect(name -> {
            if (!syncing && level != null && level.button(name) != null && !name.equals(level.selected())) {
                level.select(name);
            }
        });
    }

    /**
     * Meldet, dass eine andere Ansicht zu sehen ist: ein anderer Reiter oder dieselben Reiter einer anderen Ebene
     * (Seite statt Knoten). Die Fußleiste räumt dann Suche und Filter der alten Ansicht.
     */
    void onChange(Runnable listener) {
        onChange = java.util.Objects.requireNonNull(listener, "listener");
    }

    /** Eine Ebene anmelden: Wählt sie selbst einen Reiter, solange sie gezeigt wird, zieht die Zeile nach. */
    void follow(PillTabs tabs) {
        tabs.alsoOnSelect(name -> {
            if (tabs == level) {
                refresh();
            }
        });
    }

    /** Zeigt die Reiter dieser Ebene; {@code null} zeigt keine. */
    void level(PillTabs tabs) {
        if (level == tabs) {
            refresh();
            return;
        }
        level = tabs;
        refresh();
    }

    /** Gleicht die Zeile mit der Ebene ab: welche Reiter sie anbietet und welcher gewählt ist. */
    void refresh() {
        bar.setVisible(level != null);
        if (level == null || syncing) {
            return;
        }
        syncing = true;
        try {
            List<String> names = level.names();
            // Reiter, die erst später entstehen (Abschnitte eines Programms), hinten anfügen; sie behalten ihren Text.
            names.stream().filter(name -> row.button(name) == null).forEach(name -> row.tab(name, new JPanel()));
            if (!names.isEmpty()) {
                row.showOnly(names);
            }
            String chosen = level.selected();
            if (chosen != null && !chosen.equals(row.selected())) {
                row.select(chosen);
            }
        } finally {
            syncing = false;
        }
        onChange.run();
    }

    /** Eine kurze Marke hinter dem Symbol eines Reiters, etwa die Zahl der Widersprüche; {@code null} nimmt sie weg. */
    void mark(String name, String mark) {
        row.mark(name, mark);
    }

    /** Die Leiste mit den Symbolknöpfen für den Fuß der Seitenleiste. */
    JComponent bar() {
        return bar;
    }

    /** Für Tests und Abfragen: die Zeile selbst. */
    PillTabs tabs() {
        return row;
    }
}
