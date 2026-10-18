package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Feedback;
import com.aresstack.spaceharvester.app.ui.PlaceholderField;
import com.aresstack.spaceharvester.app.ui.Problems;
import com.aresstack.spaceharvester.app.ui.Texts;
import com.aresstack.spaceharvester.application.query.CacheMissException;
import com.aresstack.spaceharvester.application.query.ListedPage;
import com.aresstack.spaceharvester.application.query.PageListing;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataListing;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Die Seiten eines Space als Liste, der Umschalter „Liste“ der Leseansicht neben dem Baum im Explorer: aus dem Bestand,
 * ohne Bestand live aus den Metadaten der Quelle, beim Scrollen nachgeladen, nach Titel filterbar. Eine gewählte Zeile
 * öffnet die Seite ({@code onOpen}). Jeder Wechsel des Space verwirft noch laufende Ladevorgänge. Nur auf dem EDT.
 */
final class PageList extends JPanel {

    private static final int BATCH = 100;

    private final AppContext context;
    private final PlaceholderField filter = new PlaceholderField(14, "Titel filtern …");
    private final PagesModel model = new PagesModel();
    private final JTable table = Comic.table(new JTable(model));
    private final JScrollPane scroll = Comic.scroll(table);
    private final JLabel status = Comic.muted(" ");
    private final Feedback feedback = new Feedback();
    private BiConsumer<String, String> onOpen = (sourceId, pageId) -> { };

    /** Zählt Wechsel des Space; Ergebnisse eines älteren Stands werden verworfen. */
    private int epoch;
    private Batch loading;
    private Batch last;
    private String cursor;
    private boolean complete;
    private String sourceId;
    private String spaceKey;

    PageList(AppContext context) {
        super(new BorderLayout(0, 6));
        this.context = context;
        setBackground(Comic.SURFACE);
        setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 8));
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                model.filter(filter.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                model.filter(filter.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                model.filter(filter.getText());
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && table.getSelectedRow() >= 0 && sourceId != null) {
                onOpen.accept(sourceId, model.row(table.getSelectedRow()).pageId());
            }
        });
        scroll.getVerticalScrollBar().addAdjustmentListener(e -> {
            int bottom = e.getAdjustable().getValue() + e.getAdjustable().getVisibleAmount();
            if (!e.getValueIsAdjusting() && bottom >= e.getAdjustable().getMaximum() - 40) {
                loadMore();
            }
        });
        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.setOpaque(false);
        top.add(filter, BorderLayout.NORTH);
        top.add(feedback, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        status.setText("Space im Explorer wählen");
    }

    void onOpen(BiConsumer<String, String> listener) {
        onOpen = Objects.requireNonNull(listener, "listener");
    }

    /** Zeigt die Seiten dieses Space; derselbe Space bleibt samt Auswahl stehen, {@code null} leert die Liste. */
    void show(String sourceId, String spaceKey) {
        if (Objects.equals(sourceId, this.sourceId) && Objects.equals(spaceKey, this.spaceKey)
                && (model.size() > 0 || loading != null)) {
            return;
        }
        this.sourceId = sourceId;
        this.spaceKey = spaceKey;
        epoch++;
        loading = null;
        last = null;
        cursor = null;
        complete = false;
        model.clear();
        feedback.clear();
        if (sourceId == null || spaceKey == null) {
            status.setText("Space im Explorer wählen");
            return;
        }
        load(new Batch(epoch, sourceId, spaceKey, false));
    }

    private void loadMore() {
        if (loading == null && cursor != null && !complete && last != null && last.epoch() == epoch) {
            load(last);
        }
    }

    private void load(Batch batch) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || loading != null) {
            return;
        }
        loading = batch;
        String from = cursor;
        status.setText(model.size() == 0 ? "Seiten werden geladen …"
                : model.size() + " Seiten, weitere werden geladen …");
        Background.run(() -> batch.live()
                        ? Result.of(runtime.workspace().liveMetadata(batch.sourceId(), batch.spaceKey(), from, BATCH))
                        : Result.of(runtime.workspace().cachedPages(batch.sourceId(), batch.spaceKey(), from, BATCH)),
                result -> {
                    if (loading != batch || batch.epoch() != epoch) {
                        return;
                    }
                    loading = null;
                    // Noch nichts im Bestand: bei einer neuen Quelle normal, die Seiten kommen dann live.
                    if (!batch.live() && from == null && result.rows().isEmpty()) {
                        load(batch.asLive());
                        return;
                    }
                    last = batch;
                    model.append(result.rows());
                    cursor = result.nextCursor();
                    complete = result.complete();
                    String more = complete ? "" : cursor != null ? " · weitere beim Scrollen" : " · Liste unvollständig";
                    status.setText(Texts.count(model.size(), "Seite", "Seiten") + more);
                },
                error -> {
                    if (loading != batch || batch.epoch() != epoch) {
                        return;
                    }
                    loading = null;
                    if (!batch.live() && from == null && cacheMiss(error)) {
                        load(batch.asLive());
                        return;
                    }
                    status.setText(model.size() == 0 ? "Keine Seiten geladen."
                            : model.size() + " Seiten geladen; weitere nicht.");
                    feedback.problem(error, batch.live() ? Problems.Step.PAGE_LIST : Problems.Step.STOCK,
                            "Space " + batch.spaceKey());
                });
    }

    private static boolean cacheMiss(Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause()) {
            if (e instanceof CacheMissException) {
                return true;
            }
        }
        return false;
    }

    // ---- für Tests

    JTable table() {
        return table;
    }

    String status() {
        return status.getText();
    }

    private record Batch(int epoch, String sourceId, String spaceKey, boolean live) {
        Batch asLive() {
            return new Batch(epoch, sourceId, spaceKey, true);
        }
    }

    /** Eine Zeile der Seitenliste. */
    record Row(String pageId, String title, Instant modifiedAt) {
    }

    private record Result(List<Row> rows, String nextCursor, boolean complete) {

        static Result of(PageListing listing) {
            List<Row> rows = new ArrayList<>();
            for (ListedPage page : listing.pages()) {
                PageMetadata m = page.header().metadata();
                rows.add(new Row(m.pageId(), m.title(), m.modifiedAt()));
            }
            return new Result(rows, listing.nextCursor(), listing.complete());
        }

        static Result of(PageMetadataListing listing) {
            List<Row> rows = new ArrayList<>();
            for (PageMetadataListing.Entry entry : listing.entries()) {
                PageMetadata m = entry.page();
                rows.add(new Row(m.pageId(), m.title(), m.modifiedAt()));
            }
            return new Result(rows, listing.nextCursor(), listing.nextCursor() == null);
        }
    }

    private static final class PagesModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Titel", "Geändert"};
        private final List<Row> all = new ArrayList<>();
        private List<Row> visible = new ArrayList<>();
        private String query = "";

        void clear() {
            all.clear();
            visible = new ArrayList<>();
            fireTableDataChanged();
        }

        void append(List<Row> rows) {
            all.addAll(rows);
            filter(query);
        }

        void filter(String text) {
            query = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
            visible = query.isEmpty() ? new ArrayList<>(all) : all.stream()
                    .filter(row -> Objects.toString(row.title(), "").toLowerCase(Locale.ROOT).contains(query))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            fireTableDataChanged();
        }

        int size() {
            return all.size();
        }

        Row row(int index) {
            return visible.get(index);
        }

        @Override
        public int getRowCount() {
            return visible.size();
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
        public Object getValueAt(int index, int column) {
            Row row = visible.get(index);
            return column == 0 ? row.title() : Texts.time(row.modifiedAt());
        }
    }
}
