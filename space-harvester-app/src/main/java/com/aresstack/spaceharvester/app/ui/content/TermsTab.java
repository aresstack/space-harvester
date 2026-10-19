package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.control.ResearchPillButton;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.PlaceholderField;
import com.aresstack.spaceharvester.app.ui.Texts;
import com.aresstack.spaceharvester.application.enrichment.PageAcronyms;
import com.aresstack.spaceharvester.application.terms.PageTerms;
import com.aresstack.spaceharvester.application.terms.TermKind;
import com.aresstack.spaceharvester.application.terms.TermPayload;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Der Reiter „Begriffe“ der Leseansicht (#339): nur für die angezeigte Seite die Zahl der erkannten Akronyme,
 * Bezeichner und Beziehungen, der Stand der Analyse vom letzten Seitenabgleich und die erkannten Begriffe als Pillen,
 * erst die Akronyme, dann die Bezeichner. Ein Klick auf einen Begriff springt zu seiner nächsten Fundstelle im Lesetext
 * ({@code onJump}). Die Daten stammen aus dem Core: Akronyme aus {@link PageAcronyms}, Bezeichner (Nomen,
 * Nominalgruppen, Kennungen) aus {@link PageTerms} (#119); Beziehungen folgen mit #120. Nur auf dem EDT.
 */
final class TermsTab extends JPanel {

    static final String NOT_YET = "–";

    private final JLabel state = badge();
    private final JLabel acronymCount = number();
    private final JLabel identifierCount = number();
    private final JLabel relationCount = number();
    private final JLabel completeness = badge();
    private final JLabel analysis = Comic.text("Analyse vom letzten Seitenabgleich");
    private final PlaceholderField filter = new PlaceholderField(12, "Begriffe filtern …");
    private final JPanel chips = new JPanel(new WrapFlow());
    private final JLabel identifierTitle = Comic.text("Bezeichner");
    private final JPanel identifierChips = new JPanel(new WrapFlow());
    private final JLabel hint = Comic.muted(" ");
    private Consumer<List<Span>> onJump = spans -> { };
    private PageAcronyms shown;
    private PageTerms shownTerms;

    /** Eine Fundstelle im Lesetext (Zeichenpositionen wie im Core-Ergebnis). */
    record Span(int start, int end) {
    }

    TermsTab() {
        super(new BorderLayout(0, 10));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(6, 2, 2, 2));

        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        JLabel title = Comic.text("Begriffe dieser Seite");
        title.setFont(Comic.semiBold(15f));
        head.add(title, BorderLayout.WEST);
        head.add(state, BorderLayout.EAST);

        JPanel counts = new JPanel(new GridLayout(1, 3, 12, 0));
        counts.setOpaque(false);
        counts.add(count(acronymCount, "Akronyme"));
        counts.add(count(identifierCount, "Bezeichner"));
        counts.add(count(relationCount, "Beziehungen"));

        JPanel analysisRow = new JPanel(new BorderLayout());
        analysisRow.setOpaque(false);
        analysisRow.add(analysis, BorderLayout.WEST);
        analysisRow.add(completeness, BorderLayout.EAST);

        JPanel listHead = new JPanel(new BorderLayout(8, 0));
        listHead.setOpaque(false);
        JLabel listTitle = Comic.text("Erkannte Begriffe und ihre Fundstellen");
        listTitle.setFont(Comic.semiBold(13f));
        listHead.add(listTitle, BorderLayout.WEST);
        listHead.add(filter, BorderLayout.EAST);

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        for (JPanel row : List.of(head, counts, analysisRow, listHead)) {
            row.setAlignmentX(LEFT_ALIGNMENT);
            top.add(row);
            top.add(javax.swing.Box.createVerticalStrut(10));
        }

        chips.setOpaque(false);
        identifierChips.setOpaque(false);
        identifierTitle.setFont(Comic.semiBold(13f));
        JPanel identifiers = new JPanel(new BorderLayout(0, 4));
        identifiers.setOpaque(false);
        identifiers.add(identifierTitle, BorderLayout.NORTH);
        identifiers.add(identifierChips, BorderLayout.CENTER);
        JPanel lists = new JPanel(new BorderLayout(0, 10));
        lists.setOpaque(false);
        lists.add(chips, BorderLayout.NORTH);
        lists.add(identifiers, BorderLayout.CENTER);
        JPanel body = new JPanel(new BorderLayout(0, 6));
        body.setOpaque(false);
        body.add(lists, BorderLayout.NORTH);
        body.add(hint, BorderLayout.CENTER);

        add(top, BorderLayout.NORTH);
        add(Comic.scroll(body), BorderLayout.CENTER);

        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                fill();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                fill();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                fill();
            }
        });
        show(null);
    }

    /** {@code listener} bekommt die Fundstellen des angeklickten Begriffs in Textreihenfolge. */
    void onJump(Consumer<List<Span>> listener) {
        onJump = Objects.requireNonNull(listener, "listener");
    }

    /** Zeigt nur die Akronyme der Seite; Bezeichner bleiben offen. */
    void show(PageAcronyms acronyms) {
        show(acronyms, null);
    }

    /**
     * Zeigt die Begriffe der Seite; {@code acronyms} {@code null} heißt: keine Seite oder noch nicht geladen,
     * {@code terms} {@code null}: Bezeichner unbekannt.
     */
    void show(PageAcronyms acronyms, PageTerms terms) {
        shown = acronyms;
        shownTerms = terms;
        PageAcronyms.State current = acronyms == null ? PageAcronyms.State.MISSING : acronyms.state();
        state.setText(stateText(current));
        tone(state, stateColor(current));
        // Nur ein aktuelles Ergebnis hat Begriffe und Fundstellen; veraltet oder fehlgeschlagen zählt nichts.
        boolean any = current == PageAcronyms.State.CURRENT;
        acronymCount.setText(any ? String.valueOf(acronyms.acronyms().size()) : NOT_YET);
        boolean identifiers = terms != null && terms.state() == PageTerms.State.CURRENT;
        identifierCount.setText(identifiers ? String.valueOf(terms.identifierCount()) : NOT_YET);
        identifierCount.setToolTipText(identifiers ? "Nomen, Nominalgruppen und Kennungen"
                : "Für diesen Text liegt noch keine aktuelle Begriffsanalyse vor");
        relationCount.setText(NOT_YET);
        relationCount.setToolTipText("Beziehungen folgen mit dem Begriffsindex (#120)");
        analysis.setText(acronyms == null || acronyms.result() == null ? "Analyse vom letzten Seitenabgleich"
                : "Analyse vom letzten Seitenabgleich · " + Texts.time(acronyms.result().computedAt()));
        String complete = any ? completeness(acronyms) : null;
        completeness.setVisible(complete != null);
        completeness.setText(complete == null ? " " : complete);
        tone(completeness, acronyms != null && acronyms.warnings().isEmpty() ? Comic.GREEN : Comic.ORANGE);
        fill();
    }

    private void fill() {
        chips.removeAll();
        String query = filter.getText() == null ? "" : filter.getText().trim().toLowerCase(Locale.ROOT);
        List<PageAcronyms.Acronym> visible = new ArrayList<>();
        if (shown != null) {
            for (PageAcronyms.Acronym acronym : shown.acronyms()) {
                if (query.isEmpty() || acronym.acronym().toLowerCase(Locale.ROOT).contains(query)
                        || (acronym.expansion() != null
                        && acronym.expansion().toLowerCase(Locale.ROOT).contains(query))) {
                    visible.add(acronym);
                }
            }
        }
        for (PageAcronyms.Acronym acronym : visible) {
            chips.add(chip(label(acronym), tooltip(acronym), acronym.occurrences().stream()
                    .map(occurrence -> new Span(occurrence.start(), occurrence.end())).toList()));
        }
        identifierChips.removeAll();
        int identifiers = 0;
        if (shownTerms != null && shownTerms.state() == PageTerms.State.CURRENT) {
            for (TermPayload.Entry entry : shownTerms.terms()) {
                if (entry.kind() == TermKind.ACRONYM || !query.isEmpty()
                        && !entry.term().toLowerCase(Locale.ROOT).contains(query)) {
                    continue;
                }
                identifierChips.add(chip(entry.term(), tooltip(entry), entry.occurrences().stream()
                        .map(occurrence -> new Span(occurrence.start(), occurrence.end())).toList()));
                identifiers++;
            }
        }
        identifierTitle.setVisible(identifiers > 0);
        hint.setText(hint(shown, visible.size() + identifiers, !query.isEmpty()));
        for (JPanel list : List.of(chips, identifierChips)) {
            list.revalidate();
            list.repaint();
        }
    }

    private ResearchPillButton chip(String label, String tooltip, List<Span> spans) {
        ResearchPillButton chip = new ResearchPillButton(label, 26, 26, 10);
        chip.setFont(Comic.semiBold(12f));
        chip.setFills(Comic.SURFACE, ResearchUiPalette.SECONDARY_SURFACE, ResearchUiPalette.ACCENT_BLUE);
        chip.setForegrounds(Comic.INK, Color.WHITE, Color.WHITE);
        chip.setBorders(Comic.MUTED, Comic.INK, ResearchUiPalette.ACCENT_BLUE);
        chip.setToolTipText(tooltip);
        chip.setEnabled(!spans.isEmpty());
        chip.addActionListener(e -> onJump.accept(spans));
        return chip;
    }

    /** „Kennung · 3 Fundstellen · zuerst in „Betrieb“ · Klick …“ */
    static String tooltip(TermPayload.Entry entry) {
        String kind = entry.kind() == TermKind.IDENTIFIER ? "Kennung" : "Nomen";
        String where = entry.count() == 1 ? "1 Fundstelle" : entry.count() + " Fundstellen";
        return kind + " · " + where + (entry.section() == null ? "" : " · zuerst in „" + entry.section() + "“")
                + (entry.occurrences().isEmpty() ? "" : " · Klick springt zur nächsten Fundstelle im Lesetext");
    }

    /** „RSL · Rückmeldeschlüssel“, ohne Ausschreibung nur das Akronym. */
    static String label(PageAcronyms.Acronym acronym) {
        return acronym.expansion() == null ? acronym.acronym() : acronym.acronym() + " · " + acronym.expansion();
    }

    static String tooltip(PageAcronyms.Acronym acronym) {
        String where = acronym.count() == 1 ? "1 Fundstelle" : acronym.count() + " Fundstellen";
        return (acronym.expansion() == null ? "Ausschreibung steht nicht auf der Seite" : acronym.expansion())
                + " · " + where + (acronym.section() == null ? "" : " · zuerst in „" + acronym.section() + "“")
                + (acronym.occurrences().isEmpty() ? "" : " · Klick springt zur nächsten Fundstelle im Lesetext");
    }

    static String stateText(PageAcronyms.State state) {
        return switch (state) {
            case CURRENT -> "Aktuell";
            case STALE -> "Veraltet";
            case FAILED -> "Fehlgeschlagen";
            case MISSING -> "Nicht durchgeführt";
        };
    }

    private static Color stateColor(PageAcronyms.State state) {
        return switch (state) {
            case CURRENT -> Comic.GREEN;
            case STALE -> Comic.ORANGE;
            case FAILED -> Comic.RED;
            case MISSING -> Comic.MUTED;
        };
    }

    /** „Vollständig“ ohne Hinweise des Prozessors, sonst sein erster Hinweis. */
    static String completeness(PageAcronyms acronyms) {
        return acronyms.warnings().isEmpty() ? "Vollständig" : "Unvollständig: " + acronyms.warnings().get(0);
    }

    static String hint(PageAcronyms acronyms, int visible, boolean filtered) {
        PageAcronyms.State state = acronyms == null ? PageAcronyms.State.MISSING : acronyms.state();
        return switch (state) {
            case MISSING -> "Noch keine Analyse für diese Seite. Sie entsteht nach dem nächsten Abgleich oder über "
                    + "„Analyse neu berechnen“ unter Text.";
            case STALE -> "Die Analyse passt nicht mehr zum angezeigten Text; „Analyse neu berechnen“ unter Text "
                    + "zeigt die Begriffe wieder.";
            case FAILED -> "Die letzte Analyse ist fehlgeschlagen; „Analyse neu berechnen“ unter Text startet sie neu.";
            case CURRENT -> visible == 0 && !filtered ? "Auf dieser Seite wurden keine Begriffe erkannt."
                    : visible == 0 && filtered ? "Kein Begriff passt zum Filter." : " ";
        };
    }

    private static JPanel count(JLabel number, String caption) {
        JPanel cell = new JPanel(new BorderLayout(0, 2));
        cell.setOpaque(false);
        cell.add(number, BorderLayout.CENTER);
        JLabel label = Comic.muted(caption);
        label.setHorizontalAlignment(SwingConstants.CENTER);
        cell.add(label, BorderLayout.SOUTH);
        return cell;
    }

    private static JLabel number() {
        JLabel label = new JLabel(NOT_YET, SwingConstants.CENTER);
        label.setFont(Comic.semiBold(22f));
        label.setForeground(Comic.INK);
        return label;
    }

    private static JLabel badge() {
        JLabel label = new JLabel(" ");
        label.setFont(Comic.semiBold(12f));
        label.setOpaque(true);
        return label;
    }

    private static void tone(JLabel badge, Color color) {
        badge.setForeground(color);
        badge.setBackground(Comic.SURFACE);
        badge.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(color, 1, true),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));
    }

    // ---- für Tests

    String stateLabel() {
        return state.getText();
    }

    List<String> counts() {
        return List.of(acronymCount.getText(), identifierCount.getText(), relationCount.getText());
    }

    List<ResearchPillButton> chips() {
        List<ResearchPillButton> list = new ArrayList<>();
        for (java.awt.Component component : chips.getComponents()) {
            list.add((ResearchPillButton) component);
        }
        return list;
    }

    List<ResearchPillButton> identifierChips() {
        List<ResearchPillButton> list = new ArrayList<>();
        for (java.awt.Component component : identifierChips.getComponents()) {
            list.add((ResearchPillButton) component);
        }
        return list;
    }

    PlaceholderField filter() {
        return filter;
    }

    String hint() {
        return hint.getText();
    }

    String analysis() {
        return analysis.getText();
    }

    /** Wie {@link FlowLayout}, meldet aber die Höhe aller umbrochenen Zeilen in der Breite des Elternteils. */
    private static final class WrapFlow extends FlowLayout {

        WrapFlow() {
            super(FlowLayout.LEFT, 6, 6);
        }

        @Override
        public java.awt.Dimension preferredLayoutSize(java.awt.Container target) {
            int available = target.getParent() == null ? 0 : target.getParent().getWidth();
            if (available <= 0) {
                return super.preferredLayoutSize(target);
            }
            int x = getHgap();
            int rowHeight = 0;
            int height = getVgap();
            for (java.awt.Component part : target.getComponents()) {
                java.awt.Dimension size = part.getPreferredSize();
                if (x > getHgap() && x + size.width + getHgap() > available) {
                    height += rowHeight + getVgap();
                    x = getHgap();
                    rowHeight = 0;
                }
                x += size.width + getHgap();
                rowHeight = Math.max(rowHeight, size.height);
            }
            return new java.awt.Dimension(available, height + rowHeight + getVgap());
        }
    }
}
