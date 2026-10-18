package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.application.jobs.JobStatus;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Herunterladen ist ein Sammler: Spaces werden ausschließlich in den anderen Ansichten über
 * „Herunterladen“ hierher übergeben. Der Sammler enthält Einträge aus beliebig vielen Quellen,
 * bewahrt die Reihenfolge und ignoriert Duplikate. Erst „Ausführen“ erzeugt je Space einen
 * JSON-Downloadauftrag. Die daraus möglichen Formate entstehen später unter „Aufträge“.
 */
public final class ExportsView implements View {

    /** Ab dieser Zahl wird die geplante Zahl der Aufträge vorher bestätigt. */
    static final int CONFIRM_FROM = 10;
    static final String DEFAULT_FORMAT = "json";

    private final AppContext context;
    private final JPanel root = new JPanel(new BorderLayout(0, 12));
    private final Set<QueuedSpace> queued = new LinkedHashSet<>();
    private final QueuedModel model = new QueuedModel();
    private final JTable table = Comic.table(new JTable(model));
    private final JLabel hint = Comic.muted("Noch keine Spaces gesammelt.");
    private final ComposerButton start = Comic.primary("Ausführen", "Alle gesammelten Spaces herunterladen");
    private final ComposerButton remove = Comic.button("Ausgewählten entfernen", "Markierten Eintrag aus der Sammlung entfernen");
    private final ComposerButton clear = Comic.button("Alle entfernen", "Gesamte Sammlung leeren");
    private final Feedback feedback = new Feedback();
    private boolean starting;

