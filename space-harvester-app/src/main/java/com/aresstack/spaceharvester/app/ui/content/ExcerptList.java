package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Pager;
import com.aresstack.spaceharvester.app.ui.Problems;
import com.aresstack.spaceharvester.app.ui.Texts;
import com.aresstack.spaceharvester.app.ui.controls.ScrollColumn;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Die Textausschnitte aller Fundstellen eines Worts untereinander, in der Vorschau unter der Wortsuche
 * (Angelo 28.11.2026): je Fundstelle eine {@link ExcerptCard}, in einer Rollfläche; „Weitere laden“ holt die nächsten.
 * Gelesen wird im Hintergrund; ein neues Wort überholt das vorige. Nur auf dem EDT.
 */
public final class ExcerptList extends JPanel {

    public static final String MORE = "Weitere laden";
    static final String LOADING = "Fundstellen werden gelesen …";
    static final String NONE = "Keine sichtbare Fundstelle.";
    static final String FAILED = "Fundstellen nicht geladen: ";

    /** Führt Arbeit im Hintergrund aus und meldet das Ergebnis auf dem EDT; in Tests sofort. */
    public interface Runner {
        <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure);
    }

    private final JLabel heading = Comic.text(" ");
    private final ScrollColumn column = new ScrollColumn(null);
    private final ComposerButton more = Comic.button(MORE, "Die nächsten Fundstellen dieses Worts laden");
    private final Consumer<WordSearch.Place> onOpen;
    private final List<ExcerptCard> cards = new ArrayList<>();
    public Runner runner = Background::run;
    private Pager<WordSearch.Excerpt> pager;
    private String word = "";
    /** Zählt Anfragen; die Antwort einer überholten verfällt. */
    private int request;

    /** @param onOpen öffnet die Seite einer Fundstelle */
    public ExcerptList(Consumer<WordSearch.Place> onOpen) {
        super(new BorderLayout());
        this.onOpen = Objects.requireNonNull(onOpen, "onOpen");
        setOpaque(false);
        heading.setFont(Comic.semiBold(14f));
        heading.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        more.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        more.setVisible(false);
        more.addActionListener(e -> load());
        add(heading, BorderLayout.NORTH);
        add(Comic.scroll(column), BorderLayout.CENTER);
        add(more, BorderLayout.SOUTH);
    }

    /** Zeigt die Ausschnitte des Worts von vorn. */
    public void show(String word, Pager<WordSearch.Excerpt> excerpts) {
        this.word = Objects.requireNonNull(word, "word");
        this.pager = Objects.requireNonNull(excerpts, "excerpts");
        request++;
        empty();
        heading.setText(LOADING);
        load();
    }

    /** Leert die Liste, etwa vor einer neuen Suche. */
    public void clear() {
        request++;
        pager = null;
        word = "";
        empty();
        heading.setText(" ");
    }

    private void empty() {
        cards.clear();
        column.removeAll();
        more.setVisible(false);
        column.revalidate();
        column.repaint();
    }

    private void load() {
        Pager<WordSearch.Excerpt> current = pager;
        if (current == null) {
            return;
        }
        int mine = request;
        more.setEnabled(false);
        runner.run(() -> new Batch(current.next(WordSearch.STEP), current.hasMore()), batch -> {
            if (mine != request) {
                return;
            }
            for (WordSearch.Excerpt excerpt : batch.excerpts()) {
                ExcerptCard card = new ExcerptCard(excerpt, () -> onOpen.accept(excerpt.place()));
                cards.add(card);
                column.add(card);
            }
            more.setEnabled(true);
            more.setVisible(batch.more());
            heading.setText(cards.isEmpty() ? NONE : heading(word, cards.size(), batch.more()));
            column.revalidate();
            column.repaint();
        }, failure -> {
            if (mine == request) {
                more.setEnabled(true);
                heading.setText(FAILED + Problems.describe(failure).title());
            }
        });
    }

    private record Batch(List<WordSearch.Excerpt> excerpts, boolean more) {
    }

    /** „„Konto“: 12 Fundstellen“, mit „, weitere folgen“, solange nicht alle geladen sind. */
    static String heading(String word, int count, boolean more) {
        return "„" + word + "“: " + Texts.count(count, "Fundstelle", "Fundstellen") + (more ? ", weitere folgen" : "");
    }

    public String headingText() {
        return heading.getText();
    }

    /** Die gezeigten Ausschnitte, in Reihenfolge. */
    public List<ExcerptCard> cards() {
        return List.copyOf(cards);
    }

    public ComposerButton more() {
        return more;
    }
}
