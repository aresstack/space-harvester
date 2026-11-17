package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.controls.PlaceholderList;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Texts;
import com.aresstack.spaceharvester.app.ui.controls.BusyOverlay;
import com.aresstack.spaceharvester.app.ui.controls.InfoPopup;
import com.aresstack.spaceharvester.app.ui.controls.InputFrame;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.standing.PageStanding;
import com.aresstack.spaceharvester.application.standing.ReadingList;
import com.aresstack.spaceharvester.application.standing.Standing;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Unterreiter „Zuerst lesen“ in „Stand“ (ADR 0032, FL6, S3): die Seiten der Auswahl links in der Reihenfolge, in der
 * sie sich zum Lesen lohnen ({@code ReadingAdvice}). Oben rechts das Themenfeld mit ✕ und den häufigsten Begriffen
 * als Vorschlag im Feld; darunter je Seite Titel, kurze Gründe, Stand der Seite und die Summe. Ein Klick auf die
 * Summe zeigt alle Teilzahlen als Overlay, ein Klick auf den Titel öffnet die Seite. Keine verdeckte Formel: Jede
 * Zahl der Summe steht mit Grund, Anzahl und Punkten im Overlay.
 *
 * <p>Gerechnet wird nie auf dem EDT und erst, wenn der Unterreiter sichtbar ist. Nach neuem Lesen bleiben gewählte
 * Seite und Rollposition stehen.
 */
final class ReadingFirstView extends JPanel {

