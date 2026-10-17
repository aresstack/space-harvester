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
 * Exporte: Space wählen, Formate wählen, starten. Das Raw-First-Artefakt (Originaldaten und Manifest) entsteht immer;
 * die gewählten Formate kommen dazu. Fortschritt, Ergebnis und das Speichern der Dateien zeigt „Aktivitäten“.
 */
public final class ExportsView implements View {

    private static final Map<String, String> FORMAT_TITLES = Map.of(
            "json", "JSON (strukturiert)", "markdown", "Markdown", "docx", "Word (DOCX)", "pdf", "PDF");

    private final AppContext context;
    private final JPanel root = new JPanel(new BorderLayout(0, 12));
    private final SourcePicker picker;
    private final JPanel formatsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
    private final Map<String, JCheckBox> formats = new LinkedHashMap<>();
    private final ComposerButton start = Comic.primary("Export starten", "Export dieses Space starten");
    private final Feedback feedback = new Feedback();
    private final ExportsModel model = new ExportsModel();
    private final JTable table = Comic.table(new JTable(model));

    public ExportsView(AppContext context) {
        this.context = context;
        this.picker = new SourcePicker(context, false, false);
        root.setBackground(Comic.SURFACE);
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        formatsPanel.setOpaque(false);
        start.addActionListener(e -> start());
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
        form.add(left(Comic.row(Comic.caption("1 · Quelle"), picker.sourceBox(), Comic.caption("2 · Space"),
                picker.spaceBox())));
        form.add(javax.swing.Box.createVerticalStrut(8));
        form.add(left(Comic.row(Comic.caption("3 · Formate"), formatsPanel)));
        form.add(javax.swing.Box.createVerticalStrut(8));
        form.add(left(Comic.note("Immer enthalten: die Originaldaten jeder Seite byte-genau und ein Manifest mit "
                + "Prüfsummen. Ein Export ist nur vollständig, wenn der Space zuvor vollständig abgeglichen wurde; "
                + "sonst nennt das Manifest die Lücken.")));
        form.add(javax.swing.Box.createVerticalStrut(8));
        form.add(left(Comic.row(start)));
        form.add(left(feedback));

        JPanel list = new JPanel(new BorderLayout(0, 6));
        list.setOpaque(false);
        list.add(Comic.scroll(table), BorderLayout.CENTER);
        list.add(Comic.muted("Doppelklick zeigt den Export unter Aktivitäten, dort lassen sich die Dateien speichern."),
                BorderLayout.SOUTH);

        root.add(Comic.plate("Neuer Export", Comic.ORANGE, form), BorderLayout.NORTH);
        root.add(Comic.plate("Letzte Exporte", Comic.PETROL, list), BorderLayout.CENTER);
        context.onChange(ignored -> refreshFormats());
        refreshFormats();
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void shown() {
        refreshList();
    }

    /** Bereitet einen Export vor (aus Wissensquellen oder Inhalte). */
    public void prepare(String sourceId, String spaceKey) {
        picker.select(sourceId, spaceKey);
        feedback.clear();
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
        String spaceKey = picker.spaceKey();
        if (runtime == null || sourceId == null || spaceKey.isEmpty()) {
            feedback.warning("Quelle und Space wählen", null);
            return;
        }
        List<String> chosen = formats.entrySet().stream().filter(e -> e.getValue().isSelected())
                .map(Map.Entry::getKey).toList();
        start.setEnabled(false);
        feedback.working("Export wird gestartet …");
        Background.run(() -> runtime.workspace().startExport(sourceId, spaceKey, chosen), status -> {
            start.setEnabled(true);
            feedback.success("Export gestartet");
            refreshList();
            context.navigator().showJob(status.id());
        }, error -> {
            start.setEnabled(true);
            feedback.problem(error);
        });
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
