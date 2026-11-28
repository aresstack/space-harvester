package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.runtime.GlossaryAccess;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.ui.controls.BusyOverlay;
import com.aresstack.spaceharvester.app.ui.controls.ComicDivider;
import com.aresstack.spaceharvester.app.ui.controls.CountChip;
import com.aresstack.spaceharvester.app.ui.controls.InputFrame;
import com.aresstack.spaceharvester.application.glossary.GlossaryEntry;
import com.aresstack.spaceharvester.application.glossary.TechnicalTerms;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.TermRelation;
import com.aresstack.spaceharvester.application.terms.TermText;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Suchmethode „Nur Fachbegriffe“ (K5b, Angelo 21.11.2026; Form UX-Manager): statt der Treffertabelle zweigeteilt links
 * die Fachbegriffe des Glossars zum Suchtext, rechts die Synonyme des gewählten. Jede Zeile hat einen Zustandspunkt
 * (grün bestätigt, ohne Punkt Vorschlag), den Begriff und die Symbole Bestätigen und Ablehnen; dieselben Aufrufe wie
 * die Reiter „Fachglossar“ und „Beziehungen“. Ablehnen wirkt sofort ohne Rückfrage: die Seite lädt neu, der nächste
 * rückt nach, die Ansicht springt nicht. Unter den Synonymen ein Feld „Synonym hinzufügen …“ (Enter, Plus).
 *
 * <p>Neu gelesen wird nur auf Meldung (ADR 0027): Glossar und Beziehungen melden jede Änderung, auch eine aus einem
 * anderen Reiter. Kein Zeitgeber.
 */
final class TechnicalTermsPanel extends JPanel {

    static final String TERMS_TITLE = "Fachbegriffe";
    static final String TERMS_WORD = "Treffer";
    static final String SYNONYMS_WORD = "Synonyme";
    static final String ADD_PLACEHOLDER = "Synonym hinzufügen …";
    static final String ADD = "Synonym hinzufügen";
    static final String ADD_DISABLED = "Erst links einen Fachbegriff wählen";
    static final String CONFIRMED_TIP = "bestätigt";
    static final String PROPOSED_TIP = "Vorschlag";
    /** Zeilen je Stapel; beim Neuladen nach einer Änderung so viele wie schon gezeigt, über mehrere Abrufe. */
    static final int PAGE = 50;
    static final int MAX = TechnicalTerms.MAX_LIMIT;
    static final String SAME_AS_TERM = "gleich dem Begriff";
    static final String ALREADY_THERE = "schon vorhanden";

    /** Was die Ansicht vom Core braucht; im Betrieb {@link #of(Workspace)}, in Tests ein Doppel. */
    interface Access {

        TechnicalTerms.Page terms(String q, TechnicalTerms.Scope scope, int limit, String cursor);

        /** Die nicht abgelehnten Synonyme des Begriffs, deren Bereich ihn abdeckt. */
        List<TermRelation> synonyms(GlossaryEntry term);

        /** Der Prüfstatus des Begriffs laut Core; leer, wenn es ihn nicht mehr gibt. */
        java.util.Optional<ReviewStatus> status(GlossaryEntry term);

        void confirmTerm(GlossaryEntry term);

        void rejectTerm(GlossaryEntry term);

        void confirmSynonym(TermRelation synonym);

        void rejectSynonym(TermRelation synonym);

        /** Legt das Synonym global an oder bestätigt ein vorhandenes gleiches. */
        void addSynonym(GlossaryEntry term, String synonym);

        /** Meldet Änderungen an Glossar und Beziehungen; die Rückgabe meldet ab. */
        Runnable observe(Runnable onChange);
    }

