package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.application.passages.PagePassages;
import com.aresstack.spaceharvester.application.passages.PassagePayload;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Der Reiter „Absätze“ der Leseansicht (#354): die semantischen Absätze der angezeigten Seite, wie die
 * Passagensegmentierung des Core (#181) sie an Themenwechseln bildet, nummeriert mit Themenzeile und kurzem Anriss.
 * Ein Klick markiert den Absatz im Lesetext ({@code onJump}). Die Daten stammen aus {@link PagePassages}; die App
 * segmentiert nichts selbst. Nur auf dem EDT.
 */
final class PassagesTab extends JPanel {

    /** Ein Absatz, wie ihn der Core liefert: Themenzeile, Anriss und Lage im projizierten Text. */
    record Passage(String title, String preview, int start, int end) {

        Passage {
            Objects.requireNonNull(title, "title");
        }
    }

    /** Stand der Segmentierung gegenüber dem angezeigten Text, wie bei den Begriffen. */
    enum State { CURRENT, STALE, FAILED, MISSING }

    private final JLabel state = new JLabel(" ");
    private final JPanel list = new JPanel();
    private final JLabel hint = Comic.muted(" ");
    private final JLabel mode = Comic.muted(" ");
    private Consumer<List<TermsTab.Span>> onJump = spans -> { };
    private final List<String> titles = new ArrayList<>();

    PassagesTab() {
        super(new BorderLayout(0, 10));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(6, 2, 2, 2));
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        JLabel title = Comic.text("Semantische Absätze");
        title.setFont(Comic.semiBold(15f));
        head.add(title, BorderLayout.WEST);
        state.setFont(Comic.semiBold(12f));
        state.setOpaque(true);
        head.add(state, BorderLayout.EAST);
        head.add(mode, BorderLayout.SOUTH);
        list.setOpaque(false);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setOpaque(false);
        body.add(list, BorderLayout.NORTH);
        body.add(hint, BorderLayout.CENTER);
        add(head, BorderLayout.NORTH);
        add(Comic.scroll(body), BorderLayout.CENTER);
        show(State.MISSING, List.of(), false);
    }

    /** Länge des Anrisses unter der Themenzeile. */
    static final int PREVIEW_CHARS = 140;

    /** Zeigt das Core-Ergebnis; {@code null} heißt: keine Seite oder noch nicht geladen. */
    void show(PagePassages result) {
        if (result == null) {
            show(State.MISSING, List.of(), false);
            return;
        }
        List<Passage> passages = new ArrayList<>();
        for (PagePassages.Passage passage : result.passages()) {
            passages.add(new Passage(passage.title(), preview(passage), passage.start(), passage.end()));
        }
        show(State.valueOf(result.state().name()), passages, result.mode() == PassagePayload.Mode.STRUCTURE);
    }

    /** Der Wortlaut nach der Themenzeile, gekürzt; leer, wenn der Absatz nur aus ihr besteht. */
    static String preview(PagePassages.Passage passage) {
        String text = passage.text() == null ? "" : passage.text().strip();
        String title = passage.title() == null ? "" : passage.title().strip();
        String rest = text.startsWith(title) ? text.substring(title.length()).strip() : text;
        rest = rest.replaceAll("\\s+", " ");
        return rest.length() <= PREVIEW_CHARS ? rest : rest.substring(0, PREVIEW_CHARS).strip() + " …";
    }

    void onJump(Consumer<List<TermsTab.Span>> listener) {
        onJump = Objects.requireNonNull(listener, "listener");
    }

    static final String STRUCTURAL = "Nach Absätzen und Überschriften gebildet: ohne Embedding-Modell oder ohne "
            + "Freigabe der Quelle für den KI-Dienst erkennt der Core keine Themenwechsel";

    /**
     * Zeigt die Absätze; außer bei {@link State#CURRENT} ist {@code passages} leer.
     *
     * @param structural ob der Core auf die Struktur der Seite zurückgefallen ist (Modus {@code structure})
     */
    void show(State current, List<Passage> passages, boolean structural) {
        mode.setText(current == State.CURRENT && structural ? STRUCTURAL : " ");
        state.setText(TermsTab.stateText(switch (current) {
            case CURRENT -> com.aresstack.spaceharvester.application.enrichment.PageAcronyms.State.CURRENT;
            case STALE -> com.aresstack.spaceharvester.application.enrichment.PageAcronyms.State.STALE;
            case FAILED -> com.aresstack.spaceharvester.application.enrichment.PageAcronyms.State.FAILED;
            case MISSING -> com.aresstack.spaceharvester.application.enrichment.PageAcronyms.State.MISSING;
        }));
        Color tone = switch (current) {
            case CURRENT -> Comic.GREEN;
            case STALE -> Comic.ORANGE;
            case FAILED -> Comic.RED;
            case MISSING -> Comic.MUTED;
        };
        state.setForeground(tone);
        state.setBackground(Comic.SURFACE);
        state.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(tone, 1, true),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));
        list.removeAll();
        titles.clear();
        List<Passage> shown = current == State.CURRENT ? passages : List.of();
        for (int i = 0; i < shown.size(); i++) {
            if (i > 0) {
                list.add(Box.createVerticalStrut(6));
            }
            list.add(entry(i + 1, shown.get(i)));
            titles.add(shown.get(i).title());
        }
        hint.setText(hint(current, shown.size()));
        list.revalidate();
        list.repaint();
    }

    private JPanel entry(int number, Passage passage) {
        JLabel index = new JLabel(String.valueOf(number), SwingConstants.CENTER);
        index.setFont(Comic.semiBold(12f));
        index.setForeground(Comic.INK);
        index.setPreferredSize(new Dimension(24, 24));
        index.setBorder(BorderFactory.createLineBorder(Comic.MUTED, 1, true));
        JLabel title = Comic.text(passage.title());
        title.setFont(Comic.semiBold(14f));
        JPanel lines = new JPanel(new BorderLayout(0, 4));
        lines.setOpaque(false);
        lines.add(title, BorderLayout.NORTH);
        if (passage.preview() != null && !passage.preview().isBlank()) {
            lines.add(Comic.muted(passage.preview()), BorderLayout.CENTER);
        }
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(8, 2, 8, 2));
        row.add(index, BorderLayout.WEST);
        row.add(lines, BorderLayout.CENTER);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        row.setToolTipText("Klick markiert den Absatz im Lesetext");
        row.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                onJump.accept(List.of(new TermsTab.Span(passage.start(), passage.end())));
            }
        });
        return row;
    }

    static String hint(State state, int count) {
        return switch (state) {
            case MISSING -> "Noch keine Absätze für diese Seite. Sie entstehen mit der Analyse beim nächsten Abgleich.";
            case STALE -> "Die Absätze passen nicht mehr zum angezeigten Text; „Analyse neu berechnen“ unter Text "
                    + "bildet sie neu.";
            case FAILED -> "Die letzte Segmentierung ist fehlgeschlagen; „Analyse neu berechnen“ unter Text startet "
                    + "sie neu.";
            case CURRENT -> count == 0 ? "Auf dieser Seite wurde kein Themenwechsel erkannt." : " ";
        };
    }

    // ---- für Tests

    List<String> titles() {
        return List.copyOf(titles);
    }

    String stateLabel() {
        return state.getText();
    }

    String mode() {
        return mode.getText();
    }

    String hint() {
        return hint.getText();
    }

    /** Die Zeile des Absatzes {@code index} (ab 0). */
    java.awt.Component rowForTest(int index) {
        int seen = -1;
        for (java.awt.Component component : list.getComponents()) {
            if (component instanceof JPanel row && ++seen == index) {
                return row;
            }
        }
        return null;
    }

    /** Klickt den Absatz {@code index} (ab 0) an. */
    void click(int index) {
        int seen = -1;
        for (java.awt.Component component : list.getComponents()) {
            if (component instanceof JPanel row && ++seen == index) {
                MouseEvent click = new MouseEvent(row, MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, false);
                for (java.awt.event.MouseListener listener : row.getMouseListeners()) {
                    listener.mouseClicked(click);
                }
                return;
            }
        }
    }
}
