package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.ui.controls.CountChip;
import com.aresstack.spaceharvester.app.ui.controls.EscCircle;
import com.aresstack.spaceharvester.app.ui.controls.WrappedText;
import com.aresstack.spaceharvester.application.knowledge.DomainTermGraph;
import com.aresstack.spaceharvester.application.standing.SentenceText;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Overlay „Belege“ einer Verb-Kante der Fachsicht (Paket W1): Kopf „Antragsteller stellt Antrag“ mit Zahl, je Beleg der
 * Satz und darunter die Stufe des Belegs als stille Pille (Paket W2) und der Seitentitel als Link, der die Seite an der
 * Stelle öffnet und das Overlay schließt.
 * Keine Standardknöpfe; Schließen mit dem Kreis „Esc“, der Esc-Taste oder einem Klick daneben. Nur auf dem EDT.
 */
public final class EvidencePopup extends JPopupMenu {

    /** Höchstens so breit. */
    static final int WIDTH = 460;
    /** Höchstens so hoch, die Liste scrollt darin. */
    static final int MAX_HEIGHT = 420;
    /** Satz, wenn der Lesetext nicht mehr zur Analyse passt und keine Vorstufe vorliegt. */
    static final String NO_SENTENCE = "Satz nicht mehr im Lesetext – die Seite hat sich seit der Analyse geändert.";
    /** Satz, wenn die Sätze nicht gelesen werden konnten. */
    static final String UNREADABLE = "Der Satz konnte nicht gelesen werden.";
    /** Satz, wenn der Aufrufer den Wortlaut der Seite nicht lesen darf. */
    static final String NO_ACCESS = "Kein Zugriff auf den Wortlaut dieser Seite.";

    /** Innenbreite des Overlays: {@link #WIDTH} ohne Rahmen (2 + 12 je Seite). */
    private static final int INNER = WIDTH - 28;

    /** Wohin ein Link springt: Seite und Stelle im Lesetext ({@code start} negativ: nur die Seite). */
    public record Target(String sourceId, String pageId, int start, int end) {
    }

    private final JLabel heading = new JLabel(" ");
    private final CountChip count = new CountChip("Belege", Comic.MUTED);
    private final com.aresstack.spaceharvester.app.ui.controls.ScrollColumn rows =
            new com.aresstack.spaceharvester.app.ui.controls.ScrollColumn(null);
    private final JScrollPane scroll = Comic.scroll(rows);
    private final List<JLabel> links = new ArrayList<>();
    private final List<CountChip> stages = new ArrayList<>();
    private final List<String> otherTexts = new ArrayList<>();
    private Consumer<Target> onOpen = target -> { };