    public ExportsView(AppContext context) {
        this.context = context;
        root.setBackground(Comic.SURFACE);
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        start.addActionListener(e -> start());
        remove.addActionListener(e -> removeSelected());
        clear.addActionListener(e -> {
            queued.clear();
            feedback.clear();
            render();
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                remove.setEnabled(table.getSelectedRow() >= 0);
            }
        });
        table.getInputMap().put(KeyStroke.getKeyStroke("DELETE"), "removeQueued");
        table.getActionMap().put("removeQueued", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                removeSelected();
            }
        });

        JPanel contents = new JPanel(new BorderLayout(0, 10));
        contents.setOpaque(false);
        contents.add(Comic.muted("Spaces sammelst du unter „Wissensquellen“ über „Herunterladen“. "
                + "Hier kannst du die Sammlung prüfen oder Einträge entfernen. "
                + "„Ausführen“ erstellt je Space einen JSON-Datenbestand; PDF, Word und Markdown "
                + "erzeugst du danach unter „Aufträge“."), BorderLayout.NORTH);
        contents.add(Comic.scroll(table), BorderLayout.CENTER);

        JPanel actions = new JPanel(new BorderLayout(0, 6));
        actions.setOpaque(false);
        actions.add(hint, BorderLayout.NORTH);
        actions.add(Comic.row(start, remove, clear), BorderLayout.CENTER);
        actions.add(feedback, BorderLayout.SOUTH);
        contents.add(actions, BorderLayout.SOUTH);

        root.add(Comic.plate("Gesammelte Downloads", Comic.ORANGE, contents), BorderLayout.CENTER);
        render();
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void shown() {
        render();
    }

    /**
     * Ergänzt den Sammler mit der Auswahl aus einer anderen Ansicht. Bereits vorhandene Spaces
     * bleiben erhalten, auch wenn sie zu anderen Quellen gehören. Es wird nichts gestartet.
     */
    public void prepare(String sourceId, List<String> spaceKeys) {
        if (sourceId == null || sourceId.isBlank() || spaceKeys == null) {
            return;
        }
        for (String key : spaceKeys) {
            if (key != null && !key.isBlank()) {
                queued.add(new QueuedSpace(sourceId, key));
            }
        }
        feedback.clear();
        render();
    }

    /** Für Tests: gesammelte Space-Keys in Einfügereihenfolge. */
    List<String> chosenSpaces() {
        return queued.stream().map(QueuedSpace::spaceKey).toList();
    }

    /** Für Tests: gesammelte Quellen in derselben Reihenfolge wie die Spaces. */
    List<String> chosenSources() {
        return queued.stream().map(QueuedSpace::sourceId).toList();
    }

    /** Jeder Download beschafft zunächst nur den JSON-Datenbestand. */
    List<String> chosenFormats() {
        return List.of(DEFAULT_FORMAT);
    }

    private void removeSelected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return;
        }
        queued.remove(model.get(table.convertRowIndexToModel(row)));
        feedback.clear();
        render();
    }

    private void render() {
        model.set(new ArrayList<>(queued));
        int amount = queued.size();
        long sources = queued.stream().map(QueuedSpace::sourceId).distinct().count();
        hint.setText(amount == 0 ? "Noch keine Spaces gesammelt."
                : Texts.count(amount, "Space", "Spaces") + " aus " + sources
                + (sources == 1 ? " Quelle gesammelt." : " Quellen gesammelt."));
        start.setEnabled(!starting && amount > 0);
        remove.setEnabled(table.getSelectedRow() >= 0);
        clear.setEnabled(amount > 0);
    }

    private void start() {
        AppRuntime runtime = context.runtime();
        List<QueuedSpace> entries = List.copyOf(queued);
        if (runtime == null || entries.isEmpty()) {
            feedback.warning("Keine Downloads gesammelt", "Wähle zuerst unter „Wissensquellen“ Spaces aus.");
            return;
        }
        if (entries.size() >= CONFIRM_FROM && !Dialogs.confirm(root, "Viele Spaces herunterladen?",
                Texts.count(entries.size(), "Space", "Spaces") + " aus "
                        + entries.stream().map(QueuedSpace::sourceId).distinct().count()
                        + " Quellen werden heruntergeladen, je Space ein Auftrag. "
                        + "Fehlende Seiten liest der Download aus der Quelle; das kann dauern.",
                "Ausführen")) {
            return;
        }

        starting = true;
        start.setEnabled(false);
        feedback.working(entries.size() == 1 ? "Download wird gestartet …" : "Downloads werden gestartet …");
        Background.run(() -> {
            List<JobStatus> started = new ArrayList<>();
            List<QueuedSpace> accepted = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            for (QueuedSpace entry : entries) {
                try {
                    started.add(runtime.workspace().startExport(entry.sourceId(), entry.spaceKey(), chosenFormats()));
                    accepted.add(entry);
                } catch (RuntimeException e) {
                    failed.add(entry.sourceId() + " / " + entry.spaceKey() + ": " + Problems.describe(e).title());
                }
            }
            return new Started(started, accepted, failed);
        }, result -> {
            starting = false;
            queued.removeAll(result.accepted());
            render();
            if (result.failed().isEmpty()) {
                feedback.success(result.jobs().size() == 1 ? "Download gestartet"
                        : Texts.count(result.jobs().size(), "Download", "Downloads") + " gestartet");
            } else {
                feedback.warning(Texts.count(result.failed().size(), "Download ließ", "Downloads ließen")
                        + " sich nicht starten", String.join("; ", result.failed()));
            }
            // Wenn alle Aufträge angelegt wurden, direkt zum ersten Auftrag wechseln.
            // Fehlerhafte Einträge bleiben dagegen im Sammler, damit sie erneut versucht werden können.
            if (!result.jobs().isEmpty() && result.failed().isEmpty()) {
                context.navigator().showJob(result.jobs().get(0).id());
            }
        }, error -> {
            starting = false;
            render();
            feedback.problem(error);
        });
    }

    private record QueuedSpace(String sourceId, String spaceKey) {
    }

    private record Started(List<JobStatus> jobs, List<QueuedSpace> accepted, List<String> failed) {
    }

    private static final class QueuedModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Wissensquelle", "Space"};
        private List<QueuedSpace> rows = List.of();

        void set(List<QueuedSpace> entries) {
            rows = List.copyOf(entries);
            fireTableDataChanged();
        }

        QueuedSpace get(int row) {
            return rows.get(row);
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
            QueuedSpace entry = rows.get(row);
            return column == 0 ? entry.sourceId() : entry.spaceKey();
        }
    }
}
