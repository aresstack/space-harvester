package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.ui.brand.ScenePanel;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobStillRunningException;
import com.aresstack.spaceharvester.application.jobs.Redaction;
import com.aresstack.spaceharvester.application.sources.HarvestSource;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Status als Diagnosezentrum: die Diagnoseereignisse der Aufträge (docs/DIAGNOSTICS.md) und die Verbindungsprüfungen
 * der Quellen, neueste zuerst, mit Zeit, Komponente, Phase, Code, HTTP-Status, Proxy-Route, TLS-Befund und Auftrag;
 * Filter nach Art, Zeitraum und Text; ein Ereignis klappt unten mit seiner Ausnahmekette auf. Alles ist maskiert
 * ({@link Redaction}). Solange keine Quelle eingerichtet ist, begrüßt stattdessen die Weltraumszene mit dem Einstieg
 * in den Quellen-Assistenten.
 */
public final class StatusView implements View {

    /** So viele jüngste Ereignisse lädt die Ansicht ohne gewählten Auftrag. */
    static final int RECENT = 500;

    enum Filter {
        ALL("Alle", "Alle Ereignisse"),
        PROBLEMS("Fehler", "Fehler und Warnungen"),
        NETWORK("Netzwerk", "Verbindung, Proxy, HTTP-Status, TLS und Zertifikate"),
        JOBS("Aufträge", "Lebenslauf und Phasen der Aufträge");

        final String label;
        final String tooltip;

        Filter(String label, String tooltip) {
            this.label = label;
            this.tooltip = tooltip;
        }
    }

    enum Period {
        ALL("Gesamter Zeitraum", null),
        QUARTER("Letzte 15 Minuten", Duration.ofMinutes(15)),
        HOUR("Letzte Stunde", Duration.ofHours(1)),
        TODAY("Heute", null);

        final String label;
        final Duration span;