    EvidencePopup() {
        setOpaque(true);
        setBackground(java.awt.Color.WHITE);
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Comic.INK, 2),
                BorderFactory.createEmptyBorder(10, 12, 12, 12)));
        setLayout(new BorderLayout(0, 8));
        heading.setFont(Comic.semiBold(14f));
        heading.setForeground(Comic.INK);
        count.setFocusable(false);
        count.setEnabled(true);
        JPanel head = new JPanel(new BorderLayout(8, 0));
        head.setOpaque(false);
        JPanel title = new JPanel();
        title.setOpaque(false);
        title.setLayout(new BoxLayout(title, BoxLayout.X_AXIS));
        title.add(heading);
        title.add(Box.createHorizontalStrut(8));
        title.add(count);
        head.add(title, BorderLayout.CENTER);
        head.add(new EscCircle(() -> setVisible(false), "Schließen (Esc)"), BorderLayout.EAST);
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        add(head, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        getActionMap().put("close", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                setVisible(false);
            }
        });
    }

    /** {@code listener} öffnet die Seite an der Stelle (im Reiter „Text“). */
    EvidencePopup onOpen(Consumer<Target> listener) {
        onOpen = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /**
     * Füllt das Overlay.
     *
     * @param title     Kopf, etwa „Antragsteller stellt Antrag“
     * @param total     Zahl der Belege der Kante
     * @param evidence  alle Belege; die Liste scrollt bei mehr als {@link #MAX_HEIGHT}
     * @param sentences Wortlaut je Aussage-ID ({@link SentenceText}); fehlt einer, darf der Aufrufer die Seite nicht
     *                  lesen
     * @param failed    ob die Sätze nicht gelesen werden konnten
     */
    EvidencePopup fill(String title, int total, List<DomainTermGraph.Evidence> evidence,
                       Map<String, SentenceText> sentences, boolean failed) {
        heading.setText(title);
        count.count(total);
        rows.removeAll();
        links.clear();
        stages.clear();
        otherTexts.clear();
        List<WrappedText> texts = new ArrayList<>();
        for (DomainTermGraph.Evidence item : evidence) {
            rows.add(row(item, sentences.get(item.statement().id()), failed, texts));
            rows.add(Box.createVerticalStrut(10));
        }
        // Die Sätze messen ihre Höhe an der echten Breite; sonst schätzen sie schmaler, und unter einem Satz bleibt
        // eine Leerzeile stehen. Mit Scrollbalken wird die Spalte um dessen Breite schmaler.
        width(texts, INNER);
        if (rows.getPreferredSize().height + 64 > MAX_HEIGHT) {
            width(texts, INNER - scroll.getVerticalScrollBar().getPreferredSize().width);
        }
        rows.revalidate();
        int height = Math.min(MAX_HEIGHT, rows.getPreferredSize().height + 64);
        setPreferredSize(new Dimension(WIDTH, Math.max(140, height)));
        return this;
    }

    /** Zeigt das Overlay an der Mausposition; der erste Link bekommt den Fokus. */
    void showAt(Component owner, int x, int y) {
        if (!owner.isShowing()) {
            return;
        }
        show(owner, x + 8, y + 8);
        if (!links.isEmpty()) {
            links.getFirst().requestFocusInWindow();
        }
    }

    private static void width(List<WrappedText> texts, int width) {
        for (WrappedText text : texts) {
            text.setSize(width, text.heightFor(width));
            text.invalidate();
        }
    }

    private JComponent row(DomainTermGraph.Evidence item, SentenceText sentence, boolean failed,
                           List<WrappedText> texts) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setAlignmentX(LEFT_ALIGNMENT);
        String said = sentenceText(sentence, failed);
        WrappedText text = Comic.wrapped(said, said.equals(sentenceOf(sentence)) ? Comic.INK : Comic.MUTED, 13f);
        text.setAlignmentX(LEFT_ALIGNMENT);
        texts.add(text);
        row.add(text);
        String other = otherText(item);
        if (other != null) {
            // Bei abweichenden Belegen: wie die andere Ablage den Satz liest, nur was abweicht (K2).
            WrappedText otherLine = Comic.wrapped(other, Comic.MUTED, 12f);
            otherLine.setAlignmentX(LEFT_ALIGNMENT);
            texts.add(otherLine);
            otherTexts.add(other);
            row.add(Box.createVerticalStrut(2));
            row.add(otherLine);
        }
        JLabel link = link(pageTitle(item));
        JPanel foot = new JPanel();
        foot.setOpaque(false);
        foot.setLayout(new BoxLayout(foot, BoxLayout.X_AXIS));
        foot.setAlignmentX(LEFT_ALIGNMENT);
        CountChip stage = CountChip.tag(stageWord(item), Comic.MUTED, stageTip(item));
        stages.add(stage);
        foot.add(stage);
        foot.add(Box.createHorizontalStrut(8));
        foot.add(link);
        foot.add(Box.createHorizontalGlue());
        Target target = new Target(item.page().key().sourceId(), item.page().key().pageId(),
                sentence == null || sentence.stale() ? -1 : sentence.sentenceStart(),
                sentence == null || sentence.stale() ? -1 : sentence.sentenceEnd());
        Runnable open = () -> {
            setVisible(false);
            onOpen.accept(target);
        };
        link.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                open.run();
            }
        });
        link.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_SPACE) {
                    open.run();
                }
            }
        });
        links.add(link);
        row.add(Box.createVerticalStrut(4));
        row.add(foot);
        return row;
    }

    /** Genaue Stufe eines Belegs für die Pille, im Wortlaut des Kanten-Tooltips (Paket W2). */
    static String stageWord(DomainTermGraph.Evidence item) {
        return GraphSource.stage(item, com.aresstack.spaceharvester.app.ui.content.StatementProvenance::analyzer);
    }

    private static String stageTip(DomainTermGraph.Evidence item) {
        return switch (item.trust()) {
            case GEPRUEFT -> item.statement().review()
                    == com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Review.CONFIRMED
                    ? "Ein Mensch hat diese Aussage bestätigt" : "Der Prüfschritt hat diese Aussage als stimmig bewertet";
            case EINIG -> "Parser und KI lesen diesen Satz gleich, nicht geprüft";
            case UNGEPRUEFT -> "Nur diese Lesart liegt vor, nicht geprüft";
            case ABWEICHEND -> "Parser und KI lesen diesen Satz verschieden, nicht geprüft";
        };
    }

    /**
     * Die Lesart der anderen Ablage bei einem abweichenden Beleg, nur in dem, was abweicht: „Sprachmodell (KI) liest
     * anders: Verb „senden“ · Handelnde „Kunde““; sonst {@code null}. Die Wortlaute kommen normalisiert aus dem Abgleich.
     */
    static String otherText(DomainTermGraph.Evidence item) {
        com.aresstack.spaceharvester.application.knowledge.AnalyzerComparison.Reading other = item.otherReading();
        if (other == null) {
            return null;
        }
        java.util.Set<com.aresstack.spaceharvester.application.knowledge.AnalyzerComparison.Difference> differences =
                item.comparison().differences();
        List<String> parts = new ArrayList<>();
        if (differences.contains(com.aresstack.spaceharvester.application.knowledge.AnalyzerComparison.Difference.VERB)) {
            parts.add("Verb " + quoted(other.verb() == null ? java.util.Set.of() : java.util.Set.of(other.verb())));
        }
        if (differences.contains(com.aresstack.spaceharvester.application.knowledge.AnalyzerComparison.Difference.ACTOR)) {
            parts.add("Handelnde " + quoted(other.actors()));
        }
        if (differences.contains(
                com.aresstack.spaceharvester.application.knowledge.AnalyzerComparison.Difference.AFFECTED_OBJECT)) {
            parts.add("Betroffene " + quoted(other.affected()));
        }
        String name = other.analyzer() == com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.MODEL
                ? com.aresstack.spaceharvester.app.ui.content.StatementProvenance.LANGUAGE_MODEL
                : com.aresstack.spaceharvester.app.ui.content.StatementProvenance.MALTPARSER;
        return name + " liest anders" + (parts.isEmpty() ? "" : ": " + String.join(" · ", parts));
    }

    private static String quoted(java.util.Collection<String> words) {
        return words.isEmpty() ? "keine" : words.stream().map(word -> "„" + word + "“")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * Der Originalsatz, sonst der vereinfachte Satz der Vorstufe; fehlt beides, warum: nicht lesbar, kein Zugriff oder
     * die Seite hat sich geändert. Nie ein Ersatz aus Erwähnungen und Prädikat.
     */
    static String sentenceText(SentenceText sentence, boolean failed) {
        if (failed) {
            return UNREADABLE;
        }
        if (sentence == null) {
            return NO_ACCESS;
        }
        String found = sentenceOf(sentence);
        return found == null ? NO_SENTENCE : found;
    }

    private static String sentenceOf(SentenceText sentence) {
        if (sentence != null && sentence.original() != null && !sentence.original().isBlank()) {
            return sentence.original();
        }
        if (sentence != null && sentence.simplified() != null && !sentence.simplified().isBlank()) {
            return sentence.simplified() + " (vereinfacht)";
        }
        return null;
    }

    /** Seitentitel mit Quelle und Space; veraltete Analysen gekennzeichnet. */
    static String pageTitle(DomainTermGraph.Evidence item) {
        String title = item.page().title() == null || item.page().title().isBlank() ? item.page().key().pageId()
                : item.page().title();
        String where = item.page().key().sourceId() + (item.page().spaceKey() == null ? "" : " / "
                + item.page().spaceKey());
        return title + " (" + where + ")" + (item.page().state()
                == com.aresstack.spaceharvester.application.knowledge.PageStatements.State.STALE ? " · veraltet" : "");
    }

    private static JLabel link(String text) {
        JLabel link = new JLabel(text);
        link.putClientProperty("html.disable", Boolean.TRUE);
        Font base = Comic.regular(12f);
        link.setFont(base.deriveFont(Map.of(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON)));
        link.setForeground(Comic.BLUE);
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.setFocusable(true);
        link.setToolTipText("Seite an dieser Stelle öffnen");
        link.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                link.setForeground(Comic.INK);
            }

            @Override
            public void focusLost(FocusEvent e) {
                link.setForeground(Comic.BLUE);
            }
        });
        return link;
    }

    // ---- für Tests

    String headingText() {
        return heading.getText();
    }

    int countValue() {
        return count.count();
    }

    List<String> otherTexts() {
        return List.copyOf(otherTexts);
    }

    List<String> stageTexts() {
        return stages.stream().map(CountChip::word).toList();
    }

    List<String> linkTexts() {
        return links.stream().map(JLabel::getText).toList();
    }

    void clickLink(int index) {
        MouseEvent click = new MouseEvent(links.get(index), MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                1, 1, 1, false);
        for (java.awt.event.MouseListener listener : links.get(index).getMouseListeners()) {
            listener.mouseClicked(click);
        }
    }
}
