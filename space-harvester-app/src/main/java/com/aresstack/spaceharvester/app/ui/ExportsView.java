package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exportieren: Quelle und einen oder mehrere Spaces wählen (oft vorbelegt aus Wissensquellen), Formate wählen, starten;
 * je Space entsteht ein Auftrag. JSON ist vorgewählt, die übrigen Formate sind optional. Das Raw-First-Artefakt
 * (Originaldaten und Manifest) entsteht immer. Ein vorheriger Abgleich ist nicht nötig: Fehlende Seiten liest der
 * Export selbst aus der Quelle. Fortschritt, Ergebnis und das Speichern der Dateien zeigt „Aufträge“.
 */
public final class ExportsView implements View {

    private static final Map<String, String> FORMAT_TITLES = Map.of(
            "json", "JSON (strukturiert)", "markdown", "Markdown", "docx", "Word (DOCX)", "pdf", "PDF");

    private final AppContext context;
    private final JPanel root = new JPanel(new BorderLayout(0, 12));
    private final SourcePicker picker;
    private final JPanel formatsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
    private final Map<String, JCheckBox> formats = new LinkedHashMap<>();
    /** Ab so vielen Spaces fragt „Export starten“ nach. */
    static final int CONFIRM_FROM = 10;
    static final String DEFAULT_FORMAT = "json";

    private final ComposerButton start = Comic.primary("Export starten", "Je gewähltem Space einen Export starten");
    private final ComposerButton addSpace = Comic.button("Hinzufügen", "Den Space aus der Auswahl übernehmen");
    private final ComposerButton clearSpaces = Comic.button("Alle entfernen", "Gewählte Spaces leeren");
    private final JPanel chosenPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
    /** Die gewählten Spaces der Quelle {@link #chosenSource}, in dieser Reihenfolge. */
    private final List<String> chosen = new ArrayList<>();
    private String chosenSource;
    private final Feedback feedback = new Feedback();
    private final ExportsModel model = new ExportsModel();
    private final JTable table = Comic.table(new JTable(model));

    public ExportsView(AppContext context) {
        this.context = context;
        this.picker = new SourcePicker(context, false, false);
        root.setBackground(Comic.SURFACE);
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        formatsPanel.setOpaque(false);
        chosenPanel.setOpaque(false);
        start.addActionListener(e -> start());
        addSpace.addActionListener(e -> {
            String key = picker.spaceKey();
            if (!key.isEmpty()) {
                choose(picker.sourceId(), key);
            }
        });
        clearSpaces.addActionListener(e -> setChosen(picker.sourceId(), List.of()));
        // Spaces gehören zu einer Quelle: Mit einer anderen Quelle beginnt die Auswahl neu.
        picker.sourceBox().addActionListener(e -> {
            if (!java.util.Objects.equals(picker.sourceId(), chosenSource)) {
                setChosen(picker.sourceId(), List.of());
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.getSelectedRow();
                if (e.getClickCount() == 2 && row >= 0) {
                    context.navigator().showJob(model.rows.get(row).id());
                }
            }
        });

        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new javax.swing.BoxLayout(form, javax.swing.BoxLayout.Y_AXIS));
        form.add(left(Comic.row(Comic.caption("1 · Quelle"), picker.sourceBox(), Comic.caption("2 · Spaces"),
                picker.spaceBox(), addSpace, clearSpaces)));
        form.add(javax.swing.Box.createVerticalStrut(4));
        form.add(left(chosenPanel));
        form.add(javax.swing.Box.createVerticalStrut(8));
        form.add(left(Comic.row(Comic.caption("3 · Formate"), formatsPanel)));
        form.add(javax.swing.Box.createVerticalStrut(8));
        form.add(left(Comic.muted("Immer dabei: Originaldaten und Manifest mit Prüfsummen. Fehlende Seiten liest der "
                + "Export selbst aus der Quelle, ein Abgleich vorher ist nicht nötig.")));
        form.add(javax.swing.Box.createVerticalStrut(8));
        form.add(left(Comic.row(start)));
        form.add(left(feedback));

        JPanel list = new JPanel(new BorderLayout(0, 6));
        list.setOpaque(false);
        list.add(Comic.scroll(table), BorderLayout.CENTER);
        list.add(Comic.muted("Doppelklick zeigt den Export unter Aufträge, dort lassen sich die Dateien speichern."),
                BorderLayout.SOUTH);

        root.add(Comic.plate("Neuer Export", Comic.ORANGE, form), BorderLayout.NORTH);
        root.add(Comic.plate("Letzte Exporte", Comic.PETROL, list), BorderLayout.CENTER);
        context.onChange(ignored -> refreshFormats());
        refreshFormats();
        renderChosen();
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void shown() {
        refreshList();
    }

    /** Bereitet einen Export vor (aus Wissensquellen oder Inhalte): Quelle und Spaces vorbelegt, nichts gestartet. */
    public void prepare(String sourceId, List<String> spaceKeys) {
        picker.select(sourceId, spaceKeys.size() == 1 ? spaceKeys.get(0) : null);
        setChosen(sourceId, spaceKeys);
        feedback.clear();
    }

    /** Für Tests: die gewählten Spaces. */
    List<String> chosenSpaces() {
        return List.copyOf(chosen);
    }

    /** Für Tests: die vorgewählten Formate. */
    List<String> chosenFormats() {
        return formats.entrySet().stream().filter(e -> e.getValue().isSelected()).map(Map.Entry::getKey).toList();
    }

    private void choose(String sourceId, String key) {
        if (!java.util.Objects.equals(sourceId, chosenSource)) {
            chosen.clear();
            chosenSource = sourceId;
        }
        if (!chosen.contains(key)) {
            chosen.add(key);
        }
        renderChosen();
    }

