package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton;
import com.aresstack.spaceharvester.app.event.Severity;
import com.aresstack.spaceharvester.app.event.StatusMessageEvent;
import com.aresstack.spaceharvester.app.runtime.GlossaryAccess;
import com.aresstack.spaceharvester.app.runtime.GlossaryAccess.Entry;
import com.aresstack.spaceharvester.app.ui.controls.BusyOverlay;
import com.aresstack.spaceharvester.app.ui.controls.InfoPopup;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.terms.RelationEvidence;
import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.TermQueries;

import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Der Reiter „Fachglossar“ unter Begriffe (#122 Stufe 2, Spezifikation Abschnitt 2.5): die Verwaltungsansicht der
 * dauerhaft gepflegten Glossareinträge. Suchbare, sortierbare Liste mit Geltungsbereich, Herkunft und Prüfstand,
 * Filter Vorgeschlagen · Bestätigt · Abgelehnt neben dem Suchfeld, Zeilenaktionen als Symbole in der Zeile
 * (Bestätigen, Ablehnen bzw. Ablehnung zurücknehmen, Erläuterung bearbeiten, Entfernen) wie im Reiter „Beziehungen“,
 * Belege mit Kontext als Fenster am Mausklick. „Fachbegriffe ermitteln“ und „Fachbegriff hinzufügen …“ stehen oben
 * rechts in der Reiterzeile ({@link TermsView}); der KI-Lauf ist ein Auftrag des Core mit Fortschritt im Schleier
 * ({@link BusyOverlay}) und Abbrechen per Esc, Rückmeldungen gehen in die Statuszeile des Hauptfensters. Die App
 * leitet nichts ab; der Core prüft Rechte, Revision und die Schutzregeln. Nur auf dem EDT.
 */
final class GlossaryTab {

    static final String DETECT = "Fachbegriffe ermitteln";
    static final String ADD = "Fachbegriff hinzufügen …";
    static final String CONFIRM = "Bestätigen";
    static final String REJECT = "Ablehnen";
    static final String RESTORE = "Ablehnung zurücknehmen";
    static final String EDIT = "Erläuterung bearbeiten";
    static final String REMOVE = "Entfernen";
    static final String ONLY_MANUAL = "Nur manuelle Einträge lassen sich löschen; einen KI-Vorschlag ablehnen";
    static final String ALREADY_CONFIRMED = "Schon bestätigt";
    static final String ALREADY_REJECTED = "Schon abgelehnt";
    static final String NO_AI = "Kein KI-Dienst eingerichtet; Fachbegriffe lassen sich von Hand anlegen";
    static final String RUNNING = "Ein Lauf läuft schon";
    static final String EMPTY = "Noch keine Fachbegriffe. „Fachbegriffe ermitteln“ lässt die KI die Bezeichner und "
            + "Akronyme der indexierten Seiten beurteilen; „Fachbegriff hinzufügen …“ legt einen von Hand an.";
    static final String NO_MATCH = "Kein Fachbegriff passt zu Suche und Filter.";
    static final String CONFLICT = "Der Eintrag wurde inzwischen geändert; die Liste ist neu geladen.";
    static final String STALE = "veraltet";
    static final String ALL = "Alle";
    static final int LIMIT = GlossaryAccess.MAX_LIMIT;
    /** Zeichen Kontext je Beleg im Fenster. */
    static final int SNIPPET_CHARS = 120;
    /** Höchstens so viele Belege zeigt das Fenster, mit Link zur Seite. */
    static final int EVIDENCE_SHOWN = 3;
    static final int POLL_MS = 700;
    static final int NOTICE_MS = 8000;
    static final String[] COLUMNS = {"Begriff", "Geltungsbereich", "Herkunft", "Status", "Belege", "Erläuterung", ""};
    static final int ACTIONS_COLUMN = COLUMNS.length - 1;
    static final int ACTION_COUNT = 4;

    private final AppContext context;
    private final Supplier<Optional<GlossaryAccess>> access;
    private final TermsView.Directory directory = new TermsView.Directory("Fachglossar", COLUMNS);
    private final BusyOverlay overlay = new BusyOverlay(directory);
    private final ComposerButton detect = Comic.primary(DETECT, "Die KI beurteilt die Bezeichner und Akronyme der "
            + "indexierten Seiten; nur ungeprüfte Vorschläge ändern sich");
    private final ComposerButton add = Comic.button(ADD, "Einen Fachbegriff von Hand anlegen; er gilt sofort als "
            + "bestätigt");
    private final Map<String, ComposerToggleButton> filters = new LinkedHashMap<>();
    private ReviewStatus statusFilter;
    private final Map<String, Entry> byId = new HashMap<>();
    private final List<Entry> shown = new ArrayList<>();
    private String nextCursor;
    private boolean loading;
    private boolean aiAvailable;
    private int request;
    private String keepSelected;
    /** Die laufenden Aufträge „Fachbegriffe ermitteln“ (je Quelle einer) mit ihrem letzten Stand; leer ohne Lauf. */
    private final Map<String, JobStatus> jobs = new LinkedHashMap<>();
    private final Timer poll = new Timer(POLL_MS, e -> pollJob());

    /**
     * @param context Laufzeit für Statuszeile, Quellenwahl und Navigation; {@code null} zeigt nur das Gerüst (Tests)
     * @param access  das Glossar des Core, leer solange es keinen Unterbau gibt
     */
    GlossaryTab(AppContext context, Supplier<Optional<GlossaryAccess>> access) {
        this.context = context;
        this.access = access;
        poll.setRepeats(false);
        directory.show(List.of(), TermsView.NOT_AVAILABLE);
        directory.sortable();
        directory.middle(filterBar());
        JTable table = directory.table();
        GlossaryRenderer renderer = new GlossaryRenderer();
        for (int c = 0; c < ACTIONS_COLUMN; c++) {
            table.getColumnModel().getColumn(c).setCellRenderer(renderer);
        }
        table.getColumnModel().getColumn(0).setPreferredWidth(180);
        table.getColumnModel().getColumn(5).setPreferredWidth(260);
        RowActions.install(table, ACTIONS_COLUMN, ACTION_COUNT, row -> actions(entryAtModel(row)));
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0 && e.getClickCount() == 1 && javax.swing.SwingUtilities.isLeftMouseButton(e)
                        && table.columnAtPoint(e.getPoint()) != table.convertColumnIndexToView(ACTIONS_COLUMN)) {
                    Entry entry = entryAtView(row);
                    if (entry != null) {
                        showDetails(entry, e.getX(), e.getY());
                    }
                }
            }
        });
        detect.setEnabled(false);
        detect.setToolTipText(NO_AI);
        add.setEnabled(false);
        detect.addActionListener(e -> askDetect());
        add.addActionListener(e -> askNew());
        overlay.onCancel(this::cancelJob);
        directory.onSearch(q -> load());
        directory.onScrolledToEnd(this::more);
    }

    private JPanel filterBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 0));
        bar.setOpaque(false);
        ButtonGroup group = new ButtonGroup();
        Map<String, ReviewStatus> choices = new LinkedHashMap<>();
        choices.put(ALL, null);
        choices.put(Relation.statusText("PROPOSED"), ReviewStatus.PROPOSED);
        choices.put(Relation.statusText("CONFIRMED"), ReviewStatus.CONFIRMED);
        choices.put(Relation.statusText("REJECTED"), ReviewStatus.REJECTED);
        choices.forEach((label, status) -> {
            ComposerToggleButton button = new ComposerToggleButton(null, label, status == null
                    ? "Alle Einträge" : "Nur " + label.toLowerCase(java.util.Locale.GERMAN) + "e Einträge");
            button.setSelected(status == null);
            button.addActionListener(e -> {
                statusFilter = status;
                load();
            });
            group.add(button);
            filters.put(label, button);
            bar.add(button);
        });
        return bar;
    }

    /** Der Bereich wird sichtbar oder der Reiter gewählt: die Liste lädt neu. */
    void shown() {
        load();
    }

    /** Lädt die erste Seite nach Suche und Filter; weitere kommen beim Scrollen. */
    void load() {
        load(null);
    }

    /** Wie {@link #load()}; {@code notice} steht danach unter der Tabelle, etwa nach einer abgelehnten Änderung. */
    void load(String notice) {
        Optional<GlossaryAccess> glossary = access.get();
        if (glossary.isEmpty()) {
            directory.show(List.of(), TermsView.NOT_AVAILABLE);
            add.setEnabled(false);
            detect.setEnabled(false);
            detect.setToolTipText(NO_AI);
            return;
        }
        int current = ++request;
        nextCursor = null;
        loading = true;
        String q = directory.query();
        GlossaryAccess.Search search = new GlossaryAccess.Search(q.isEmpty() ? null : q, statusFilter, null, null,
                null, null, LIMIT, null);
        Background.run(() -> glossary.get().search(search), page -> {
            if (current != request) {
                return;
            }
            loading = false;
            aiAvailable = page.aiAvailable();
            add.setEnabled(true);
            updateDetect();
            show(page.entries(), q.isEmpty() && statusFilter == null ? EMPTY : NO_MATCH);
            nextCursor = page.nextCursor();
            directory.more(nextCursor != null);
            if (notice != null) {
                directory.message(notice);
            }
        }, failure -> {
            if (current == request) {
                loading = false;
                show(List.of(), "Fachglossar nicht lesbar: " + failure.getMessage());
            }
        });
    }

    private void more() {
        Optional<GlossaryAccess> glossary = access.get();
        if (loading || nextCursor == null || glossary.isEmpty()) {
            return;
        }
        int current = request;
        loading = true;
        String q = directory.query();
        GlossaryAccess.Search search = new GlossaryAccess.Search(q.isEmpty() ? null : q, statusFilter, null, null,
                null, null, LIMIT, nextCursor);
        Background.run(() -> glossary.get().search(search), page -> {
            if (current != request) {
                return;
            }
            loading = false;
            for (Entry entry : page.entries()) {
                byId.put(entry.id(), entry);
                shown.add(entry);
            }
            directory.append(rows(page.entries()));
            nextCursor = page.nextCursor();
            directory.more(nextCursor != null);
        }, failure -> {
            if (current == request) {
                loading = false;
                directory.message("Fachglossar nicht lesbar: " + failure.getMessage());
            }
        });
    }

    private void updateDetect() {
        boolean running = !jobs.isEmpty();
        detect.setEnabled(aiAvailable && !running);
        detect.setToolTipText(!aiAvailable ? NO_AI : running ? RUNNING : "Die KI beurteilt die Bezeichner und "
                + "Akronyme der indexierten Seiten; nur ungeprüfte Vorschläge ändern sich");
    }

    /** Zeigt die Einträge; die gewählte Zeile bleibt gewählt, wenn es sie noch gibt. */
    void show(List<Entry> entries, String emptyHint) {
        Entry current = selectedEntry();
        String select = keepSelected != null ? keepSelected : current == null ? null : current.id();
        keepSelected = null;
        byId.clear();
        shown.clear();
        for (Entry entry : entries) {
            byId.put(entry.id(), entry);
            shown.add(entry);
        }
        directory.show(rows(entries), emptyHint);
        if (select != null) {
            JTable table = directory.table();
            for (int row = 0; row < table.getRowCount(); row++) {
                Entry entry = entryAtView(row);
                if (entry != null && entry.id().equals(select)) {
                    table.setRowSelectionInterval(row, row);
                    table.scrollRectToVisible(table.getCellRect(row, 0, true));
                    break;
                }
            }
        }
    }

    /** Je Eintrag Begriff, Geltungsbereich, Herkunft, Status, Belege, Erläuterung, leer; unsichtbar die Kennung. */
    static List<List<String>> rows(List<Entry> entries) {
        List<List<String>> rows = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            List<String> row = new ArrayList<>(List.of(entry.term(), scopeText(entry),
                    Relation.originText(entry.origin().name()), Relation.statusText(entry.status().name()),
                    evidenceText(entry), entry.explanation() == null ? "" : entry.explanation(), ""));
            row.add(entry.id());
            rows.add(List.copyOf(row));
        }
        return rows;
    }

    /** „Alle Quellen“, „handbuch“ oder „handbuch · DOK“. */
    static String scopeText(Entry entry) {
        if (entry.sourceId() == null) {
            return "Alle Quellen";
        }
        return entry.spaceKey() == null ? entry.sourceId() : entry.sourceId() + " · " + entry.spaceKey();
    }

    /** „3“, „3 · veraltet“ oder „–“ ohne Belege. */
    static String evidenceText(Entry entry) {
        if (entry.evidence().isEmpty()) {
            return "–";
        }
        return entry.evidence().size() + (entry.evidenceStale() ? " · " + STALE : "");
    }

    /** Die Symbole einer Zeile: Bestätigen, Ablehnen bzw. Ablehnung zurücknehmen, Erläuterung, Entfernen. */
    List<RowActions.Action> actions(Entry entry) {
        if (entry == null) {
            return List.of();
        }
        boolean confirmed = entry.status() == ReviewStatus.CONFIRMED;
        boolean rejected = entry.status() == ReviewStatus.REJECTED;
        boolean manual = entry.origin() == RelationOrigin.MANUAL;
        return List.of(
                new RowActions.Action(Glyph.ACCEPT, confirmed ? ALREADY_CONFIRMED : CONFIRM, !confirmed,
                        () -> review(CONFIRM, entry)),
                rejected ? new RowActions.Action(Glyph.REFRESH, RESTORE, true, () -> review(RESTORE, entry))
                        : new RowActions.Action(Glyph.CANCEL, REJECT, true, () -> review(REJECT, entry)),
                new RowActions.Action(Glyph.PENCIL, EDIT, true, () -> askExplanation(entry)),
                new RowActions.Action(Glyph.TRASH, manual ? REMOVE : ONLY_MANUAL, manual,
                        () -> review(REMOVE, entry)));
    }

    /** Bestätigen, Ablehnen, Zurücknehmen oder Entfernen mit dem gezeigten Stand; der Core prüft Revision und Rechte. */
    void review(String action, Entry entry) {
        Optional<GlossaryAccess> glossary = access.get();
        if (glossary.isEmpty()) {
            return;
        }
        keepSelected = entry.id();
        Background.run(() -> {
            GlossaryAccess g = glossary.get();
            return switch (action) {
                case CONFIRM -> g.confirm(entry.id(), entry.revision());
                case REJECT -> g.reject(entry.id(), entry.revision());
                case RESTORE -> g.restore(entry.id(), entry.revision());
                default -> g.remove(entry.id(), entry.revision()).orElse(null);
            };
        }, done -> {
            notice(REMOVE.equals(action) && done == null ? "„" + entry.term() + "“ entfernt"
                    : "„" + entry.term() + "“: " + Relation.statusText(done.status().name()), Severity.INFO);
            load();
        }, failure -> load(notice(failure instanceof GlossaryAccess.ConflictException ? CONFLICT
                : action + " nicht möglich: " + failure.getMessage(), Severity.ERROR)));
    }

    // ---- Dialoge

    /** „Fachbegriff hinzufügen …“: Begriff, Geltungsbereich (Quelle, Space), Erläuterung. */
    private void askNew() {
        if (context == null || access.get().isEmpty()) {
            return;
        }
        javax.swing.JDialog dialog = Dialogs.dialog(directory, ADD);
        JTextField term = Comic.field(24);
        SourcePicker scope = new SourcePicker(context, true, true);
        JTextArea explanation = explanationArea("");
        JLabel problem = Comic.muted(" ");
        JPanel form = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
        form.setOpaque(false);
        form.add(Comic.caption("Fachbegriff"));
        form.add(term);
        form.add(Comic.caption("Gilt für Quelle"));
        form.add(scope.sourceBox());
        form.add(Comic.caption("Gilt für Space (leer: alle)"));
        form.add(scope.spaceBox());
        form.add(Comic.caption("Erläuterung (optional)"));
        form.add(Comic.scroll(explanation));
        form.add(problem);
        ComposerButton cancel = Comic.button("Abbrechen", null);
        ComposerButton ok = Comic.primary("Anlegen", "Den Fachbegriff anlegen; er gilt sofort als bestätigt");
        cancel.addActionListener(e -> dialog.dispose());
        ok.addActionListener(e -> {
            String invalid = invalidTerm(term.getText());
            if (invalid != null) {
                problem.setText(invalid);
                return;
            }
            dialog.dispose();
            String space = scope.spaceKey();
            create(new GlossaryAccess.NewEntry(term.getText().trim(), scope.sourceId(),
                    space == null || space.isBlank() ? null : space, blankToNull(explanation.getText())));
        });
        Dialogs.show(dialog, ADD, form, cancel, ok);
    }

    /** {@code null}, wenn der Begriff taugt, sonst der Grund in Worten. */
    static String invalidTerm(String term) {
        return term == null || term.isBlank() ? "Den Fachbegriff angeben." : null;
    }

    static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    /** Legt den Eintrag im Core an (manuell, bestätigt) und wählt ihn danach. */
    void create(GlossaryAccess.NewEntry entry) {
        Optional<GlossaryAccess> glossary = access.get();
        if (glossary.isEmpty()) {
            return;
        }
        Background.run(() -> glossary.get().add(entry), created -> {
            keepSelected = created.id();
            notice("„" + created.term() + "“ angelegt", Severity.INFO);
            load();
        }, failure -> directory.message(notice("Anlegen nicht möglich: " + failure.getMessage(), Severity.ERROR)));
    }

    /** „Erläuterung bearbeiten“: nur der Text; Begriff und Geltungsbereich bleiben. */
    private void askExplanation(Entry entry) {
        if (context == null || access.get().isEmpty()) {
            return;
        }
        javax.swing.JDialog dialog = Dialogs.dialog(directory, EDIT);
        JTextArea explanation = explanationArea(entry.explanation() == null ? "" : entry.explanation());
        JPanel form = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
        form.setOpaque(false);
        form.add(Comic.caption("Fachbegriff"));
        form.add(Comic.text(entry.term()));
        form.add(Comic.caption("Erläuterung"));
        form.add(Comic.scroll(explanation));
        ComposerButton cancel = Comic.button("Abbrechen", null);
        ComposerButton ok = Comic.primary("Speichern", "Die Erläuterung übernehmen");
        cancel.addActionListener(e -> dialog.dispose());
        ok.addActionListener(e -> {
            dialog.dispose();
            explain(entry, blankToNull(explanation.getText()));
        });
        Dialogs.show(dialog, EDIT, form, cancel, ok);
    }

    void explain(Entry entry, String text) {
        Optional<GlossaryAccess> glossary = access.get();
        if (glossary.isEmpty()) {
            return;
        }
        keepSelected = entry.id();
        Background.run(() -> glossary.get().explanation(entry.id(), text, entry.revision()), saved -> {
            notice("Erläuterung zu „" + saved.term() + "“ gespeichert", Severity.INFO);
            load();
        }, failure -> load(notice(failure instanceof GlossaryAccess.ConflictException ? CONFLICT
                : "Speichern nicht möglich: " + failure.getMessage(), Severity.ERROR)));
    }

    private static JTextArea explanationArea(String text) {
        JTextArea area = new JTextArea(text, 4, 30);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        Comic.style(area);
        return area;
    }

    /** „Fachbegriffe ermitteln“: Umfang (alle Quellen, eine Quelle, ein Space), dann der Auftrag. */
    private void askDetect() {
        if (context == null || access.get().isEmpty() || !jobs.isEmpty()) {
            return;
        }
        javax.swing.JDialog dialog = Dialogs.dialog(directory, DETECT);
        SourcePicker scope = new SourcePicker(context, true, true);
        JPanel form = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
        form.setOpaque(false);
        form.add(Comic.hint("Die KI beurteilt die Bezeichner und Akronyme der bereits indexierten Seiten mit ihren "
                + "Fundstellen. Fehlende Seiten lädt der Lauf nicht nach. Manuelle, bestätigte und abgelehnte "
                + "Einträge bleiben, wie sie sind; nur ungeprüfte Vorschläge ändern sich."));
        form.add(Comic.caption("Quelle"));
        form.add(scope.sourceBox());
        form.add(Comic.caption("Space (leer: alle Spaces der Quelle)"));
        form.add(scope.spaceBox());
        ComposerButton cancel = Comic.button("Abbrechen", null);
        ComposerButton ok = Comic.primary("Ermitteln", "Den KI-Lauf starten; Abbrechen jederzeit mit Esc");
        cancel.addActionListener(e -> dialog.dispose());
        ok.addActionListener(e -> {
            dialog.dispose();
            String space = scope.spaceKey();
            detect(scope.sourceId(), space == null || space.isBlank() ? null : space);
        });
        Dialogs.show(dialog, DETECT, form, cancel, ok);
    }

    /** Startet den Lauf im Core (je Quelle ein Auftrag) und zeigt den Schleier mit Fortschritt, bis alle enden. */
    void detect(String sourceId, String spaceKey) {
        Optional<GlossaryAccess> glossary = access.get();
        if (glossary.isEmpty() || !jobs.isEmpty()) {
            return;
        }
        overlay.setText(DETECT + " · wird gestartet …");
        overlay.setBusy(true);
        Background.run(() -> glossary.get().classify(sourceId, spaceKey), started -> {
            if (started.isEmpty()) {
                overlay.setBusy(false);
                return;
            }
            started.forEach(status -> jobs.put(status.id(), status));
            updateDetect();
            started.forEach(this::track);
        }, failure -> {
            overlay.setBusy(false);
            directory.message(notice(failure instanceof GlossaryAccess.AiUnavailableException ? NO_AI
                    : failure instanceof GlossaryAccess.NotAllowedException ? failure.getMessage()
                    : DETECT + " nicht möglich: " + failure.getMessage(), Severity.ERROR));
        });
    }

    private void pollJob() {
        Optional<GlossaryAccess> glossary = access.get();
        List<String> open = jobs.values().stream().filter(s -> !s.state().terminal()).map(JobStatus::id).toList();
        if (open.isEmpty() || glossary.isEmpty()) {
            return;
        }
        Background.run(() -> {
            List<JobStatus> states = new ArrayList<>();
            for (String id : open) {
                states.add(glossary.get().classification(id));
            }
            return states;
        }, states -> states.forEach(this::track), failure -> finish(failure));
    }

    /** Fortschritt aller Aufträge im Schleier; sind alle zu Ende, Rückmeldung in die Statuszeile und die Liste neu. */
    void track(JobStatus status) {
        if (!jobs.containsKey(status.id())) {
            return;
        }
        jobs.put(status.id(), status);
        if (jobs.values().stream().allMatch(s -> s.state().terminal())) {
            finish(null);
            return;
        }
        overlay.setText(progressText(List.copyOf(jobs.values())));
        poll.restart();
    }

    private void finish(Throwable failure) {
        poll.stop();
        List<JobStatus> ended = List.copyOf(jobs.values());
        jobs.clear();
        overlay.setBusy(false);
        updateDetect();
        if (failure != null) {
            load(notice(DETECT + ": Stand nicht lesbar: " + failure.getMessage(), Severity.ERROR));
        } else {
            JobState worst = ended.stream().map(JobStatus::state).anyMatch(s -> s == JobState.FAILED)
                    ? JobState.FAILED : ended.stream().map(JobStatus::state).anyMatch(s -> s == JobState.CANCELLED)
                    ? JobState.CANCELLED : JobState.COMPLETED;
            Severity severity = worst == JobState.FAILED ? Severity.ERROR
                    : worst == JobState.CANCELLED ? Severity.WARN : Severity.INFO;
            String text = notice(outcomeText(ended), severity);
            load(severity == Severity.INFO ? null : text);
        }
    }

    /** Fortschritt über alle Aufträge: Summen je Zähler; abgebrochen wird gemeldet, sobald einer es ist. */
    static String progressText(List<JobStatus> states) {
        long discovered = 0;
        long processed = 0;
        long written = 0;
        boolean cancelling = false;
        for (JobStatus status : states) {
            discovered += status.progress().discovered();
            processed += status.progress().processed();
            written += status.progress().written();
            cancelling |= status.cancelRequested();
        }
        String text = progressText(new JobProgress(discovered, processed, written, 0), cancelling);
        return states.size() > 1 ? text + " · " + states.size() + " Quellen" : text;
    }

    /** „Fachbegriffe ermitteln · 12 von 40 Begriffen · 3 Einträge“; ohne Zahlen „Begriffe werden gesammelt …“. */
    static String progressText(JobProgress progress, boolean cancelRequested) {
        StringBuilder text = new StringBuilder(DETECT);
        if (progress.discovered() == 0) {
            text.append(" · Begriffe werden gesammelt …");
        } else {
            text.append(" · ").append(progress.processed()).append(" von ").append(progress.discovered())
                    .append(" Begriffen · ").append(Texts.count(progress.written(), "Eintrag", "Einträge"));
        }
        if (cancelRequested) {
            text.append(" · wird abgebrochen …");
        }
        return text.toString();
    }

    /** Die Rückmeldung am Ende: fehlgeschlagen vor abgebrochen vor abgeschlossen, mit den Summen aller Aufträge. */
    static String outcomeText(List<JobStatus> ended) {
        long processed = ended.stream().mapToLong(s -> s.progress().processed()).sum();
        long writtenCount = ended.stream().mapToLong(s -> s.progress().written()).sum();
        String written = Texts.count(writtenCount, "Eintrag", "Einträge");
        Optional<JobStatus> failed = ended.stream().filter(s -> s.state() == JobState.FAILED).findFirst();
        if (failed.isPresent()) {
            JobStatus status = failed.get();
            return DETECT + " fehlgeschlagen" + (ended.size() > 1 ? " (Quelle " + status.sourceId() + ")" : "")
                    + ": " + (status.error() == null ? "" : status.error().message())
                    + " · Entscheidungen unverändert";
        }
        if (ended.stream().anyMatch(s -> s.state() == JobState.CANCELLED)) {
            return DETECT + " abgebrochen; " + written + " " + (writtenCount == 1 ? "bleibt" : "bleiben")
                    + ", Entscheidungen unverändert";
        }
        return DETECT + " abgeschlossen: " + written + " aus "
                + Texts.count(processed, "geprüften Begriff", "geprüften Begriffen");
    }

    private void cancelJob() {
        Optional<GlossaryAccess> glossary = access.get();
        List<String> open = jobs.values().stream().filter(s -> !s.state().terminal()).map(JobStatus::id).toList();
        if (open.isEmpty() || glossary.isEmpty()) {
            overlay.setBusy(false);
            return;
        }
        Background.run(() -> {
            List<JobStatus> states = new ArrayList<>();
            for (String id : open) {
                states.add(glossary.get().cancelClassification(id));
            }
            return states;
        }, states -> states.forEach(this::track),
                failure -> directory.message(notice("Abbrechen nicht möglich: " + failure.getMessage(),
                        Severity.ERROR)));
    }

    // ---- Belege

    /** Fenster am Mausklick: Erläuterung, Herkunft und Stand, Belege mit Kontext, Links zur Seite. */
    private void showDetails(Entry entry, int x, int y) {
        Optional<GlossaryAccess> glossary = access.get();
        if (glossary.isEmpty()) {
            return;
        }
        List<RelationEvidence> evidence = entry.evidence().stream().limit(EVIDENCE_SHOWN).toList();
        Background.run(() -> {
            List<String> lines = new ArrayList<>();
            List<String> titles = new ArrayList<>();
            for (RelationEvidence e : evidence) {
                Optional<TermQueries.Snippet> snippet = glossary.get().snippet(e, SNIPPET_CHARS);
                String title = glossary.get().pageTitle(e.sourceId(), e.pageId()).orElse(e.pageId());
                titles.add(title);
                lines.add(title + ": " + snippet.map(GlossaryTab::snippetText)
                        .orElse("Kontext nicht mehr lesbar (Seite geändert oder nicht im Cache)"));
            }
            return new Details(lines, titles);
        }, details -> {
            InfoPopup popup = new InfoPopup().text(entry.term(), detailText(entry, details.lines()));
            for (int i = 0; i < evidence.size(); i++) {
                RelationEvidence e = evidence.get(i);
                popup.link("Zur Seite „" + shorten(details.titles().get(i)) + "“",
                        () -> context.navigator().openPage(e.sourceId(), e.pageId()));
            }
            popup.showAt(directory.table(), x, y);
        }, failure -> directory.message(notice("Belege nicht lesbar: " + failure.getMessage(), Severity.ERROR)));
    }

    private record Details(List<String> lines, List<String> titles) {
    }

    static String snippetText(TermQueries.Snippet s) {
        return ((s.truncatedBefore() ? "…" : "") + s.before() + s.match() + s.after()
                + (s.truncatedAfter() ? "…" : "")).replaceAll("\\s+", " ").trim();
    }

    private static String shorten(String title) {
        return title.length() > 40 ? title.substring(0, 39) + "…" : title;
    }

    /** Der Text des Fensters: Erläuterung, Geltungsbereich, Herkunft und Stand, Belege. */
    static String detailText(Entry entry, List<String> evidenceLines) {
        StringBuilder text = new StringBuilder();
        if (entry.explanation() != null && !entry.explanation().isBlank()) {
            text.append(entry.explanation().trim()).append("\n\n");
        }
        text.append("Gilt für: ").append(scopeText(entry)).append('\n');
        text.append("Herkunft: ").append(Relation.originText(entry.origin().name()));
        if (entry.generation() != null && !entry.generation().isBlank()) {
            text.append(" (").append(entry.generation()).append(')');
        }
        text.append(" · Stand: ").append(Relation.statusText(entry.status().name()));
        if (entry.reviewedAt() != null) {
            text.append(" am ").append(Texts.time(entry.reviewedAt()));
        }
        text.append('\n');
        if (entry.evidence().isEmpty()) {
            text.append("Keine Belegstellen im Begriffsindex.");
        } else {
            text.append(Texts.count(entry.evidence().size(), "Beleg", "Belege"));
            if (entry.evidenceStale()) {
                text.append(" · Beleg veraltet: die Fundstellen haben sich geändert; der Eintrag bleibt");
            }
            for (String line : evidenceLines) {
                text.append("\n• ").append(line);
            }
            if (entry.evidence().size() > evidenceLines.size()) {
                text.append("\n… und ").append(entry.evidence().size() - evidenceLines.size()).append(" weitere");
            }
        }
        return text.toString();
    }

    // ---- Hilfen

    /** Meldet {@code text} in der Statuszeile des Hauptfensters und gibt ihn zurück (für den Hinweis unter der Liste). */
    private String notice(String text, Severity severity) {
        if (context != null) {
            context.events().publish(new StatusMessageEvent(text, NOTICE_MS, severity));
        }
        return text;
    }

    private Entry entryAtModel(int modelRow) {
        if (modelRow < 0 || modelRow >= directory.table().getModel().getRowCount()) {
            return null;
        }
        List<String> cells = directory.visibleRow(modelRow);
        return cells.size() > COLUMNS.length ? byId.get(cells.get(COLUMNS.length)) : null;
    }

    Entry entryAtView(int viewRow) {
        if (viewRow < 0 || viewRow >= directory.table().getRowCount()) {
            return null;
        }
        return entryAtModel(directory.table().convertRowIndexToModel(viewRow));
    }

    private Entry selectedEntry() {
        return entryAtView(directory.table().getSelectedRow());
    }

    /** Grau hinterlegt: abgelehnte; orange: veraltete Belege. Alle Spalten linksbündig. */
    private final class GlossaryRenderer extends DefaultTableCellRenderer {

        GlossaryRenderer() {
            setHorizontalAlignment(SwingConstants.LEFT);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            Entry entry = entryAtView(row);
            boolean rejected = entry != null && entry.status() == ReviewStatus.REJECTED;
            boolean stale = entry != null && entry.evidenceStale() && column == 4;
            if (!selected) {
                setBackground(rejected ? TermsView.REJECTED_BACKGROUND : table.getBackground());
                setForeground(stale ? Comic.ORANGE : rejected ? Comic.MUTED : table.getForeground());
            }
            setToolTipText(stale ? "Beleg veraltet: die Fundstellen haben sich seit dem Beleg geändert" : null);
            return this;
        }
    }

    JComponent component() {
        return overlay;
    }

    ComposerButton detectButton() {
        return detect;
    }

    ComposerButton addButton() {
        return add;
    }

    // ---- für Tests

    TermsView.Directory directory() {
        return directory;
    }

    BusyOverlay overlay() {
        return overlay;
    }

    ComposerToggleButton filter(String label) {
        return filters.get(label);
    }

    List<String> jobIds() {
        return List.copyOf(jobs.keySet());
    }

    List<Entry> shownEntries() {
        return List.copyOf(shown);
    }
}