        Period(String label, Duration span) {
            this.label = label;
            this.span = span;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final AppContext context;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JPanel attention = new JPanel();
    private final JComponent attentionPlate;
    private final JComponent dashboard;
    private final JComponent welcome;
    private final JPanel dashboardAttention = new JPanel(new BorderLayout());
    private final JPanel welcomeAttentionHolder = new JPanel(new BorderLayout());
    private final JComponent welcomeAttention;
    private boolean welcoming;

    private final List<ComposerToggleButton> filters = new ArrayList<>();
    private Filter filter = Filter.ALL;
    private final JComboBox<Period> period = Comic.combo();
    private final JTextField text = Comic.field(22);
    private final JLabel focus = Comic.muted(" ");
    private final ComposerButton unfocus = Comic.button("Alle Aufträge", "Wieder die Ereignisse aller Aufträge zeigen");
    private final ComposerButton reload = Comic.button("Aktualisieren", "Ereignisse neu laden");
    private final JLabel count = Comic.muted(" ");
    private final Feedback feedback = new Feedback();
    private final EventsModel model = new EventsModel();
    private final JTable table = Comic.table(new JTable(model));
    private final JTextArea detail = Comic.code();
    private final ComposerButton copy = Comic.button("Kopieren", "Das gewählte Ereignis mit Ausnahmekette kopieren");
    final ComposerButton delete = Comic.button("Löschen", "Das gewählte Ereignis aus dem Protokoll löschen");
    final ComposerButton clean = Comic.button("Protokoll bereinigen …",
            "Ereignisse beendeter Aufträge vor einem Zeitpunkt löschen");
    private final Timer timer = new Timer(3000, e -> load());
    /** Ausgeblendete Verbindungsprüfungen (Quelle und Zeitpunkt); sie stehen nirgends sonst, nur in dieser Sitzung. */
    private final java.util.Set<String> hiddenChecks = new java.util.HashSet<>();
    private Instant checksBefore = Instant.MIN;
    /** Alle geladenen Einträge vor dem Filtern. */
    private List<Entry> loaded = List.of();
    /** Auftrag, auf den die Ansicht beschränkt ist, oder {@code null}. */
    private String focusedJob;
    private int request;

    public StatusView(AppContext context) {
        this.context = context;
        root.setBackground(Comic.SURFACE);
        attention.setOpaque(false);
        attention.setLayout(new BoxLayout(attention, BoxLayout.Y_AXIS));

        ButtonGroup group = new ButtonGroup();
        JPanel kinds = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        kinds.setOpaque(false);
        for (Filter f : Filter.values()) {
            ComposerToggleButton button = new ComposerToggleButton(null, f.label, f.tooltip);
            button.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
            button.setSelected(f == Filter.ALL);
            button.addActionListener(e -> {
                filter = f;
                apply();
            });
            group.add(button);
            filters.add(button);
            kinds.add(button);
        }
        for (Period p : Period.values()) {
            period.addItem(p);
        }
        period.setToolTipText("Zeitraum");
        period.addActionListener(e -> apply());
        text.setToolTipText("Filtert nach Komponente, Phase, Code, HTTP-Status, Proxy-Route, TLS, Auftrags-ID, Quelle, "
                + "Space und Meldung");
        text.getAccessibleContext().setAccessibleName("Ereignisse filtern");
        text.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                apply();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                apply();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                apply();
            }
        });
        unfocus.addActionListener(e -> showJob(null));
        reload.addActionListener(e -> load());
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(detail.getText()), null));
        delete.addActionListener(e -> {
            Entry chosen = selected();
            if (chosen != null && Dialogs.confirm(root, "Ereignis löschen?", "Das gewählte Ereignis wird dauerhaft "
                    + "aus dem Protokoll gelöscht; der Auftrag und seine übrigen Ereignisse bleiben.", "Löschen")) {
                deleteEntry(chosen);
            }
        });
        clean.addActionListener(e -> {
            Instant before = askCleanup();
            if (before != null) {
                cleanBefore(before);
            }
        });

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showDetail();
            }
        });
        table.setDefaultRenderer(Object.class, new SeverityRenderer());
        int[] widths = {150, 60, 125, 115, 135, 45, 150, 110, 110, 260};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        detail.setLineWrap(false);
        detail.setText("Ein Ereignis wählen, um alle Befunde und die Ausnahmekette zu sehen.");

        JPanel bar = Comic.row(kinds, Comic.caption("Zeitraum"), period, Comic.caption("Filter"), text, reload,
                clean);
        JPanel focusRow = new JPanel(new BorderLayout(8, 0));
        focusRow.setOpaque(false);
        focusRow.add(focus, BorderLayout.CENTER);
        focusRow.add(Comic.row(unfocus), BorderLayout.EAST);
        JPanel head = new JPanel(new BorderLayout(0, 6));
        head.setOpaque(false);
        head.add(bar, BorderLayout.NORTH);
        head.add(focusRow, BorderLayout.CENTER);
        head.add(feedback, BorderLayout.SOUTH);

        JScrollPane detailScroll = Comic.scroll(detail);
        detailScroll.setPreferredSize(new Dimension(400, 170));
        JPanel detailPanel = new JPanel(new BorderLayout(0, 4));
        detailPanel.setOpaque(false);
        JPanel detailHead = new JPanel(new BorderLayout());
        detailHead.setOpaque(false);
        detailHead.add(Comic.caption("Details"), BorderLayout.WEST);
        detailHead.add(Comic.row(copy, delete), BorderLayout.EAST);
        detailPanel.add(detailHead, BorderLayout.NORTH);
        detailPanel.add(detailScroll, BorderLayout.CENTER);

        JPanel list = new JPanel(new BorderLayout(0, 6));
        list.setOpaque(false);
        list.add(Comic.scroll(table), BorderLayout.CENTER);
        list.add(count, BorderLayout.SOUTH);
        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        body.add(head, BorderLayout.NORTH);
        body.add(list, BorderLayout.CENTER);
        body.add(detailPanel, BorderLayout.SOUTH);

        dashboardAttention.setOpaque(false);
        welcomeAttentionHolder.setOpaque(false);
        welcomeAttention = Comic.plate("Zu erledigen", Comic.RED, welcomeAttentionHolder);
        attentionPlate = Comic.plate("Zu erledigen", Comic.RED, dashboardAttention);
        dashboardAttention.add(attention, BorderLayout.CENTER);
        JPanel board = new JPanel(new BorderLayout(0, 12));
        board.setBackground(Comic.SURFACE);
        board.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        board.add(attentionPlate, BorderLayout.NORTH);
        board.add(Comic.plate("Diagnose", Comic.PETROL, body), BorderLayout.CENTER);
        dashboard = board;
        welcome = Comic.column(welcomePlate(), welcomeAttention);
        root.add(dashboard, BorderLayout.CENTER);
        context.onChange(ignored -> render());
        render();
        showJob(null);
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void shown() {
        render();
        load();
        timer.start();
    }

    /** Zeigt die Diagnose genau eines Auftrags, z. B. aus Aufträge; {@code null} zeigt wieder alle. */
    public void showJob(String jobId) {
        focusedJob = jobId;
        focus.setText(jobId == null ? "Jüngste Ereignisse aller Aufträge dieser App und die Verbindungsprüfungen"
                : "Diagnose des Auftrags " + jobId);
        unfocus.setVisible(jobId != null);
        if (jobId != null) {
            filter = Filter.ALL;
            filters.get(0).setSelected(true);
            period.setSelectedItem(Period.ALL);
            text.setText("");
        }
        load();
    }

    /** Für Tests: die sichtbaren Einträge. */
    List<Entry> entries() {
        return List.copyOf(model.rows);
    }

    /** Für Tests: wählt einen Filter wie ein Klick. */
    void filter(Filter f) {
        filters.get(f.ordinal()).doClick();
    }

    void filterText(String value) {
        text.setText(value);
    }

    String detailText() {
        return detail.getText();
    }

    // ------------------------------------------------------------------ Löschen

    /**
     * Löscht ein Ereignis: ein Auftragsereignis dauerhaft im Core, eine Verbindungsprüfung (nur in dieser Sitzung
     * gemerkt) durch Ausblenden.
     */
    void deleteEntry(Entry entry) {
        AppRuntime runtime = context.runtime();
        if (entry.jobId() == null) {
            hiddenChecks.add(checkKey(entry.sourceId(), entry.at()));
            feedback.success("Verbindungsprüfung aus dem Protokoll entfernt");
            load();
            return;
        }
        if (runtime == null) {
            return;
        }
        Background.run(() -> runtime.workspace().deleteEvent(entry.jobId(), entry.sequence()), deleted -> {
            feedback.success(deleted ? "Ereignis gelöscht" : "Das Ereignis war schon gelöscht");
            load();
        }, this::deleteFailed);
    }

    /** Löscht die Ereignisse beendeter Aufträge vor {@code before} ({@link Instant#MAX}: alle). */
    void cleanBefore(Instant before) {
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            return;
        }
        // Auch die Verbindungsprüfungen bis jetzt; spätere Prüfungen erscheinen wieder.
        checksBefore = before.equals(Instant.MAX) ? Instant.now() : before;
        Background.run(() -> runtime.workspace().deleteEventsBefore(before), deleted -> {
            feedback.success(Texts.count(deleted, "Ereignis", "Ereignisse") + " gelöscht"
                    + "; Ereignisse laufender Aufträge bleiben");
            load();
        }, this::deleteFailed);
    }

    private void deleteFailed(Throwable error) {
        if (error instanceof JobStillRunningException) {
            feedback.info("Der Auftrag läuft noch", "Ereignisse eines laufenden Auftrags lassen sich erst nach "
                    + "seinem Ende löschen.");
        } else {
            feedback.problem(error);
        }
    }

    /** Fragt den Zeitraum für „Protokoll bereinigen“ ab; {@code null} bei Abbruch. */
    private Instant askCleanup() {
        record Choice(String label, Duration age) {
            @Override
            public String toString() {
                return label;
            }
        }
        JComboBox<Choice> choice = Comic.combo();
        choice.addItem(new Choice("Älter als eine Stunde", Duration.ofHours(1)));
        choice.addItem(new Choice("Älter als einen Tag", Duration.ofDays(1)));
        choice.addItem(new Choice("Älter als eine Woche", Duration.ofDays(7)));
        choice.addItem(new Choice("Alle Ereignisse beendeter Aufträge", null));
        choice.setSelectedIndex(1);
        Instant[] result = {null};
        javax.swing.JDialog dialog = Dialogs.dialog(root, "Protokoll bereinigen");
        ComposerButton yes = Comic.danger("Löschen", null);
        ComposerButton no = Comic.button("Abbrechen", null);
        yes.addActionListener(e -> {
            Choice chosen = (Choice) choice.getSelectedItem();
            result[0] = chosen == null || chosen.age() == null ? Instant.MAX : Instant.now().minus(chosen.age());
            dialog.dispose();
        });
        no.addActionListener(e -> dialog.dispose());
        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setOpaque(false);
        body.add(Comic.note("Löscht die Diagnoseereignisse beendeter Aufträge dauerhaft. Die Aufträge selbst bleiben "
                + "unter „Aufträge“, Ereignisse laufender Aufträge bleiben ebenfalls."), BorderLayout.NORTH);
        body.add(choice, BorderLayout.CENTER);
        Dialogs.show(dialog, "Protokoll bereinigen", body, no, yes);
        return result[0];
    }

    private static String checkKey(String sourceId, Instant at) {
        return sourceId + "@" + at;
    }

    // ------------------------------------------------------------------ Laden

    private void load() {
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            loaded = List.of();
            apply();
            return;
        }
        String job = focusedJob;
        List<Entry> checks = job == null ? connectionEntries(runtime) : List.of();
        int id = ++request;
        Background.run(() -> job == null ? runtime.workspace().recentEvents(RECENT) : runtime.workspace().events(job),
                events -> {
                    if (id != request) {
                        return;
                    }
                    loaded = Stream.concat(events.stream().map(Entry::of), checks.stream())
                            .sorted(Comparator.comparing(Entry::at).thenComparing(Entry::sequence).reversed())
                            .toList();
                    feedback.clear();
                    apply();
                }, error -> {
                    if (id != request) {
                        return;
                    }
                    loaded = checks;
                    feedback.problem(error);
                    apply();
                });
        if (!root.isShowing()) {
            timer.stop();
        }
    }

    /** Die letzte Verbindungsprüfung je Quelle als Eintrag; Fehler mit Code und technischem Befund. */
    private List<Entry> connectionEntries(AppRuntime runtime) {
        List<Entry> result = new ArrayList<>();
        for (HarvestSource source : runtime.workspace().sources()) {
            AppContext.Connection connection = context.connection(source.id());
            if (connection.checkedAt() == null || connection.checkedAt().isBefore(checksBefore)
                    || hiddenChecks.contains(checkKey(source.id(), connection.checkedAt()))) {
                continue;
            }
            switch (connection.state()) {
                case CONNECTED -> result.add(new Entry(connection.checkedAt(), 0, JobEvent.Severity.INFO,
                        JobEvent.Category.NETWORK, "Verbindungsprüfung", "Spaces abrufen", "CONNECTED",
                        "Verbunden, " + Texts.count(connection.spaces().size(), "Space", "Spaces") + " sichtbar",
                        null, null, null, null, source.id(), null, null));
                case FAILED -> {
                    Problems.Problem problem = Problems.describe(connection.error());
                    result.add(new Entry(connection.checkedAt(), 0, JobEvent.Severity.ERROR,
                            JobEvent.Category.NETWORK, "Verbindungsprüfung", "Spaces abrufen",
                            Redaction.mask(problem.title()), Redaction.mask(problem.message()), httpStatus(problem),
                            null, tls(connection.error()), null, source.id(), null,
                            problem.technical() == null ? null : Redaction.mask(problem.technical())));
                }
                default -> {
                }
            }
        }
        return result;
    }

    private static Integer httpStatus(Problems.Problem problem) {
        String technical = problem.technical() == null ? "" : problem.technical();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("HTTP (\\d{3})").matcher(technical);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static String tls(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof javax.net.ssl.SSLException || t instanceof java.security.cert.CertificateException) {
                return Redaction.mask(t.getClass().getSimpleName() + ": " + Objects.toString(t.getMessage(), ""));
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ Filtern und Anzeigen

    private void apply() {
        Entry chosen = selected();
        String needle = text.getText().trim().toLowerCase(Locale.ROOT);
        Period p = (Period) period.getSelectedItem();
        Instant now = Instant.now();
        Instant since = p == null || p == Period.ALL ? null : p == Period.TODAY
                ? LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant() : now.minus(p.span);
        List<Entry> shown = loaded.stream()
                .filter(e -> switch (filter) {
                    case ALL -> true;
                    case PROBLEMS -> e.severity() != JobEvent.Severity.INFO;
                    case NETWORK -> e.category() == JobEvent.Category.NETWORK || e.httpStatus() != null
                            || e.proxyRoute() != null || e.tlsFinding() != null;
                    case JOBS -> e.jobId() != null;
                })
                .filter(e -> since == null || !e.at().isBefore(since))
                .filter(e -> needle.isEmpty() || e.haystack().contains(needle))
                .toList();
        model.set(shown);
        count.setText(Texts.count(shown.size(), "Ereignis", "Ereignisse")
                + (shown.size() < loaded.size() ? " von " + loaded.size() : "")
                + (focusedJob == null && loaded.size() >= RECENT ? " · die jüngsten " + RECENT : ""));
        int index = chosen == null ? -1 : shown.indexOf(chosen);
        if (index >= 0) {
            table.setRowSelectionInterval(index, index);
        } else if (focusedJob != null && !shown.isEmpty()) {
            // Beim Sprung aus Aufträge steht das Ende des Auftrags (meist der Fehler) gleich offen.
            table.setRowSelectionInterval(0, 0);
        } else {
            showDetail();
        }
    }

    private Entry selected() {
        int row = table.getSelectedRow();
        return row < 0 || row >= model.rows.size() ? null : model.rows.get(row);
    }

    private void showDetail() {
        Entry e = selected();
        if (e == null) {
            detail.setText("Ein Ereignis wählen, um alle Befunde und die Ausnahmekette zu sehen.");
            copy.setEnabled(false);
            delete.setEnabled(false);
            return;
        }
        StringBuilder out = new StringBuilder();
        line(out, "Zeit", Texts.time(e.at()) + "  (" + e.at() + ")");
        line(out, "Schwere", severity(e.severity()));
        line(out, "Bereich", e.category().name());
        line(out, "Komponente", e.component());
        line(out, "Phase", e.phase());
        line(out, "Code", e.code());
        line(out, "Meldung", e.message());
        line(out, "HTTP-Status", e.httpStatus() == null ? null : String.valueOf(e.httpStatus()));
        line(out, "Proxy-Route", e.proxyRoute());
        line(out, "TLS", e.tlsFinding());
        line(out, "Auftrag", e.jobId() == null ? null : e.jobId() + (e.sequence() > 0 ? " · #" + e.sequence() : ""));
        line(out, "Quelle", e.sourceId());
        line(out, "Space", e.partitionKey());
        if (e.detail() != null) {
            out.append('\n').append(e.detail());
        }
        detail.setText(out.toString());
        detail.setCaretPosition(0);
        copy.setEnabled(true);
        delete.setEnabled(true);
    }

    private static void line(StringBuilder out, String label, String value) {
        if (value != null && !value.isBlank()) {
            out.append(String.format(Locale.ROOT, "%-12s %s%n", label + ":", value));
        }
    }

    private static String severity(JobEvent.Severity severity) {
        return switch (severity) {
            case INFO -> "Info";
            case WARNING -> "Warnung";
            case ERROR -> "Fehler";
        };
    }

    // ------------------------------------------------------------------ Zu erledigen und Begrüßung

    private void render() {
        attention.removeAll();
        AppRuntime runtime = context.runtime();
        showWelcome(runtime != null && context.settings().sources().isEmpty());
        if (runtime == null) {
            note(context.startProblem() == null ? "Die App startet …"
                    : "Der Start ist fehlgeschlagen: " + Problems.describe(context.startProblem()).title()
                    + ". Details unter Einstellungen.", AppContext.Section.SETTINGS, "Zu den Einstellungen");
        } else {
            if (!context.settings().problems().isEmpty()) {
                note(Texts.count(context.settings().problems().size(), "Hinweis", "Hinweise")
                        + " zur Konfiguration: " + context.settings().problems().get(0),
                        AppContext.Section.SETTINGS, "Alle ansehen");
            }
            if (!runtime.rejectedSources().isEmpty()) {
                note(Texts.count(runtime.rejectedSources().size(), "Quelle fehlt", "Quellen fehlen")
                        + " wegen fehlerhafter Einstellungen: " + runtime.rejectedSources().get(0),
                        AppContext.Section.SETTINGS, "Alle ansehen");
            }
            for (String id : runtime.waitingForSecret()) {
                note("Für „" + id + "“ fehlen die Zugangsdaten.", AppContext.Section.SOURCES, "Zugangsdaten eingeben");
            }
        }
        boolean open = attention.getComponentCount() > 0;
        welcomeAttention.setVisible(open);
        attentionPlate.setVisible(open);
        if (!welcoming) {
            load();
        }
        root.revalidate();
        root.repaint();
    }

    /** Ohne Quellen: Szene, Name, Leitsatz und der Weg zur ersten Quelle. */
    private JComponent welcomePlate() {
        JLabel name = new JLabel("Space Harvester");
        name.setFont(Comic.semiBold(30f));
        name.setForeground(Comic.INK);
        ComposerButton add = Comic.primary("Erste Wissensquelle hinzufügen …", "Führt Schritt für Schritt durch die "
                + "Einrichtung einer Confluence- oder Dateiquelle");
        add.addActionListener(e -> SourceWizard.open(root, context,
                id -> context.navigator().show(AppContext.Section.SOURCES)));
        JPanel textPanel = new JPanel(new GridBagLayout());
        textPanel.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        textPanel.add(name, c);
        c.insets = new Insets(2, 0, 16, 0);
        textPanel.add(AboutDialog.heading(AboutDialog.TAGLINE), c);
        c.insets = new Insets(0, 0, 18, 0);
        textPanel.add(Comic.note("Noch ist keine Wissensquelle eingerichtet. Der Assistent fragt Adresse, Anmeldung und "
                + "Proxy ab, prüft die Verbindung und trägt die Quelle in die Konfigurationsdatei ein; Passwörter und "
                + "Token legt die App verschlüsselt ab, nie in der Datei."), c);
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(0, 0, 0, 0);
        textPanel.add(add, c);
        JPanel body = new JPanel(new BorderLayout(28, 0));
        body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 8));
        body.add(new ScenePanel(380, 520, Comic.INK), BorderLayout.WEST);
        body.add(textPanel, BorderLayout.CENTER);
        return Comic.plate(null, Comic.BLUE, body);
    }

    private void showWelcome(boolean show) {
        if (show == welcoming) {
            return;
        }
        welcoming = show;
        (show ? welcomeAttentionHolder : dashboardAttention).add(attention, BorderLayout.CENTER);
        root.removeAll();
        root.add(show ? welcome : dashboard, BorderLayout.CENTER);
    }

    private void note(String message, AppContext.Section section, String action) {
        ComposerButton go = Comic.button(action, null);
        go.addActionListener(e -> context.navigator().show(section));
        JPanel line = new JPanel(new BorderLayout(10, 0));
        line.setOpaque(false);
        line.add(Comic.text(message), BorderLayout.CENTER);
        line.add(go, BorderLayout.EAST);
        line.setAlignmentX(0f);
        attention.add(line);
    }

    // ------------------------------------------------------------------ Modell

    /**
     * Eine Zeile der Diagnose: ein Auftragsereignis oder eine Verbindungsprüfung ({@code jobId == null}). Alle Texte
     * sind maskiert.
     */
    record Entry(Instant at, long sequence, JobEvent.Severity severity, JobEvent.Category category, String component,
                 String phase, String code, String message, Integer httpStatus, String proxyRoute, String tlsFinding,
                 String jobId, String sourceId, String partitionKey, String detail) {

        static Entry of(JobEvent e) {
            return new Entry(e.at(), e.sequence(), e.severity(), e.category(), e.component(), e.phase(), e.code(),
                    e.message(), e.httpStatus(), e.proxyRoute(), e.tlsFinding(), e.jobId(), e.sourceId(),
                    e.partitionKey(), e.detail());
        }

        String haystack() {
            return String.join(" ", Stream.of(component, phase, code, message,
                            httpStatus == null ? null : String.valueOf(httpStatus), proxyRoute, tlsFinding, jobId,
                            sourceId, partitionKey).filter(Objects::nonNull).toList())
                    .toLowerCase(Locale.ROOT);
        }
    }

    private static final class EventsModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Zeit", "Schwere", "Komponente", "Phase", "Code", "HTTP",
                "Proxy-Route", "TLS", "Auftrag", "Meldung"};
        private List<Entry> rows = new ArrayList<>();

        void set(List<Entry> entries) {
            if (entries.equals(rows)) {
                return;
            }
            rows = new ArrayList<>(entries);
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
            Entry e = rows.get(row);
            return switch (column) {
                case 0 -> Texts.time(e.at());
                case 1 -> severity(e.severity());
                case 2 -> e.component();
                case 3 -> e.phase();
                case 4 -> e.code();
                case 5 -> e.httpStatus() == null ? null : String.valueOf(e.httpStatus());
                case 6 -> e.proxyRoute();
                case 7 -> e.tlsFinding();
                case 8 -> e.jobId() == null ? "Prüfung " + e.sourceId() : e.jobId();
                default -> e.message();
            };
        }
    }

    /** Fehler rot, Warnungen orange; leere Felder als Strich. */
    private final class SeverityRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            Component c = super.getTableCellRendererComponent(t, value == null ? "–" : value, selected, focus, row,
                    column);
            JobEvent.Severity severity = model.rows.get(row).severity();
            Color color = severity == JobEvent.Severity.ERROR ? Comic.RED
                    : severity == JobEvent.Severity.WARNING ? Comic.ORANGE : Comic.INK;
            c.setForeground(value == null ? Comic.MUTED : color);
            if (c instanceof JLabel label) {
                label.setToolTipText(value == null ? null : value.toString());
            }
            return c;
        }
    }
}