    private void setChosen(String sourceId, List<String> keys) {
        chosenSource = sourceId;
        chosen.clear();
        keys.stream().distinct().forEach(chosen::add);
        renderChosen();
    }

    /** Die gewählten Spaces als kleine Marken mit ✕. */
    private void renderChosen() {
        chosenPanel.removeAll();
        if (chosen.isEmpty()) {
            chosenPanel.add(Comic.muted("Noch kein Space gewählt: oben wählen und „Hinzufügen“, oder unter "
                    + "Wissensquellen am Space auf das Export-Symbol klicken."));
        }
        for (String key : List.copyOf(chosen)) {
            JPanel chip = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            chip.setBackground(java.awt.Color.WHITE);
            chip.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Comic.LINE),
                    BorderFactory.createEmptyBorder(1, 6, 1, 2)));
            javax.swing.JLabel label = new javax.swing.JLabel(key);
            label.setFont(Comic.regular(12.5f));
            label.setForeground(Comic.INK);
            chip.add(label);
            IconButton remove = new IconButton(Glyph.CANCEL, key + " entfernen");
            remove.addActionListener(e -> {
                chosen.remove(key);
                renderChosen();
            });
            chip.add(remove);
            chosenPanel.add(chip);
        }
        clearSpaces.setVisible(!chosen.isEmpty());
        start.setText(chosen.size() > 1 ? Texts.count(chosen.size(), "Export", "Exporte") + " starten"
                : "Export starten");
        chosenPanel.revalidate();
        chosenPanel.repaint();
    }

    private void refreshFormats() {
        AppRuntime runtime = context.runtime();
        List<String> available = runtime == null ? List.of() : runtime.workspace().exportFormats();
        if (available.equals(new ArrayList<>(formats.keySet()))) {
            return;
        }
        formats.clear();
        formatsPanel.removeAll();
        for (String format : available) {
            JCheckBox box = new JCheckBox(FORMAT_TITLES.getOrDefault(format, format.toUpperCase(Locale.ROOT)));
            box.setOpaque(false);
            box.setFont(Comic.regular(13f));
            box.setForeground(Comic.INK);
            // JSON ist die Vorgabe; alle anderen Formate wählt man bei Bedarf dazu.
            box.setSelected(DEFAULT_FORMAT.equals(format));
            formats.put(format, box);
            formatsPanel.add(box);
        }
        formatsPanel.revalidate();
        refreshList();
    }

    private void refreshList() {
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            model.set(List.of());
            return;
        }
        Background.run(() -> runtime.workspace().jobs().stream().filter(j -> j.type() == JobType.EXPORT).toList(),
                model::set, feedback::problem);
    }

    private void start() {
        AppRuntime runtime = context.runtime();
        String sourceId = picker.sourceId();
        List<String> keys = !chosen.isEmpty() && java.util.Objects.equals(sourceId, chosenSource)
                ? List.copyOf(chosen) : picker.spaceKey().isEmpty() ? List.of() : List.of(picker.spaceKey());
        if (runtime == null || sourceId == null || keys.isEmpty()) {
            feedback.warning("Quelle und Space wählen", null);
            return;
        }
        if (keys.size() >= CONFIRM_FROM && !Dialogs.confirm(root, "Viele Exporte starten?",
                Texts.count(keys.size(), "Space", "Spaces") + " von " + sourceId + " werden exportiert, je Space ein "
                        + "Auftrag. Fehlende Seiten liest jeder Export aus der Quelle; das kann dauern.",
                Texts.count(keys.size(), "Export", "Exporte") + " starten")) {
            return;
        }
        List<String> chosenFormats = chosenFormats();
        start.setEnabled(false);
        feedback.working(keys.size() == 1 ? "Export wird gestartet …" : "Exporte werden gestartet …");
        Background.run(() -> {
            List<JobStatus> started = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            for (String key : keys) {
                try {
                    started.add(runtime.workspace().startExport(sourceId, key, chosenFormats));
                } catch (RuntimeException e) {
                    if (keys.size() == 1) {
                        throw e;
                    }
                    failed.add(key + ": " + Problems.describe(e).title());
                }
            }
            return new Started(started, failed);
        }, result -> {
            start.setEnabled(true);
            refreshList();
            if (result.failed().isEmpty()) {
                feedback.success(result.jobs().size() == 1 ? "Export gestartet"
                        : Texts.count(result.jobs().size(), "Export", "Exporte") + " gestartet");
            } else {
                feedback.warning(Texts.count(result.failed().size(), "Export ließ", "Exporte ließen")
                        + " sich nicht starten", String.join("; ", result.failed()));
            }
            // Mit Fehlern bleibt die Ansicht stehen, damit die Meldung sichtbar ist.
            if (!result.jobs().isEmpty() && result.failed().isEmpty()) {
                context.navigator().showJob(result.jobs().get(0).id());
            }
        }, error -> {
            start.setEnabled(true);
            feedback.problem(error);
        });
    }

    private record Started(List<JobStatus> jobs, List<String> failed) {
    }

    private static JComponent left(JComponent component) {
        component.setAlignmentX(0f);
        return component;
    }

    private static final class ExportsModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Quelle / Space", "Zustand", "Angelegt", "Beendet", "Auftrags-ID"};
        private List<JobStatus> rows = new ArrayList<>();

        void set(List<JobStatus> jobs) {
            rows = new ArrayList<>(jobs);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            JobStatus status = rows.get(row);
            return switch (column) {
                case 0 -> status.sourceId() + " / " + status.partitionKey();
                case 1 -> Texts.state(status.state());
                case 2 -> Texts.time(status.createdAt());
                case 3 -> Texts.time(status.finishedAt());
                default -> status.id();
            };
        }
    }
}