    static Access of(Workspace workspace) {
        return new Access() {
            @Override
            public TechnicalTerms.Page terms(String q, TechnicalTerms.Scope scope, int limit, String cursor) {
                return workspace.technicalTerms(q, scope, limit, cursor);
            }

            @Override
            public List<TermRelation> synonyms(GlossaryEntry term) {
                return workspace.technicalSynonyms(term);
            }

            @Override
            public java.util.Optional<ReviewStatus> status(GlossaryEntry term) {
                return workspace.glossaryStatus(term.id());
            }

            @Override
            public void confirmTerm(GlossaryEntry term) {
                glossary().confirm(term.id(), term.revision());
            }

            @Override
            public void rejectTerm(GlossaryEntry term) {
                glossary().reject(term.id(), term.revision());
            }

            @Override
            public void confirmSynonym(TermRelation synonym) {
                workspace.confirmRelation(synonym.id(), synonym.revision());
            }

            @Override
            public void rejectSynonym(TermRelation synonym) {
                workspace.rejectRelation(synonym.id(), synonym.revision());
            }

            @Override
            public void addSynonym(GlossaryEntry term, String synonym) {
                workspace.addTechnicalSynonym(term, synonym);
            }

            @Override
            public Runnable observe(Runnable onChange) {
                Runnable glossary = workspace.glossary().map(g -> g.observe(onChange)).orElse(() -> { });
                Runnable relations = workspace.subscribeTermRelations(onChange);
                return () -> {
                    glossary.run();
                    relations.run();
                };
            }

            private GlossaryAccess glossary() {
                return workspace.glossary().orElseThrow();
            }
        };
    }

    private final Consumer<Throwable> onError;
    private Access access;
    private Runnable unobserve = () -> { };
    private String query;
    private TechnicalTerms.Scope scope = TechnicalTerms.Scope.ALL;

    private final RowList termRows = new RowList();
    private final RowList synonymRows = new RowList();
    private final JScrollPane termScroll = Comic.scroll(termRows);
    private final JScrollPane synonymScroll = Comic.scroll(synonymRows);
    final BusyOverlay termsBusy = new BusyOverlay(termScroll);
    final BusyOverlay synonymsBusy = new BusyOverlay(synonymScroll);
    final CountChip termCount = display(TERMS_WORD);
    final CountChip synonymCount = display(SYNONYMS_WORD);
    final JLabel synonymTitle = title(" ");
    final PlaceholderField synonymField = new PlaceholderField(16, ADD_PLACEHOLDER);
    final IconButton addButton = new IconButton(Glyph.PLUS, ADD).compact();
    /** Hinweis im Eingabefeld, warum ein Synonym nicht angelegt wird (gleich dem Begriff, schon vorhanden). */
    final JLabel addHint = new JLabel();
    final JSplitPane split;

    /** Gezeigte Treffer, ihr Cursor und welcher gewählt ist. */
    private final List<TechnicalTerms.Hit> hits = new ArrayList<>();
    private String nextCursor;
    private int total;
    private String selectedId;
    private GlossaryEntry selected;
    private final List<TermRelation> synonyms = new ArrayList<>();
    /** Verwirft Antworten älterer Abfragen. */
    private int termRequest;
    private int synonymRequest;
    private boolean loadingMore;
    private boolean rereadQueued;

