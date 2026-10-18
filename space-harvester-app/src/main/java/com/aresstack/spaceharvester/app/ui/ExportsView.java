package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Herunterladen: Quelle und einen oder mehrere Spaces wählen (oft vorbelegt aus Wissensquellen), starten; je Space
 * entsteht ein Auftrag, der die Seiten einmal beschafft und den JSON-Datenbestand anlegt. Ein vorheriger Abgleich ist
 * nicht nötig: Fehlende Seiten liest der Export selbst aus der Quelle. PDF, Word, Markdown oder JSON erzeugt danach
 * „Exportieren“ auf der Karte unter „Aufträge“ aus diesem Datenbestand, ohne die Quelle erneut zu lesen.
 */
public final class ExportsView implements View {

    private final AppContext context;
    private final JPanel root = new JPanel(new BorderLayout(0, 12));
    private final SourcePicker picker;
    /** Ab so vielen Spaces fragt „Herunterladen“ nach. */
    static final int CONFIRM_FROM = 10;
    static final String DEFAULT_FORMAT = "json";

    private final ComposerButton start = Comic.primary("Herunterladen", "Je gewähltem Space einen Download starten");
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
        // Beim Auffrischen der Quellenliste ist kurz keine Quelle gewählt; das ist kein Wechsel.
        picker.sourceBox().addActionListener(e -> {
            if (picker.sourceId() != null && !java.util.Objects.equals(picker.sourceId(), chosenSource)) {
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
        form.add(left(Comic.muted("Herunterladen beschafft die Seiten einmal als JSON-Datenbestand; ein Abgleich vorher "
                + "ist nicht nötig. PDF, Word oder Markdown erzeugst du danach unter Aufträge mit „Exportieren“.")));
        form.add(javax.swing.Box.createVerticalStrut(8));
        form.add(left(Comic.row(start)));
        form.add(left(feedback));

        JPanel list = new JPanel(new BorderLayout(0, 6));
        list.setOpaque(false);
        list.add(Comic.scroll(table), BorderLayout.CENTER);
        list.add(Comic.muted("Doppelklick zeigt den Download unter Aufträge, dort exportierst du ihn als PDF, Word, "
                + "Markdown oder JSON."),
                BorderLayout.SOUTH);

        root.add(Comic.plate("Neuer Download", Comic.ORANGE, form), BorderLayout.NORTH);
        root.add(Comic.plate("Letzte Downloads", Comic.PETROL, list), BorderLayout.CENTER);
        context.onChange(ignored -> refreshList());
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

    /** Herunterladen legt immer den JSON-Datenbestand an; andere Formate entstehen daraus unter „Aufträge“. */
    List<String> chosenFormats() {
        return List.of(DEFAULT_FORMAT);
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
        start.setText(chosen.size() > 1 ? Texts.count(chosen.size(), "Space", "Spaces") + " herunterladen"
                : "Herunterladen");
        chosenPanel.revalidate();
        chosenPanel.repaint();
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
        // Exportiert wird genau, was als Marke angezeigt wird; die Space-Auswahl oben zählt erst nach „Hinzufügen“.
        List<String> keys = java.util.Objects.equals(sourceId, chosenSource) ? List.copyOf(chosen) : List.of();
        if (runtime == null || sourceId == null || keys.isEmpty()) {
            feedback.warning("Space hinzufügen", "Oben einen Space wählen und „Hinzufügen“ klicken.");
            return;
        }
        if (keys.size() >= CONFIRM_FROM && !Dialogs.confirm(root, "Viele Spaces herunterladen?",
                Texts.count(keys.size(), "Space", "Spaces") + " von " + sourceId + " werden heruntergeladen, je Space ein "
                        + "Auftrag. Fehlende Seiten liest jeder Download aus der Quelle; das kann dauern.",
                "Herunterladen")) {
            return;
        }
        List<String> chosenFormats = chosenFormats();
        start.setEnabled(false);
        feedback.working(keys.size() == 1 ? "Download wird gestartet …" : "Downloads werden gestartet …");
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
                feedback.success(result.jobs().size() == 1 ? "Download gestartet"
                        : Texts.count(result.jobs().size(), "Download", "Downloads") + " gestartet");
            } else {
                feedback.warning(Texts.count(result.failed().size(), "Download ließ", "Downloads ließen")
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
