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
 * Satz und darunter der Seitentitel als Link, der die Seite an der Stelle öffnet und das Overlay schließt.
 * Keine Standardknöpfe; Schließen mit dem Kreis „Esc“, der Esc-Taste oder einem Klick daneben. Nur auf dem EDT.
 */
public final class EvidencePopup extends JPopupMenu {

    /** Höchstens so breit. */
    static final int WIDTH = 460;
    /** Höchstens so hoch, die Liste scrollt darin. */
    static final int MAX_HEIGHT = 420;
    /** Satz, wenn der Lesetext nicht mehr zur Analyse passt und keine Vorstufe vorliegt. */
    static final String NO_SENTENCE = "Satz nicht mehr im Lesetext – die Seite hat sich seit der Analyse geändert.";

    /** Wohin ein Link springt: Seite und Stelle im Lesetext ({@code start} negativ: nur die Seite). */
    public record Target(String sourceId, String pageId, int start, int end) {
    }

    private final JLabel heading = new JLabel(" ");
    private final CountChip count = new CountChip("Belege", Comic.MUTED);
    private final com.aresstack.spaceharvester.app.ui.controls.ScrollColumn rows =
            new com.aresstack.spaceharvester.app.ui.controls.ScrollColumn(null);
    private final JScrollPane scroll = Comic.scroll(rows);
    private final List<JLabel> links = new ArrayList<>();
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
     * @param sentences Wortlaut je Aussage-ID ({@link SentenceText}); fehlt einer, steht das Prädikat da
     */
    EvidencePopup fill(String title, int total, List<DomainTermGraph.Evidence> evidence,
                       Map<String, SentenceText> sentences) {
        heading.setText(title);
        count.count(total);
        rows.removeAll();
        links.clear();
        for (DomainTermGraph.Evidence item : evidence) {
            rows.add(row(item, sentences.get(item.statement().id())));
            rows.add(Box.createVerticalStrut(10));
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

    private JComponent row(DomainTermGraph.Evidence item, SentenceText sentence) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setAlignmentX(LEFT_ALIGNMENT);
        WrappedText text = Comic.wrapped(sentenceText(item, sentence), Comic.INK, 13f);
        text.setAlignmentX(LEFT_ALIGNMENT);
        row.add(text);
        JLabel link = link(pageTitle(item));
        link.setAlignmentX(LEFT_ALIGNMENT);
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
        row.add(link);
        return row;
    }

    /** Der Originalsatz, sonst der vereinfachte Satz der Vorstufe, sonst ein Hinweis mit dem Prädikat. */
    static String sentenceText(DomainTermGraph.Evidence item, SentenceText sentence) {
        if (sentence != null && sentence.original() != null && !sentence.original().isBlank()) {
            return sentence.original();
        }
        if (sentence != null && sentence.simplified() != null && !sentence.simplified().isBlank()) {
            return sentence.simplified() + " (vereinfacht)";
        }
        return item.fromText() + " … " + item.statement().predicateText() + " … " + item.toText() + " – " + NO_SENTENCE;
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
