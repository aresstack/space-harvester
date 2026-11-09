package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.application.terms.RelationConflictException;
import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.RelationType;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.TermRelation;
import com.aresstack.spaceharvester.application.terms.TermRelations;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.font.TextAttribute;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static com.aresstack.spaceharvester.app.ui.TermsView.CONFIRM;
import static com.aresstack.spaceharvester.app.ui.TermsView.CONFLICT;
import static com.aresstack.spaceharvester.app.ui.TermsView.NO_MATCH;
import static com.aresstack.spaceharvester.app.ui.TermsView.NO_RUNTIME;
import static com.aresstack.spaceharvester.app.ui.TermsView.REJECT;
import static com.aresstack.spaceharvester.app.ui.TermsView.REJECTED_BACKGROUND;
import static com.aresstack.spaceharvester.app.ui.TermsView.RELATION_LIMIT;
import static com.aresstack.spaceharvester.app.ui.TermsView.REMOVE;
import static com.aresstack.spaceharvester.app.ui.TermsView.UNDO;

/**
 * Ein Reiter mit Beziehungen zwischen Begriffen unter Begriffe (#120): „Beziehungen“ zeigt alle Arten, „Synonyme“ nur
 * Synonyme und Schreibvarianten. Liste mit Suchfeld und sortierbaren Spalten, Bestätigen, Ablehnen und Entfernen mit
 * Rückgängig sowie „Neue Beziehung …“. Die Arten grenzt der Core ein ({@code RelationSearch.type}); die App leitet
 * nichts ab. Nur auf dem EDT.
 */
final class RelationTab {

    private final AppContext context;
    private final TermsView.Directory directory;
    private final int columnCount;
    /** Gezeigte Arten in der Reihenfolge des Dialogs; leer heißt alle. */
    private final List<RelationType> types;
    private final RelationType presetType;
    private final Function<Relation, List<String>> cells;
    private final String emptyHint;

    private final ComposerButton confirm = Comic.button(CONFIRM, "Die gewählte Beziehung gilt");
    private final ComposerButton reject = Comic.button(REJECT, "Die gewählte Beziehung gilt nicht; sie bleibt als "
            + "abgelehnt sichtbar und kommt nicht wieder");
    private final ComposerButton remove = Comic.button(REMOVE, "Eine manuelle Beziehung ganz löschen, jede andere "
            + "ablehnen");
    private final ComposerButton create = Comic.button(TermsView.NEW_RELATION, "Eine eigene Beziehung zwischen zwei "
            + "Begriffen anlegen; sie gilt sofort als bestätigt");

    private List<Relation> shownRelations = List.of();
    /** Beziehungen der Liste nach Kennung, für Auswahl und Darstellung. */
    private final Map<String, Relation> byId = new HashMap<>();
    /** Stand im Core je Kennung, damit Rückgängig eine entfernte Beziehung wiederherstellen kann. */
    private final Map<String, TermRelation> loaded = new HashMap<>();
    /** Eben entfernte Beziehungen: bleiben durchgestrichen stehen, bis Reiter oder Bereich wechseln. */
    private final Map<String, Relation> removed = new LinkedHashMap<>();
    private final Map<String, TermRelation> removedCore = new HashMap<>();
    /** Nach dem Neuladen wieder zu wählende Beziehung. */
    private String keepSelected;
    private BiConsumer<String, Relation> onReview = (action, relation) -> { };
    private int request;

    /**
     * @param columns sichtbare Spalten; {@code cells} liefert je Beziehung genau so viele Werte
     * @param types   gezeigte Arten; leer heißt alle
     * @param preset  im Dialog „Neue Beziehung“ vorgewählte Art
     */
    RelationTab(AppContext context, String title, String[] columns, Function<Relation, List<String>> cells,
                List<RelationType> types, RelationType preset, String emptyHint) {
        this.context = context;
        this.directory = new TermsView.Directory(title, columns);
        this.columnCount = columns.length;
        this.cells = cells;
        this.types = List.copyOf(types);
        this.presetType = preset;
        this.emptyHint = emptyHint;
        directory.show(List.of(), TermsView.PENDING);
        JPanel review = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        review.setOpaque(false);
        for (ComposerButton button : List.of(confirm, reject, remove)) {
            button.setEnabled(false);
            review.add(button);
        }
        directory.actions(review);
        directory.sortable();
        RelationRenderer renderer = new RelationRenderer();
        for (int c = 0; c < directory.table().getColumnCount(); c++) {
            directory.table().getColumnModel().getColumn(c).setCellRenderer(renderer);
        }
        directory.middle(create);
        create.addActionListener(e -> askNewRelation());
        create.setEnabled(context != null);
        directory.table().getSelectionModel().addListSelectionListener(e -> selectionChanged());
        confirm.addActionListener(e -> review(CONFIRM));
        reject.addActionListener(e -> review(REJECT));
        remove.addActionListener(e -> review(REMOVE));
        if (context != null) {
            directory.onSearch(q -> load(null));
            onReview = this::reviewInCore;
        }
    }

