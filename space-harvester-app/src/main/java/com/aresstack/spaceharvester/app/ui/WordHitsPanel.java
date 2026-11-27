package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.ui.content.WordSearch;
import com.aresstack.spaceharvester.app.ui.controls.InfoPopup;

import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Die Trefferliste der Wortsuche (Angelo 27.11.2026): statt Seiten die Wörter im Bereich der Suche mit Worttyp, Zahl der
 * Seiten und Fundstellen, darunter „Weitere laden“. Ein Klick auf ein Wort zeigt seine Seiten wie die Pillen im Reiter
 * „Begriffe“; ein Klick auf eine Seite öffnet sie. Nur auf dem EDT.
 */
final class WordHitsPanel extends JPanel {

    static final String NO_WORDS = "Keine Wörter gefunden";
    static final String ROW_TIP = "Klick zeigt die Seiten mit diesem Wort";

    /** Führt Arbeit im Hintergrund aus und meldet das Ergebnis auf dem EDT; in Tests sofort. */
    interface Runner {
        <T> void run(java.util.concurrent.Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure);
    }

    final WordsModel model = new WordsModel();
    private final JTable table = Comic.table(new JTable(model));
    private final ComposerButton more = Comic.button("Weitere laden", "Die nächsten Wörter derselben Suche laden");
    private final InfoPopup popup = new InfoPopup();
    private final Consumer<WordSearch.Place> onOpen;
    private final Consumer<String> onStatus;
    private final Consumer<Throwable> onProblem;
    Runner runner = Background::run;
    private WordSearch search;
    private Pager<WordSearch.Word> pager;
    private final Set<String> seen = new HashSet<>();
    /** Zählt Suchen; Antworten einer überholten verfallen. */
    private int request;

    /**
     * @param onOpen    öffnet eine Seite
     * @param onStatus  der Stand für die Statuszeile
     * @param onProblem ein Fehler beim Lesen
     */
    WordHitsPanel(Consumer<WordSearch.Place> onOpen, Consumer<String> onStatus, Consumer<Throwable> onProblem) {
        super(new BorderLayout(0, 6));
        this.onOpen = Objects.requireNonNull(onOpen, "onOpen");
        this.onStatus = Objects.requireNonNull(onStatus, "onStatus");
        this.onProblem = Objects.requireNonNull(onProblem, "onProblem");
        setOpaque(false);
        table.setToolTipText(ROW_TIP);
        table.setAutoCreateRowSorter(false);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0 && javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    places(model.rows.get(row), e);
                }
            }
        });
        // Wort und Ausschreibung breit, die Zahlen schmal; die Breiten folgen der Schrift, nicht festen Pixeln.
        int digits = table.getFontMetrics(table.getFont()).stringWidth(WordsModel.COLUMNS.get(3)) + 24;
        for (int column : new int[] {2, 3}) {
            table.getColumnModel().getColumn(column).setPreferredWidth(digits);
            table.getColumnModel().getColumn(column).setMaxWidth(digits * 2);
        }
        table.getColumnModel().getColumn(1).setPreferredWidth(digits);
        more.addActionListener(e -> loadMore());
        more.setVisible(false);
        more.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        add(Comic.scroll(table), BorderLayout.CENTER);
        add(more, BorderLayout.SOUTH);
    }

    /** Sucht die Wörter neu; die Liste beginnt leer. */
    void search(WordSearch search, Set<WordSearch.Kind> kinds, List<String> queries) {
        this.search = Objects.requireNonNull(search, "search");
        this.pager = search.words(kinds, queries);
        seen.clear();
        model.set(List.of());
        popup.setVisible(false);
        request++;
        onStatus.accept("Wörter werden gesucht …");
        loadMore();
    }

    /** Leert die Liste, etwa ohne Bereich. */
    void clear() {
        request++;
        search = null;
        pager = null;
        seen.clear();
        model.set(List.of());
        more.setVisible(false);
        popup.setVisible(false);
    }

    private void loadMore() {
        Pager<WordSearch.Word> current = pager;
        if (current == null) {
            return;
        }
        int mine = request;
        more.setEnabled(false);
        runner.run(() -> {
            List<WordSearch.Word> next = current.next(WordSearch.STEP);
            return new Batch(next, current.hasMore());
        }, batch -> {
            if (mine != request) {
                return;
            }
            List<WordSearch.Word> rows = new ArrayList<>(model.rows);
            for (WordSearch.Word word : batch.words()) {
                if (seen.add(word.key())) {
                    rows.add(word);
                }
            }
            model.set(rows);
            more.setEnabled(true);
            more.setVisible(batch.more());
            onStatus.accept(status(rows.size(), batch.more()));
        }, failure -> {
            if (mine == request) {
                more.setEnabled(true);
                onProblem.accept(failure);
            }
        });
    }

    private record Batch(List<WordSearch.Word> words, boolean more) {
    }

    /** „12 Wörter“, „120 Wörter, weitere folgen“ bzw. „Keine Wörter gefunden“. */
    static String status(int count, boolean more) {
        if (count == 0 && !more) {
            return NO_WORDS;
        }
        return Texts.count(count, "Wort", "Wörter") + (more ? ", weitere folgen" : "");
    }

    private void places(WordSearch.Word word, MouseEvent at) {
        WordSearch current = search;
        if (current == null) {
            return;
        }
        int mine = request;
        runner.run(() -> current.places(word), places -> {
            if (mine != request) {
                return;
            }
            popup.clearLinks();
            popup.text((word.verb() != null ? "Aussagen mit „" : "Fundstellen von „") + word.text() + "“",
                    places.isEmpty() ? "Keine sichtbare Fundstelle."
                            : Texts.count(places.size(), "Seite", "Seiten") + "; ein Klick öffnet die Seite.");
            for (WordSearch.Place place : places) {
                popup.link(place.title() + " · " + Texts.count(place.count(), word.verb() != null ? "Aussage"
                        : "Fundstelle", word.verb() != null ? "Aussagen" : "Fundstellen"), () -> {
                    popup.setVisible(false);
                    onOpen.accept(place);
                });
            }
            if (table.isShowing()) {
                popup.showAt(table, at.getX(), at.getY());
            }
        }, failure -> {
            if (mine == request) {
                onProblem.accept(failure);
            }
        });
    }

    /** Die Spalten: Wort, Worttyp, Seiten, Fundstellen, Ausschreibung bzw. Schreibweisen. */
    static final class WordsModel extends AbstractTableModel {

        static final List<String> COLUMNS = List.of("Wort", "Worttyp", "Seiten", "Fundstellen",
                "Ausschreibung / Schreibweisen");
        final List<WordSearch.Word> rows = new ArrayList<>();

        void set(List<WordSearch.Word> words) {
            rows.clear();
            rows.addAll(words);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.size();
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS.get(column);
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 2 || column == 3 ? Integer.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            WordSearch.Word word = rows.get(row);
            return switch (column) {
                case 0 -> word.text();
                case 1 -> word.kind().one();
                case 2 -> word.pages();
                case 3 -> word.count();
                default -> word.detail();
            };
        }
    }
}