    /** @param onError Fehler eines Aufrufs, rot im Meldungsbereich der Suche */
    TechnicalTermsPanel(Consumer<Throwable> onError) {
        super(new BorderLayout());
        this.onError = Objects.requireNonNull(onError, "onError");
        setOpaque(false);
        termsBusy.setHighlight(Comic.BLUE);
        termsBusy.setVeilColor(Comic.SURFACE);
        synonymsBusy.setHighlight(Comic.BLUE);
        synonymsBusy.setVeilColor(Comic.SURFACE);
        termScroll.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (!e.getValueIsAdjusting()) {
                loadMoreAtEnd();
            }
        });

        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.setOpaque(false);
        left.add(header(title(TERMS_TITLE), termCount), BorderLayout.NORTH);
        left.add(termsBusy, BorderLayout.CENTER);

        synonymField.getAccessibleContext().setAccessibleName(ADD);
        synonymField.addActionListener(e -> addSynonym());
        addButton.addActionListener(e -> addSynonym());
        InputFrame addFrame = new InputFrame(synonymField);
        addHint.setFont(Comic.regular(11.5f));
        addHint.setForeground(Comic.ORANGE);
        addHint.setVisible(false);
        addFrame.addButton(addHint);
        addFrame.addButton(addButton);
        synonymField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                hint(null);
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                hint(null);
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                hint(null);
            }
        });
        JPanel right = new JPanel(new BorderLayout(0, 6));
        right.setOpaque(false);
        right.add(header(synonymTitle, synonymCount), BorderLayout.NORTH);
        right.add(synonymsBusy, BorderLayout.CENTER);
        right.add(addFrame, BorderLayout.SOUTH);

        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setBackground(Comic.SURFACE);
        // Derselbe Trenner wie neben der Wissensquellen-Liste: dünne Linie mit gelbem Griff.
        ComicDivider.install(split, Comic.PALETTE);
        split.setResizeWeight(0.5);
        split.setContinuousLayout(true);
        add(split, BorderLayout.CENTER);
        showSynonymSide();
    }

    // ------------------------------------------------------------------ öffentlich für SearchView

    /** Bindet die Ansicht an eine Laufzeit; {@code null} löst sie. Meldet sich bei Glossar und Beziehungen an. */
    void access(Access access) {
        unobserve.run();
        unobserve = () -> { };
        this.access = access;
        if (access != null) {
            unobserve = access.observe(() -> SwingUtilities.invokeLater(this::changed));
        }
    }

    /** Sucht neu: Suchtext (leer heißt alle) im Bereich; die Liste beginnt oben. */
    void search(String q, TechnicalTerms.Scope within) {
        query = q == null || q.isBlank() ? null : q.strip();
        scope = within == null ? TechnicalTerms.Scope.ALL : within;
        clear();
        reread(PAGE, false);
    }

    /** Leert beide Seiten sofort; laufende Antworten älterer Abfragen verfallen. */
    void clear() {
        termRequest++;
        termsBusy.setBusy(false);
        selectedId = null;
        selected = null;
        hits.clear();
        nextCursor = null;
        total = 0;
        synonyms.clear();
        synonymRequest++;
        // Alte Zeilen sofort weg: ihre Knöpfe zeigten sonst auf Einträge einer anderen Suche.
        showTerms();
        termScroll.getVerticalScrollBar().setValue(0);
        showSynonymSide();
    }

    /** Meldet ab, etwa wenn die Suche die Methode verlässt. */
    void close() {
        access(null);
    }

    // ------------------------------------------------------------------ Laden

    /** Eine Meldung aus Glossar oder Beziehungen: beide Seiten neu, ohne zu springen; mehrere fallen zusammen. */
    private void changed() {
        if (access == null || rereadQueued) {
            return;
        }
        rereadQueued = true;
        SwingUtilities.invokeLater(() -> {
            rereadQueued = false;
            reread(Math.max(PAGE, hits.size()), true);
            if (selected != null) {
                loadSynonyms();
            }
        });
    }

    /**
     * Liest die ersten {@code limit} Treffer neu; mit {@code keep} bleiben Rollstand und Auswahl (nach Ablehnen der
     * nächste an derselben Stelle).
     */
    private void reread(int limit, boolean keep) {
        if (access == null) {
            return;
        }
        int request = ++termRequest;
        Access current = access;
        String q = query;
        TechnicalTerms.Scope within = scope;
        int count = Math.max(limit, 1);
        int selectedIndex = indexOf(selectedId);
        GlossaryEntry chosen = selected;
        termsBusy.setBusy(true);
        Background.run(() -> {
            // Alle schon geladenen Zeilen neu, über den Cursor in Stapeln: nichts jenseits der Grenze eines Abrufs
            // gilt fälschlich als abgelehnt.
            List<TechnicalTerms.Hit> loaded = new ArrayList<>();
            TechnicalTerms.Page page = null;
            String cursor = null;
            do {
                page = current.terms(q, within, Math.min(MAX, count - loaded.size()), cursor);
                loaded.addAll(page.hits());
                cursor = page.nextCursor();
            } while (cursor != null && loaded.size() < count);
            // „Abgelehnt“ nur, wenn der Core es sagt; sonst bleibt der gewählte gewählt.
            boolean present = chosen == null
                    || loaded.stream().anyMatch(hit -> hit.entry().id().equals(chosen.id()));
            boolean gone = !present && current.status(chosen).map(status -> status == ReviewStatus.REJECTED)
                    .orElse(true);
            return new Reread(loaded, cursor, page.total(), gone);
        }, result -> {
            if (request != termRequest) {
                return;
            }
            termsBusy.setBusy(false);
            Point view = termScroll.getViewport().getViewPosition();
            hits.clear();
            hits.addAll(result.hits());
            nextCursor = result.nextCursor();
            total = result.total();
            int index = indexOf(selectedId);
            if (selectedId != null && index >= 0) {
                selected = hits.get(index).entry();
            } else if (keep && selectedId != null && result.gone()) {
                // Der gewählte ist abgelehnt oder weg: der nächste an derselben Stelle.
                selectedId = hits.isEmpty() ? null
                        : hits.get(Math.min(Math.max(selectedIndex, 0), hits.size() - 1)).entry().id();
                selectedChanged();
            } else if (!keep) {
                selectedId = null;
                selected = null;
            }
            showTerms();
            if (keep) {
                termScroll.getViewport().setViewPosition(view);
            }
        }, failure -> {
            if (request == termRequest) {
                termsBusy.setBusy(false);
                showTerms();
                onError.accept(failure);
            }
        });
    }

    /** Ergebnis eines Neuladens. */
    private record Reread(List<TechnicalTerms.Hit> hits, String nextCursor, int total, boolean gone) {
    }

    /** Am Ende der Liste der nächste Stapel. */
    private void loadMoreAtEnd() {
        if (access == null || nextCursor == null || loadingMore) {
            return;
        }
        javax.swing.JScrollBar bar = termScroll.getVerticalScrollBar();
        if (bar.getValue() + bar.getVisibleAmount() < bar.getMaximum() - 4) {
            return;
        }
        loadMore();
    }

    /** Der nächste Stapel hinter den gezeigten. */
    void loadMore() {
        if (access == null || nextCursor == null || loadingMore) {
            return;
        }
        loadingMore = true;
        int request = termRequest;
        Access current = access;
        String q = query;
        TechnicalTerms.Scope within = scope;
        String cursor = nextCursor;
        Background.run(() -> current.terms(q, within, PAGE, cursor), page -> {
            loadingMore = false;
            if (request != termRequest) {
                return;
            }
            hits.addAll(page.hits());
            nextCursor = page.nextCursor();
            total = page.total();
            Point view = termScroll.getViewport().getViewPosition();
            showTerms();
            termScroll.getViewport().setViewPosition(view);
        }, failure -> {
            loadingMore = false;
            onError.accept(failure);
        });
    }

    private void loadSynonyms() {
        GlossaryEntry term = selected;
        if (access == null || term == null) {
            return;
        }
        int request = ++synonymRequest;
        Access current = access;
        synonymsBusy.setBusy(true);
        Background.run(() -> current.synonyms(term), found -> {
            if (request != synonymRequest) {
                return;
            }
            synonymsBusy.setBusy(false);
            Point view = synonymScroll.getViewport().getViewPosition();
            synonyms.clear();
            synonyms.addAll(found);
            showSynonyms();
            synonymScroll.getViewport().setViewPosition(view);
        }, failure -> {
            if (request == synonymRequest) {
                synonymsBusy.setBusy(false);
                onError.accept(failure);
            }
        });
    }

    // ------------------------------------------------------------------ Handlungen

    private void select(String id) {
        if (Objects.equals(id, selectedId)) {
            return;
        }
        selectedId = id;
        selectedChanged();
        showTerms();
    }

    private void selectedChanged() {
        int index = indexOf(selectedId);
        selected = index < 0 ? null : hits.get(index).entry();
        synonyms.clear();
        synonymRequest++;
        synonymsBusy.setBusy(false);
        showSynonymSide();
        loadSynonyms();
    }

    /** Bestätigen oder Ablehnen eines Fachbegriffs; danach liest die Liste neu, ohne zu springen. */
    void review(GlossaryEntry term, boolean confirm) {
        Access current = access;
        if (current == null) {
            return;
        }
        Background.run(() -> {
            if (confirm) {
                current.confirmTerm(term);
            } else {
                current.rejectTerm(term);
            }
            return term;
        }, done -> reread(Math.max(PAGE, hits.size()), true), failure -> {
            onError.accept(failure);
            reread(Math.max(PAGE, hits.size()), true);
        });
    }

    /** Bestätigen oder Ablehnen eines Synonyms. */
    void review(TermRelation synonym, boolean confirm) {
        Access current = access;
        if (current == null) {
            return;
        }
        Background.run(() -> {
            if (confirm) {
                current.confirmSynonym(synonym);
            } else {
                current.rejectSynonym(synonym);
            }
            return synonym;
        }, done -> {
            loadSynonyms();
            reread(Math.max(PAGE, hits.size()), true);
        }, failure -> {
            onError.accept(failure);
            loadSynonyms();
        });
    }

    private void addSynonym() {
        GlossaryEntry term = selected;
        String text = synonymField.getText().strip();
        Access current = access;
        if (term == null || text.isEmpty() || current == null) {
            return;
        }
        // Vorher abfangen, mit Hinweis im Feld statt roter Meldung.
        String normalized = TermText.normalize(text);
        if (normalized.equals(term.normalized())) {
            hint(SAME_AS_TERM);
            return;
        }
        if (synonyms.stream().anyMatch(relation -> TermText.normalize(relation.other(term.normalized()))
                .equals(normalized))) {
            hint(ALREADY_THERE);
            return;
        }
        Background.run(() -> {
            current.addSynonym(term, text);
            return text;
        }, done -> {
            synonymField.setText("");
            synonymField.requestFocusInWindow();
            loadSynonyms();
        }, onError::accept);
    }

    /** Der Hinweis im Eingabefeld; {@code null} blendet ihn aus. */
    private void hint(String text) {
        addHint.setText(text == null ? "" : text);
        addHint.setVisible(text != null);
        synonymField.revalidate();
    }

    // ------------------------------------------------------------------ Darstellung

    private void showTerms() {
        termRows.removeAll();
        for (TechnicalTerms.Hit hit : hits) {
            GlossaryEntry entry = hit.entry();
            String note = hit.viaSynonym() == null ? null : "über „" + hit.viaSynonym() + "“";
            Row[] row = new Row[1];
            // Bis zum Neuladen gesperrt: ein zweiter Klick träfe sonst den schon abgelehnten Eintrag.
            row[0] = new Row(entry.term(), note, entry.status() == ReviewStatus.CONFIRMED,
                    entry.id().equals(selectedId), () -> select(entry.id()),
                    () -> {
                        row[0].lock();
                        review(entry, true);
                    }, () -> {
                        row[0].lock();
                        review(entry, false);
                    });
            termRows.add(row[0]);
        }
        termCount.count(total);
        termRows.revalidate();
        termRows.repaint();
    }

    private void showSynonymSide() {
        boolean chosen = selected != null;
        synonymTitle.setText(chosen ? selected.term() : " ");
        synonymCount.setVisible(chosen);
        synonymField.setEnabled(chosen);
        addButton.setEnabled(chosen);
        synonymField.setToolTipText(chosen ? "Neues Synonym zu „" + selected.term() + "“; Enter fügt hinzu"
                : ADD_DISABLED);
        addButton.setToolTipText(chosen ? ADD : ADD_DISABLED);
        showSynonyms();
    }

    private void showSynonyms() {
        synonymRows.removeAll();
        if (selected != null) {
            String self = TermText.normalize(selected.term());
            for (TermRelation relation : synonyms) {
                String other = relation.other(self);
                Row[] row = new Row[1];
                row[0] = new Row(other, null, relation.status() == ReviewStatus.CONFIRMED, false, () -> { },
                        () -> {
                            row[0].lock();
                            review(relation, true);
                        }, () -> {
                            row[0].lock();
                            review(relation, false);
                        });
                synonymRows.add(row[0]);
            }
        }
        synonymCount.count(synonyms.size());
        synonymRows.revalidate();
        synonymRows.repaint();
    }

    private int indexOf(String id) {
        if (id == null) {
            return -1;
        }
        for (int i = 0; i < hits.size(); i++) {
            if (hits.get(i).entry().id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static JLabel title(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Comic.semiBold(12.5f));
        label.setForeground(Comic.INK);
        return label;
    }

    /** Ein Zählchip nur zur Anzeige, kein Filter. */
    private static CountChip display(String word) {
        CountChip chip = new CountChip(word, Comic.MUTED);
        chip.setFocusable(false);
        chip.tips(word, word);
        chip.addActionListener(e -> chip.setSelected(false));
        return chip;
    }

    private static JPanel header(JComponent title, CountChip count) {
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        header.add(title, BorderLayout.CENTER);
        header.add(count, BorderLayout.EAST);
        header.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        return header;
    }

    // ------------------------------------------------------------------ für Tests

    List<String> termTexts() {
        return hits.stream().map(hit -> hit.entry().term()).toList();
    }

    List<String> synonymTexts() {
        String self = selected == null ? "" : TermText.normalize(selected.term());
        return synonyms.stream().map(relation -> relation.other(self)).toList();
    }

    String selectedTerm() {
        return selected == null ? null : selected.term();
    }

    void selectTerm(String term) {
        hits.stream().filter(hit -> hit.entry().term().equals(term)).findFirst()
                .ifPresent(hit -> select(hit.entry().id()));
    }

    Row termRow(String term) {
        return rowOf(termRows, term);
    }

    Row synonymRow(String term) {
        return rowOf(synonymRows, term);
    }

    private static Row rowOf(RowList rows, String term) {
        for (java.awt.Component component : rows.getComponents()) {
            if (component instanceof Row row && row.text.getText().equals(term)) {
                return row;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ Bausteine

    /** Senkrechte Liste von Zeilen, so breit wie der sichtbare Bereich: kein waagerechter Rollbalken. */
    private static final class RowList extends JPanel implements Scrollable {

        RowList() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBackground(AppColors.of("comic.fieldBg"));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return Row.HEIGHT;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(Row.HEIGHT, visible.height - Row.HEIGHT);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /** Eine Zeile: Zustandspunkt, Begriff (gedämpft dahinter, über welches Synonym), Bestätigen, Ablehnen. */
    static final class Row extends JPanel {

        static final int HEIGHT = 30;
        final JLabel text;
        final IconButton confirm;
        final IconButton reject;
        final boolean confirmed;

        Row(String term, String note, boolean confirmed, boolean selected, Runnable onSelect, Runnable onConfirm,
            Runnable onReject) {
            super(new BorderLayout(6, 0));
            this.confirmed = confirmed;
            setOpaque(true);
            setBackground(selected ? Comic.CHANGE_CHANGED : AppColors.of("comic.fieldBg"));
            setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 4));
            setAlignmentX(LEFT_ALIGNMENT);
            Dot dot = new Dot(confirmed);
            dot.setToolTipText(confirmed ? CONFIRMED_TIP : PROPOSED_TIP);
            text = new JLabel(term);
            text.setFont(Comic.regular(13f));
            text.setForeground(Comic.INK);
            JPanel middle = new JPanel(new BorderLayout(6, 0));
            middle.setOpaque(false);
            middle.add(text, BorderLayout.WEST);
            if (note != null) {
                JLabel via = new JLabel(note);
                via.setFont(Comic.regular(12f));
                via.setForeground(Comic.MUTED);
                via.setHorizontalAlignment(SwingConstants.LEFT);
                middle.add(via, BorderLayout.CENTER);
            }
            confirm = new IconButton(Glyph.ACCEPT, GlossaryTab.CONFIRM).compact();
            reject = new IconButton(Glyph.CANCEL, GlossaryTab.REJECT).compact();
            confirm.setEnabled(!confirmed);
            if (confirmed) {
                confirm.setToolTipText(GlossaryTab.ALREADY_CONFIRMED);
            }
            confirm.addActionListener(e -> onConfirm.run());
            reject.addActionListener(e -> onReject.run());
            JPanel buttons = new JPanel();
            buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
            buttons.setOpaque(false);
            buttons.add(confirm);
            buttons.add(Box.createHorizontalStrut(2));
            buttons.add(reject);
            add(dot, BorderLayout.WEST);
            add(middle, BorderLayout.CENTER);
            add(buttons, BorderLayout.EAST);
            MouseAdapter click = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    onSelect.run();
                }
            };
            addMouseListener(click);
            middle.addMouseListener(click);
            text.addMouseListener(click);
            getAccessibleContext().setAccessibleName(term + (confirmed ? ", bestätigt" : ", Vorschlag"));
        }

        /** Sperrt beide Knöpfe bis zum Neuladen der Liste. */
        void lock() {
            confirm.setEnabled(false);
            reject.setEnabled(false);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(super.getPreferredSize().width, HEIGHT);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, HEIGHT);
        }
    }

    /** Grüner Punkt für bestätigt, für einen Vorschlag nur der freie Platz. */
    private static final class Dot extends JComponent {

        private final boolean filled;

        Dot(boolean filled) {
            this.filled = filled;
            setPreferredSize(new Dimension(10, 10));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            if (!filled) {
                return;
            }
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Comic.STANDING_CURRENT);
            int d = 8;
            g.fillOval((getWidth() - d) / 2, (getHeight() - d) / 2, d, d);
            g.dispose();
        }
    }
}