    /** Der Bereich wird sichtbar: entfernte Zeilen sind besiegelt, die Liste lädt neu. */
    void shown() {
        removed.clear();
        removedCore.clear();
        load(null);
    }

    /** Ein Wechsel von Reiter oder Bereich besiegelt das Entfernen: die durchgestrichenen Zeilen verschwinden. */
    void forgetRemoved() {
        if (!removed.isEmpty()) {
            removed.clear();
            removedCore.clear();
            show(shownRelations.stream().filter(r -> byId.containsKey(r.id()) && loaded.containsKey(r.id()))
                    .toList(), emptyHint);
        }
    }

    /**
     * Vorgeschlagene und bestätigte Beziehungen, danach die abgelehnten (sie bleiben sichtbar und kommen nicht wieder),
     * je nach Begriff gefiltert wie im Dienst.
     */
    void load(String notice) {
        if (context == null) {
            return;
        }
        if (context.runtime() == null) {
            directory.show(List.of(), NO_RUNTIME);
            return;
        }
        int current = ++request;
        String q = directory.query();
        String term = q.isEmpty() ? null : q;
        AppRuntime runtime = context.runtime();
        List<RelationType> queried = types.isEmpty() ? java.util.Collections.singletonList(null) : types;
        Background.run(() -> {
            List<TermRelation> open = new ArrayList<>();
            List<TermRelation> rejected = new ArrayList<>();
            boolean more = false;
            for (RelationType type : queried) {
                TermRelations.RelationList o = runtime.workspace().termRelations(term, type, null, RELATION_LIMIT);
                TermRelations.RelationList r = runtime.workspace().termRelations(term, type, ReviewStatus.REJECTED,
                        RELATION_LIMIT);
                open.addAll(o.relations());
                rejected.addAll(r.relations());
                more |= o.nextCursor() != null || r.nextCursor() != null;
            }
            if (queried.size() > 1) {
                // Je Art sortiert der Core nach Schlüssel; zusammengeführt gilt dieselbe Ordnung.
                open.sort(Comparator.comparing(TermRelation::key));
                rejected.sort(Comparator.comparing(TermRelation::key));
            }
            List<Relation> all = new ArrayList<>();
            Map<String, TermRelation> core = new HashMap<>();
            for (List<TermRelation> part : List.of(open, rejected)) {
                for (TermRelation r : part) {
                    all.add(Relation.of(r));
                    core.put(r.id(), r);
                }
            }
            return new Loaded(all, core, more);
        }, found -> {
            if (current == request) {
                loaded.clear();
                loaded.putAll(found.core());
                show(found.list(), q.isEmpty() ? emptyHint : NO_MATCH);
                // Der Core liefert je Status höchstens RELATION_LIMIT; mehr nur mit Hinweis, nicht still gekürzt.
                String message = TermsView.relationsMessage(notice, found.more());
                if (message != null) {
                    directory.message(message);
                }
            }
        }, failure -> {
            if (current == request) {
                show(List.of(), "Beziehungen nicht lesbar: " + failure.getMessage());
            }
        });
    }

    private record Loaded(List<Relation> list, Map<String, TermRelation> core, boolean more) {
    }

