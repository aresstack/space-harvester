package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.terms.SynonymDiscovery;
import com.aresstack.spaceharvester.application.terms.SynonymScope;
import com.aresstack.spaceharvester.app.ui.controls.WrappedText;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;

/**
 * Das Formular hinter „Synonyme vorschlagen“ (#336): Umfang (alle Quellen, eine Quelle, ein Space), höchstens so viele
 * Paare an das Chat-Modell (vorbelegt aus {@code terms.synonyms.max-judged-pairs}, 0 heißt nur Regelvorschläge) und
 * ein gedämpfter, umbrechender Hinweis, der vor dem Start sagt, was der Lauf im gewählten Umfang kann: Chat-Modell ja
 * oder nein, welche Quellen freigegeben sind. Reihenfolge = Entscheidungsfluss: Umfang → Budget → Erläuterung. Der
 * Hinweis kommt aus dem Hintergrund, nachdem der Dialog gepackt ist; deshalb wächst das Fenster mit ihm nach.
 */
final class SynonymRunForm extends JPanel {

    static final String ALL_SOURCES = "Alle Quellen";
    static final String WHOLE_SOURCE = "Ganze Quelle";
    static final String NO_MODEL = "Kein Chat-Modell eingestellt: nur Regelvorschläge (Schreibvarianten, "
            + "Formulierungen im Text).";
    static final String MODEL = "Chat-Modell eingestellt: Kandidatenpaare werden mit kurzen Fundstellen geprüft.";
    static final int MAX_PAIRS = 100_000;

    private final JComboBox<String> source = Comic.combo();
    private final JComboBox<String> space = Comic.combo();
    private final JSpinner pairs;
    private final WrappedText status = Comic.hint(" ");
    private final List<HarvestSource> sources;
    private final Function<String, List<Partition>> spaces;
    private final Function<SynonymScope, SynonymDiscovery.Availability> availability;
    private List<Partition> partitions = List.of();
    private boolean loadingSpaces;

    /**
     * @param sources      die Quellen zur Wahl, in Katalogreihenfolge
     * @param spaces       Spaces einer Quelle; läuft im Hintergrund
     * @param availability was der Lauf im Umfang könnte; läuft im Hintergrund
     * @param maxJudgedPairs Vorbelegung des Budgets
     */
    SynonymRunForm(List<HarvestSource> sources, Function<String, List<Partition>> spaces,
                   Function<SynonymScope, SynonymDiscovery.Availability> availability, int maxJudgedPairs) {
        super(new BorderLayout(0, 8));
        setOpaque(false);
        JPanel fields = new JPanel(new GridLayout(0, 1, 0, 4));
        fields.setOpaque(false);
        this.sources = List.copyOf(sources);
        this.spaces = spaces;
        this.availability = availability;
        pairs = new JSpinner(new SpinnerNumberModel(Math.min(Math.max(maxJudgedPairs, 0), MAX_PAIRS), 0, MAX_PAIRS,
                10));
        pairs.setToolTipText("Höchstens so viele Kandidatenpaare gehen an das Chat-Modell; 0 heißt nur "
                + "Regelvorschläge");
        source.addItem(ALL_SOURCES);
        for (HarvestSource entry : this.sources) {
            source.addItem(label(entry));
        }
        space.addItem(WHOLE_SOURCE);
        space.setEnabled(false);
        fields.add(Comic.caption("Umfang"));
        fields.add(source);
        fields.add(Comic.caption("Space"));
        fields.add(space);
        fields.add(Comic.caption("Höchstens Paare an das Chat-Modell"));
        fields.add(pairs);
        add(fields, BorderLayout.NORTH);
        add(status, BorderLayout.CENTER);
        source.addActionListener(e -> sourceChanged());
        space.addActionListener(e -> {
            if (!loadingSpaces) {
                describe();
            }
        });
        describe();
    }

    private static String label(HarvestSource source) {
        String title = source.title();
        return title == null || title.isBlank() || title.equals(source.id()) ? source.id()
                : title + " (" + source.id() + ")";
    }

    private void sourceChanged() {
        loadingSpaces = true;
        space.removeAllItems();
        space.addItem(WHOLE_SOURCE);
        partitions = List.of();
        loadingSpaces = false;
        String sourceId = sourceId();
        if (sourceId == null) {
            space.setEnabled(false);
            describe();
            return;
        }
        space.setEnabled(true);
        describe();
        Background.run(() -> spaces.apply(sourceId), found -> {
            if (!sourceId.equals(sourceId())) {
                return;
            }
            loadingSpaces = true;
            partitions = List.copyOf(found);
            for (Partition partition : partitions) {
                space.addItem(partition.name() == null || partition.name().isBlank() ? partition.key()
                        : partition.name() + " (" + partition.key() + ")");
            }
            loadingSpaces = false;
        }, failure -> { });
    }

    /** Gewählte Quelle oder {@code null} für alle. */
    String sourceId() {
        int index = source.getSelectedIndex();
        return index <= 0 ? null : sources.get(index - 1).id();
    }

    /** Gewählter Space oder {@code null} für die ganze Quelle. */
    String spaceKey() {
        int index = space.getSelectedIndex();
        return index <= 0 || index > partitions.size() ? null : partitions.get(index - 1).key();
    }

    SynonymScope scope() {
        String sourceId = sourceId();
        if (sourceId == null) {
            return SynonymScope.ALL;
        }
        String key = spaceKey();
        return key == null ? SynonymScope.source(sourceId) : SynonymScope.space(sourceId, key);
    }

    SynonymDiscovery.Request request() {
        return new SynonymDiscovery.Request(scope(), (Integer) pairs.getValue());
    }

    int maxJudgedPairs() {
        return (Integer) pairs.getValue();
    }

    String statusText() {
        return status.getText();
    }

    JComboBox<String> sourceBox() {
        return source;
    }

    JComboBox<String> spaceBox() {
        return space;
    }

    private void describe() {
        SynonymScope scope = scope();
        Background.run(() -> availability.apply(scope), found -> {
            if (scope.equals(scope())) {
                show(describe(found));
            }
        }, failure -> show("Umfang nicht prüfbar: " + failure.getClass().getSimpleName()));
    }

    /** Der Hinweis kommt nach dem Packen des Dialogs; das Fenster wächst mit, bis der ganze Text zu lesen ist. */
    private void show(String text) {
        status.setText(text);
        Window window = SwingUtilities.getWindowAncestor(this);
        if (window == null || !window.isShowing()) {
            return;
        }
        status.revalidate();
        int needed = window.getPreferredSize().height;
        if (needed > window.getHeight()) {
            window.setSize(window.getWidth(), needed);
            window.validate();
        }
    }

    /** Der Hinweis unter dem Formular, als Text mit Zeilenumbrüchen: Modell, freigegebene und zurückgehaltene Quellen. */
    static String describe(SynonymDiscovery.Availability availability) {
        List<String> parts = new ArrayList<>();
        parts.add(availability.chatModel() ? MODEL : NO_MODEL);
        if (!availability.released().isEmpty()) {
            parts.add("Freigegeben: " + String.join(", ", availability.released()) + ".");
        }
        if (!availability.withheld().isEmpty()) {
            parts.add("Nicht freigegeben, kein Text an den Dienst: " + String.join(", ", availability.withheld())
                    + ".");
        }
        return String.join("\n", parts);
    }
}