    static final String BUSY = "Leseempfehlung wird berechnet …";
    static final String TOPIC_TIP = "Thema: nur Seiten zeigen, die diesen Begriff erwähnen (Enter übernimmt)";
    static final String SUGGEST_TIP = "Häufigste Begriffe der Auswahl als Thema wählen";
    static final String OPEN_PAGE = "Seite öffnen";
    static final String SUM_LABEL = "Summe";
    private static final int ROW_HEIGHT = 46;
    /** Platz rechts für Stand und Summe; der Klick auf die Summe öffnet das Overlay. */
    private static final int SUM_WIDTH = 56;
    private static final int LEFT = 8;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY)
            .withZone(ZoneId.systemDefault());

    /** Rechnet die Leseempfehlung der aktuellen Auswahl; nie auf dem EDT. {@code topic} ist {@code null} ohne Thema. */
    interface Loader {
        ReadingList load(String topic, BooleanSupplier cancelled);
    }

    private final StandingTab.Runner runner;
    private final JTextField topic = new JTextField(9);
    private final InputFrame topicField;
    private final JButton suggest;
    private final JPopupMenu suggestions = new JPopupMenu();
    private final DefaultListModel<ReadingList.Recommendation> model = new DefaultListModel<>();
    private final PlaceholderList<ReadingList.Recommendation> list = new PlaceholderList<>(model);
    private final JScrollPane scroll;
    private final BusyOverlay busy;
    private final InfoPopup popup;
    private java.util.function.Function<PageStanding, JComponent> pageControls = standing -> null;
    private Consumer<String> onStatus = text -> { };
    private Consumer<KnowledgeGraph.PageRef> onOpen = page -> { };
    private Loader loader;
    private ReadingList reading;
    /** Das Thema, mit dem zuletzt gerechnet wurde; leer ohne Thema. */
    private String applied = "";
    private boolean shown;
    private boolean dirty;
    private int generation;
    private AtomicBoolean cancelled = new AtomicBoolean();

    /** @param popup das Overlay von „Stand“, eines für alle Unterreiter */
    ReadingFirstView(StandingTab.Runner runner, InfoPopup popup) {
        super(new BorderLayout());
        this.runner = Objects.requireNonNull(runner, "runner");
        this.popup = Objects.requireNonNull(popup, "popup");
        setOpaque(false);

        topic.setToolTipText(TOPIC_TIP);
        topic.getAccessibleContext().setAccessibleName("Thema");
        topic.addActionListener(e -> apply(topic.getText()));
        // ✕ leert das Feld: dann gilt wieder die ganze Auswahl, ohne Enter.
        topic.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                if (topic.getText().isBlank() && !applied.isEmpty()) {
                    apply("");
                }
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });
        topicField = InputFrame.search(topic);
        suggest = topicField.addButton(InputFrame.flatButton("▾", SUGGEST_TIP));
        suggest.setFocusable(false);
        suggest.setEnabled(false);
        suggest.addActionListener(e -> {
            if (suggestions.getComponentCount() > 0 && topicField.isShowing()) {
                suggestions.show(topicField, 0, topicField.getHeight());
            }
        });

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(ROW_HEIGHT);
        list.setCellRenderer(new Renderer());
        list.setOpaque(false);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int index = rowAt(e.getPoint());
                if (index < 0) {
                    return;
                }
                list.setSelectedIndex(index);
                click(index, e.getPoint());
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                int index = rowAt(e.getPoint());
                boolean hot = index >= 0 && spot(index, e.getPoint()) != Spot.NONE;
                list.setCursor(Cursor.getPredefinedCursor(hot ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }
        };
        list.addMouseListener(mouse);
        list.addMouseMotionListener(mouse);
        scroll = Comic.scroll(list);
        busy = new BusyOverlay(scroll);
        busy.setText(BUSY);
        busy.onCancel(() -> cancelled.set(true));
        add(busy, BorderLayout.CENTER);
    }

    /** Das Themenfeld für die Tab-Zeile oben rechts. */
    JComponent topicField() {
        return topicField;
    }

    void onStatus(Consumer<String> listener) {
        this.onStatus = Objects.requireNonNull(listener, "listener");
    }

    /** Öffnet eine Seite (Klick auf den Titel oder „Seite öffnen“ im Overlay). */
    void onOpen(Consumer<KnowledgeGraph.PageRef> listener) {
        this.onOpen = Objects.requireNonNull(listener, "listener");
    }

    /** Die Zeile „Seite von Hand:“ im Overlay (Pillen, Löschen, Protokoll); {@code null} ohne. */
    void pageControls(java.util.function.Function<PageStanding, JComponent> controls) {
        this.pageControls = Objects.requireNonNull(controls, "controls");
    }

    /** Neue Auswahl links; {@code null} ohne Auswahl. Das Thema bleibt stehen. */
    void source(Loader loader) {
        this.loader = loader;
        cancelled.set(true);
        generation++;
        busy.setBusy(false);
        popup.setVisible(false);
        if (loader == null) {
            reading = null;
            model.clear();
            suggestions(List.of());
            dirty = false;
            return;
        }
        dirty = true;
        loadIfShown();
    }

    /** Der Stand der Auswahl hat sich geändert (Entscheidung, Ende einer Aussagenanalyse). */
    void changed() {
        if (loader != null) {
            dirty = true;
            loadIfShown();
        }
    }

    /** Ob der Unterreiter zu sehen ist; erst dann wird gerechnet. */
    void shown(boolean shown) {
        this.shown = shown;
        if (!shown) {
            popup.setVisible(false);
        }
        loadIfShown();
    }

    private void loadIfShown() {
        if (shown && dirty && loader != null) {
            load();
        }
    }

    private void apply(String text) {
        String wanted = text == null ? "" : text.strip();
        if (wanted.equals(applied) && !dirty) {
            return;
        }
        applied = wanted;
        dirty = true;
        loadIfShown();
    }

    private void load() {
        Loader current = loader;
        dirty = false;
        cancelled.set(true);
        AtomicBoolean stop = new AtomicBoolean();
        cancelled = stop;
        int request = ++generation;
        String wanted = applied.isEmpty() ? null : applied;
        busy.setBusy(true);
        runner.run(() -> current.load(wanted, stop::get), loaded -> {
            if (request != generation) {
                return;
            }
            busy.setBusy(false);
            reading = loaded;
            suggestions(loaded.topTerms());
            show(loaded.pages());
            status();
        }, failure -> {
            if (request != generation) {
                return;
            }
            busy.setBusy(false);
            if (failure instanceof CancellationException || stop.get()) {
                onStatus.accept("Leseempfehlung abgebrochen");
                dirty = true;
                return;
            }
            onStatus.accept("Leseempfehlung nicht berechenbar (" + failure.getClass().getSimpleName() + ")");
        });
    }

    /** Setzt die Zeilen und behält gewählte Seite und Rollposition. */
    private void show(List<ReadingList.Recommendation> pages) {
        ReadingList.Recommendation chosen = list.getSelectedValue();
        Point position = scroll.getViewport().getViewPosition();
        model.clear();
        model.addAll(pages);
        if (chosen != null) {
            for (int i = 0; i < model.size(); i++) {
                if (model.get(i).page().key().equals(chosen.page().key())) {
                    list.setSelectedIndex(i);
                    break;
                }
            }
        }
        scroll.getViewport().setViewPosition(position);
    }

    private void suggestions(List<String> terms) {
        suggestions.removeAll();
        for (String term : terms) {
            JMenuItem item = new JMenuItem(term);
            item.addActionListener(e -> {
                topic.setText(term);
                apply(term);
            });
            suggestions.add(item);
        }
        suggest.setEnabled(!terms.isEmpty());
    }

    /** Meldet den Umfang in die Statuszeile; ruft „Stand“, sobald der Unterreiter gewählt wird. */
    void status() {
        ReadingList current = reading;
        if (current == null) {
            return;
        }
        int pages = current.pages().size();
        String text;
        if (current.topic() == null) {
            text = pages == 0 ? "Keine analysierten Seiten in der Auswahl"
                    : Texts.count(pages, "Seite", "Seiten") + " nach Lesewert";
        } else {
            text = pages == 0 ? "Keine Seite erwähnt „" + current.topic() + "“"
                    : Texts.count(pages, "Seite erwähnt", "Seiten erwähnen") + " „" + current.topic() + "“";
        }
        list.placeholder(pages == 0 ? text : null);
        onStatus.accept(text);
    }

    private int rowAt(Point point) {
        int index = list.locationToIndex(point);
        if (index < 0) {
            return -1;
        }
        Rectangle cell = list.getCellBounds(index, index);
        return cell != null && cell.contains(point) ? index : -1;
    }

    private enum Spot { TITLE, SUM, NONE }

    /** Wohin ein Klick in der Zeile trifft: auf den Titel, auf die Summe oder daneben. */
    private Spot spot(int index, Point point) {
        Rectangle cell = list.getCellBounds(index, index);
        String title = title(model.get(index).page());
        int width = list.getFontMetrics(Comic.semiBold(13f)).stringWidth(title);
        int titleEnd = Math.min(cell.x + LEFT + width, cell.x + cell.width - SUM_WIDTH - 90);
        boolean upper = point.y < cell.y + cell.height / 2 + 2;
        if (upper && point.x >= cell.x + LEFT && point.x <= Math.max(titleEnd, cell.x + LEFT + 8)) {
            return Spot.TITLE;
        }
        return point.x >= cell.x + cell.width - SUM_WIDTH - LEFT ? Spot.SUM : Spot.NONE;
    }

    private void click(int index, Point point) {
        ReadingList.Recommendation recommendation = model.get(index);
        switch (spot(index, point)) {
            case TITLE -> {
                popup.setVisible(false);
                onOpen.accept(recommendation.page());
            }
            case SUM, NONE -> parts(recommendation, point.x, point.y);
        }
    }

    /** Das Overlay mit allen Teilzahlen; nur ein Link „Seite öffnen“, schließt mit Esc. */
    private void parts(ReadingList.Recommendation recommendation, int x, int y) {
        popup.clearLinks();
        popup.controls(null);
        popup.text(title(recommendation.page()), details(recommendation,
                reading == null ? null : reading.changesSince()));
        popup.controls(pageControls.apply(recommendation.standing()));
        popup.link(OPEN_PAGE, () -> {
            popup.setVisible(false);
            onOpen.accept(recommendation.page());
        });
        if (list.isShowing()) {
            popup.showAt(list, x, y);
        }
    }

    // ---- Texte, ohne Zustand und testbar

    static String title(KnowledgeGraph.PageRef page) {
        return page.title() == null || page.title().isBlank() ? page.key().pageId() : page.title();
    }

    /** Punkte mit Vorzeichen; minus als echtes Minuszeichen. */
    static String points(int points) {
        return points > 0 ? "+" + points : points < 0 ? "−" + (-points) : "0";
    }

    /** Ein Grund in Worten, mit Anzahl und, bei mehreren, Punkten je Treffer. */
    static String reason(ReadingList.Part part, Instant since) {
        int n = part.count();
        String each = n > 1 ? " (je " + points(part.points() / n) + ")" : "";
        return switch (part.reason()) {
            case CURRENT_STATEMENTS -> Texts.count(n, "sichere Aussage, die gilt", "sichere Aussagen, die gelten")
                    + each;
            case OUTDATED_STATEMENTS -> Texts.count(n, "sichere überholte Aussage", "sichere überholte Aussagen")
                    + each;
            case TOP_TERMS -> Texts.count(n, "häufigster Begriff", "häufigste Begriffe") + " der Auswahl" + each
                    + (part.terms().isEmpty() ? "" : ": " + String.join(", ", part.terms()));
            case TOPIC_MENTIONS -> Texts.count(n, "Erwähnung des Themas", "Erwähnungen des Themas") + each;
            case RECENT_CHANGE -> since == null ? "neu oder geändert" : "neu oder geändert seit " + DAY.format(since);
            case PAGE_OUTDATED -> "Seite überholt (Label, Titel, Lage oder Entscheidung)";
            case PAGE_DRAFT -> "Seite ist Entwurf oder von Hand „geplant“/„unklar“";
            case STALE_ANALYSIS -> "Analyse der Seite veraltet";
        };
    }

    /** Die Gründe kurz für die zweite Zeile. */
    static String brief(ReadingList.Recommendation recommendation) {
        List<String> words = new ArrayList<>();
        for (ReadingList.Part part : recommendation.parts()) {
            int n = part.count();
            words.add(switch (part.reason()) {
                case CURRENT_STATEMENTS -> n + " gilt";
                case OUTDATED_STATEMENTS -> n + " überholt";
                case TOP_TERMS -> Texts.count(n, "Begriff", "Begriffe");
                case TOPIC_MENTIONS -> "Thema " + n + "×";
                case RECENT_CHANGE -> "neu/geändert";
                case PAGE_OUTDATED -> "Seite überholt";
                case PAGE_DRAFT -> "Entwurf";
                case STALE_ANALYSIS -> "Analyse veraltet";
            });
        }
        return words.isEmpty() ? "keine Gründe" : String.join(" · ", words);
    }

    /** Der Text im Overlay: Summe, je Teilzahl eine Zeile, zuletzt der Stand der Seite. */
    static String details(ReadingList.Recommendation recommendation, Instant since) {
        StringBuilder text = new StringBuilder(SUM_LABEL).append(": ").append(points(recommendation.score()));
        text.append("\n");
        for (ReadingList.Part part : recommendation.parts()) {
            text.append("\n").append(points(part.points())).append("  ").append(reason(part, since));
        }
        if (recommendation.parts().isEmpty()) {
            text.append("\nKeine Teilzahl: Die Seite hat keine sicheren Aussagen und keinen der Gründe.");
        }
        PageStanding standing = recommendation.standing();
        text.append("\n\nStand der Seite: „").append(StandingTab.word(standing.standing())).append("“");
        if (standing.decision() != null) {
            text.append(" (von Hand)");
        }
        return text.toString();
    }

    /** Zeichnet nur, was sichtbar ist; dieselben Komponenten für jede Zeile. */
    private final class Renderer implements ListCellRenderer<ReadingList.Recommendation> {
        private final JPanel line = new JPanel(new BorderLayout(8, 0));
        private final JLabel title = new JLabel();
        private final JLabel meta = new JLabel();
        private final JLabel badge = new JLabel();
        private final JLabel sum = new JLabel();
        private final JPanel text = new JPanel(new BorderLayout());
        private final JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));

        Renderer() {
            title.setFont(Comic.semiBold(13f));
            meta.setFont(Comic.regular(11.5f));
            badge.setFont(Comic.semiBold(12f));
            badge.setHorizontalAlignment(JLabel.CENTER);
            badge.setOpaque(true);
            badge.setPreferredSize(new Dimension(78, 20));
            sum.setFont(Comic.semiBold(13f));
            sum.setHorizontalAlignment(JLabel.CENTER);
            sum.setOpaque(true);
            sum.setPreferredSize(new Dimension(SUM_WIDTH - 6, 22));
            text.setOpaque(false);
            text.add(title, BorderLayout.CENTER);
            text.add(meta, BorderLayout.SOUTH);
            east.setOpaque(false);
            east.add(badge);
            east.add(sum);
            JPanel eastBox = new JPanel(new java.awt.GridBagLayout());
            eastBox.setOpaque(false);
            eastBox.add(east);
            line.add(text, BorderLayout.CENTER);
            line.add(eastBox, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends ReadingList.Recommendation> owner,
                                                      ReadingList.Recommendation row, int index, boolean selected,
                                                      boolean focused) {
            Standing standing = row.standing().standing();
            boolean faded = standing == Standing.UEBERHOLT;
            title.setText(title(row.page()));
            title.setForeground(faded ? Comic.STANDING_OUTDATED : Comic.BLUE);
            meta.setText(brief(row));
            meta.setForeground(faded ? Comic.STANDING_OUTDATED : Comic.MUTED);
            Color tone = StandingTab.tone(standing);
            badge.setText(StandingTab.word(standing));
            badge.setForeground(tone);
            badge.setBackground(Comic.SURFACE);
            badge.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(tone, 1, true),
                    BorderFactory.createEmptyBorder(1, 6, 1, 6)));
            sum.setText(points(row.score()));
            sum.setForeground(row.score() < 0 ? Comic.STANDING_OUTDATED : Comic.INK);
            sum.setBackground(Comic.SURFACE);
            sum.setBorder(BorderFactory.createLineBorder(Comic.LINE, 1, true));
            line.setOpaque(selected);
            line.setBackground(selected ? Comic.NOTICE : Comic.SURFACE);
            line.setBorder(BorderFactory.createEmptyBorder(3, LEFT, 3, 6));
            return line;
        }
    }

    // ---- für Tests

    JTextField topic() {
        return topic;
    }

    JButton suggestButton() {
        return suggest;
    }

    JPopupMenu suggestionMenu() {
        return suggestions;
    }

    List<ReadingList.Recommendation> rows() {
        List<ReadingList.Recommendation> rows = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) {
            rows.add(model.get(i));
        }
        return rows;
    }

    JList<ReadingList.Recommendation> rowList() {
        return list;
    }

    InfoPopup popup() {
        return popup;
    }

    /** Klickt wie die Maus auf Titel ({@code onTitle}) oder Summe der Zeile {@code index}. */
    void clickRow(int index, boolean onTitle) {
        Rectangle cell = list.getCellBounds(index, index);
        Point point = onTitle ? new Point(cell.x + LEFT + 2, cell.y + 6)
                : new Point(cell.x + cell.width - SUM_WIDTH / 2, cell.y + cell.height / 2);
        list.setSelectedIndex(index);
        click(index, point);
    }
}