    /** Bestätigen, Ablehnen oder Entfernen mit dem gezeigten Stand; der Core prüft Rechte und Revision. */
    private void reviewInCore(String action, Relation relation) {
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            return;
        }
        TermRelation before = removed.containsKey(relation.id()) ? removedCore.get(relation.id())
                : loaded.get(relation.id());
        keepSelected = relation.id();
        Background.run(() -> {
            switch (action) {
                case CONFIRM -> runtime.workspace().confirmRelation(relation.id(), relation.revision());
                case REJECT -> runtime.workspace().rejectRelation(relation.id(), relation.revision());
                case UNDO -> undoRemove(runtime.workspace(), relation, before);
                default -> runtime.workspace().removeRelation(relation.id(), relation.revision());
            }
            return relation;
        }, done -> {
            if (REMOVE.equals(action)) {
                removed.put(relation.id(), relation);
                if (before != null) {
                    removedCore.put(relation.id(), before);
                }
            } else if (UNDO.equals(action)) {
                removed.remove(relation.id());
                removedCore.remove(relation.id());
            }
            load(null);
        }, failure -> load(failure instanceof RelationConflictException
                ? CONFLICT : action + " nicht möglich: " + failure.getMessage()));
    }

    /**
     * Stellt den Stand vor dem Entfernen wieder her: Eine manuelle Beziehung hat der Core gelöscht, sie entsteht neu
     * (gleicher Schlüssel, gleiche Kennung); eine automatische hat er abgelehnt, die Ablehnung wird aufgehoben.
     */
    private static void undoRemove(Workspace workspace, Relation relation, TermRelation before) {
        if (before == null) {
            throw new IllegalStateException("der frühere Stand ist nicht bekannt");
        }
        if (before.origin() == RelationOrigin.MANUAL) {
            TermRelation again = workspace.addRelation(new TermRelations.NewRelation(before.fromTerm(),
                    before.toTerm(), before.type(), before.scope(), before.note(), before.evidence()));
            if (before.status() == ReviewStatus.REJECTED) {
                workspace.rejectRelation(again.id(), again.revision());
            }
        } else if (before.status() != ReviewStatus.REJECTED) {
            workspace.restoreRelation(relation.id());
            if (before.status() == ReviewStatus.CONFIRMED) {
                workspace.confirmRelation(relation.id());
            }
        }
    }

    /** Die Arten im Dialog „Neue Beziehung“: die des Reiters, sonst alle. */
    List<RelationType> dialogTypes() {
        return types.isEmpty() ? List.of(RelationType.values()) : types;
    }

    /** Dialog „Neue Beziehung“: Begriff, Art (vorgewählt die des Reiters), Zu Begriff. */
    private void askNewRelation() {
        javax.swing.JDialog dialog = Dialogs.dialog(directory, "Neue Beziehung");
        javax.swing.JTextField from = Comic.field(20);
        javax.swing.JTextField to = Comic.field(20);
        javax.swing.JComboBox<String> type = Comic.combo();
        List<RelationType> offered = dialogTypes();
        for (RelationType t : offered) {
            type.addItem(Relation.typeText(t.name()));
        }
        type.setSelectedIndex(Math.max(0, offered.indexOf(presetType)));
        JLabel problem = Comic.muted(" ");
        JPanel form = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
        form.setOpaque(false);
        form.add(Comic.caption("Begriff"));
        form.add(from);
        form.add(Comic.caption("Beziehung"));
        form.add(type);
        form.add(Comic.caption("Zu Begriff"));
        form.add(to);
        form.add(problem);
        ComposerButton cancel = Comic.button("Abbrechen", null);
        ComposerButton ok = Comic.primary("Anlegen", "Die Beziehung anlegen und bestätigen");
        cancel.addActionListener(e -> dialog.dispose());
        ok.addActionListener(e -> {
            String invalid = TermsView.invalidNewRelation(from.getText(), to.getText());
            if (invalid != null) {
                problem.setText(invalid);
                return;
            }
            dialog.dispose();
            createRelation(from.getText(), offered.get(type.getSelectedIndex()), to.getText());
        });
        Dialogs.show(dialog, "Neue Beziehung", form, cancel, ok);
    }

    /** Legt die Beziehung im Core an (manuell, bestätigt, global) und wählt sie danach in der Liste. */
    void createRelation(String from, RelationType type, String to) {
        AppRuntime runtime = context == null ? null : context.runtime();
        if (runtime == null) {
            return;
        }
        Background.run(() -> runtime.workspace().addRelation(new TermRelations.NewRelation(from.trim(), to.trim(),
                type, null, null, null)), created -> {
            // Neu angelegt ist sie nicht mehr entfernt, auch wenn sie eben noch durchgestrichen dastand.
            removed.remove(created.id());
            removedCore.remove(created.id());
            keepSelected = created.id();
            load(null);
        }, failure -> directory.message("Anlegen nicht möglich: " + failure.getMessage()));
    }

    /** Grau hinterlegt: abgelehnte; zusätzlich durchgestrichen: eben entfernte. Alle Spalten linksbündig. */
    private final class RelationRenderer extends DefaultTableCellRenderer {

        RelationRenderer() {
            setHorizontalAlignment(SwingConstants.LEFT);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            Relation relation = relationAtView(row);
            boolean gone = relation != null && removed.containsKey(relation.id());
            boolean grey = gone || relation != null && "REJECTED".equals(relation.status());
            if (!selected) {
                setBackground(grey ? REJECTED_BACKGROUND : table.getBackground());
                setForeground(gone ? Comic.MUTED : table.getForeground());
            }
            Font font = table.getFont();
            if (gone) {
                Map<TextAttribute, Object> attributes = new HashMap<>();
                attributes.put(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON);
                font = font.deriveFont(attributes);
            }
            setFont(font);
            return this;
        }
    }

    /** Zeigt Beziehungen; {@code hint} steht da, solange es keine gibt. */
    void show(List<Relation> list, String hint) {
        Relation current = selectedRelation();
        String select = keepSelected != null ? keepSelected : current == null ? null : current.id();
        keepSelected = null;
        List<Relation> merged = new ArrayList<>(list);
        Set<String> ids = new HashSet<>();
        list.forEach(r -> ids.add(r.id()));
        // Entfernte bleiben stehen, bis Reiter oder Bereich wechseln, damit Rückgängig möglich bleibt.
        removed.values().stream().filter(r -> !ids.contains(r.id())).forEach(merged::add);
        shownRelations = List.copyOf(merged);
        byId.clear();
        List<List<String>> rows = new ArrayList<>(merged.size());
        for (Relation relation : merged) {
            byId.put(relation.id(), relation);
            // Die Kennung steht unsichtbar hinter den Spalten.
            List<String> row = new ArrayList<>(cells.apply(relation));
            row.add(relation.id());
            rows.add(List.copyOf(row));
        }
        directory.show(rows, hint);
        if (select != null) {
            JTable table = directory.table();
            for (int row = 0; row < table.getRowCount(); row++) {
                Relation relation = relationAtView(row);
                if (relation != null && relation.id().equals(select)) {
                    table.setRowSelectionInterval(row, row);
                    table.scrollRectToVisible(table.getCellRect(row, 0, true));
                    break;
                }
            }
        }
        selectionChanged();
    }

    private Relation relationAtView(int row) {
        if (row < 0 || row >= directory.table().getRowCount()) {
            return null;
        }
        List<String> row0 = directory.visibleRow(directory.table().convertRowIndexToModel(row));
        return row0.size() > columnCount ? byId.get(row0.get(columnCount)) : null;
    }

    /** {@code listener} bekommt Aktion ({@code CONFIRM}, {@code REJECT}, {@code REMOVE}) und Beziehung. */
    void onReview(BiConsumer<String, Relation> listener) {
        onReview = Objects.requireNonNull(listener, "listener");
    }

    /** Die Schaltflächen passen zum Stand der gewählten Beziehung; Rechte prüft der Core. */
    private void selectionChanged() {
        Relation selected = selectedRelation();
        boolean gone = selected != null && removed.containsKey(selected.id());
        confirm.setEnabled(selected != null && !gone && !"CONFIRMED".equals(selected.status()));
        reject.setEnabled(selected != null && !gone && !"REJECTED".equals(selected.status()));
        remove.setEnabled(selected != null);
        remove.setText(gone ? UNDO : REMOVE);
        remove.setToolTipText(gone ? "Das Entfernen zurücknehmen" : "Eine manuelle Beziehung ganz löschen, jede "
                + "andere ablehnen");
    }

    private Relation selectedRelation() {
        return relationAtView(directory.table().getSelectedRow());
    }

    private void review(String action) {
        Relation selected = selectedRelation();
        if (selected != null) {
            onReview.accept(REMOVE.equals(action) && removed.containsKey(selected.id()) ? UNDO : action, selected);
        }
    }

    /** Ob die Beziehung eben entfernt wurde und noch durchgestrichen dasteht. */
    boolean isRemoved(String id) {
        return removed.containsKey(id);
    }

    TermsView.Directory directory() {
        return directory;
    }

    ComposerButton createButton() {
        return create;
    }

    List<ComposerButton> reviewButtons() {
        return List.of(confirm, reject, remove);
    }
}
