package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.research.BookNotes;
import com.aresstack.spaceharvester.app.ui.controls.CountChip;
import com.aresstack.spaceharvester.app.ui.controls.ScrollColumn;
import com.aresstack.spaceharvester.app.ui.controls.WrapLayout;
import com.aresstack.spaceharvester.app.ui.controls.WrappedText;
import com.aresstack.writing4j.cornerstone.CornerstoneSieve;
import com.aresstack.writing4j.cornerstone.CornerstoneSurvey;
import com.aresstack.writing4j.cornerstone.FieldCards;
import com.aresstack.writing4j.cornerstone.Snippets;
import com.aresstack.writing4j.cornerstone.TermCandidates;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * „Begriffe wählen“ der Buchfunktion (Phase 4, Angelo 27.11.2026): alle Begriffe des Korbs als Schlagwörter wie in der
 * Mittelleiste von Ask AI, blass mit Umrandung, ein Klick färbt sie ({@link CountChip#choice}). Was das Sieb sicher
 * drin sieht, ist vorgewählt, die unsichere Mitte bleibt blass, was sicher draußen ist, liegt eingeklappt darunter;
 * nichts ist gesperrt, und „Buch schreiben“ ohne Änderung gilt. Die Schlagwörter stehen nach Themenfeld; ein Klick auf
 * den Namen des Feldes wählt das Feld ab (dann gehört es nicht zum Buch) oder wieder an.
 *
 * <p>Rechts die Beispiele des Begriffs, auf den die Maus zeigt: nie der nackte Satz, sondern der Satz mit seinen
 * Nachbarsätzen, darüber Quelle und Überschrift, aufklappbar zum ganzen Absatz. Steht ein Wort in mehreren Feldern,
 * stehen seine Sätze nach Feld getrennt, und es lässt sich je Feld wählen; der Klick auf das Schlagwort wählt das ganze
 * Wort. Nicht gewählt heißt nicht ausgeschlossen: ein Begriff ohne Wahl bekommt keine eigenen Fragen, seine Stellen
 * bleiben Belege.</p>
 */
final class BookTermsView {

    static final String TITLE = "Begriffe wählen";
    static final String ACTION = "Buch schreiben";
    static final String NOT_EXCLUDED = "Nicht gewählt heißt nicht ausgeschlossen: Ein Begriff ohne Farbe bekommt keine "
            + "eigenen Fragen, seine Stellen bleiben Belege. Nur ein abgewähltes Themenfeld gehört nicht zum Buch.";
    static final String OUTSIDE_FIELD = "Ohne Themenfeld";
    static final String FURTHER_SENTENCES = "Weitere Sätze";

    private final CornerstoneSurvey.Survey survey;
    /** Gewählte Begriffe: Schlüssel → Themenfelder, leer = das ganze Wort. */
    private final Map<String, Set<String>> selected = new LinkedHashMap<>();
    private final Set<String> deselectedFields = new LinkedHashSet<>();
    private final Map<String, CountChip> chips = new LinkedHashMap<>();
    private final Map<String, List<String>> termsOfField = new LinkedHashMap<>();
    private final Map<String, CountChip> fieldChips = new LinkedHashMap<>();
    private final JPanel details = new JPanel();
    private String shown;

    /** @param notes die gespeicherte Wahl des Lesers; sie schlägt die Vorwahl des Siebs */
    BookTermsView(CornerstoneSurvey.Survey survey, BookNotes notes) {
        this.survey = survey;
        boolean judged = !survey.getVotes().isEmpty();
        for (TermCandidates.Candidate candidate : survey.getCandidates().getCandidates()) {
            String key = candidate.getKey();
            if (notes != null && notes.chosen().containsKey(key)) {
                selected.put(key, new LinkedHashSet<>(notes.chosen().get(key)));
            } else if ((notes == null || !notes.unchosen().contains(key)) && judged
                    && survey.verdict(key) == CornerstoneSieve.Verdict.SICHER_DRIN) {
                selected.put(key, new LinkedHashSet<>());
            }
        }
        if (notes != null) {
            for (FieldCards.Field field : survey.getFields()) {
                if (notes.excludedFields().contains(field.getName())) {
                    deselectedFields.add(field.getName());
                }
            }
        }
    }

    /** Ob der Leser gefragt werden muss: eine unsichere Mitte, oder das Sieb hat nichts sicher drin gesehen. */
    static boolean needsReader(CornerstoneSurvey.Survey survey) {
        return !survey.uncertain().isEmpty() || survey.safeIn().isEmpty();
    }

    /** Die Zahlen des Siebs in einem Satz, für die Kopfzeile. */
    static String numbers(CornerstoneSurvey.Survey survey) {
        int all = survey.getCandidates().getCandidates().size();
        if (survey.getVotes().isEmpty()) {
            return all + " Begriffe aus dem Korb, nach Bedeutung im Korb geordnet; das Sieb hat nicht geurteilt, "
                    + "darum ist nichts vorgewählt.";
        }
        return "Das Sieb hat " + all + " Begriffe geprüft: " + survey.safeIn().size() + " sicher drin (vorgewählt), "
                + survey.uncertain().size() + " unsicher (blass), " + survey.safeOut().size()
                + " sicher draußen (eingeklappt).";
    }

    JComponent body() {
        JPanel body = new JPanel(new BorderLayout(12, 10));
        body.setOpaque(false);
        JPanel head = new JPanel();
        head.setOpaque(false);
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        head.add(left(Comic.wrapped(numbers(survey) + " Ein Klick färbt einen Begriff: Er bekommt eigene Fragen im "
                + "Buch. Zeigt die Maus auf einen Begriff, stehen rechts Sätze, in denen er vorkommt.", Comic.TEXT,
                12.5f)));
        head.add(left(Comic.hint(NOT_EXCLUDED)));
        body.add(head, BorderLayout.NORTH);
        body.add(Comic.scroll(top(tags())), BorderLayout.CENTER);
        details.setOpaque(false);
        details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
        details.add(left(Comic.hint("Zeigt die Maus auf einen Begriff, stehen hier Sätze mit dem Begriff, je mit "
                + "Quelle und Überschrift.")));
        javax.swing.JScrollPane right = Comic.scroll(top(details));
        right.setPreferredSize(new Dimension(440, 400));
        body.add(right, BorderLayout.EAST);
        return body;
    }

    private JComponent tags() {
        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        Map<String, List<TermCandidates.Candidate>> byField = new LinkedHashMap<>();
        Map<String, String> fieldOfKey = new LinkedHashMap<>();
        for (FieldCards.Field field : survey.getFields()) {
            byField.put(field.getName(), new ArrayList<>());
            for (String key : field.getKeys()) {
                fieldOfKey.putIfAbsent(key, field.getName());
            }
        }
        byField.put("", new ArrayList<>());
        for (TermCandidates.Candidate candidate : survey.getCandidates().getCandidates()) {
            byField.get(fieldOfKey.getOrDefault(candidate.getKey(), "")).add(candidate);
        }
        boolean judged = !survey.getVotes().isEmpty();
        for (Map.Entry<String, List<TermCandidates.Candidate>> field : byField.entrySet()) {
            if (field.getValue().isEmpty()) {
                continue;
            }
            List<String> keys = new ArrayList<>();
            JPanel shownTags = wrap();
            JPanel folded = wrap();
            int out = 0;
            for (TermCandidates.Candidate candidate : field.getValue()) {
                keys.add(candidate.getKey());
                boolean safeOut = judged && survey.verdict(candidate.getKey()) == CornerstoneSieve.Verdict.SICHER_DRAUSSEN;
                (safeOut ? folded : shownTags).add(chip(candidate));
                out += safeOut ? 1 : 0;
            }
            termsOfField.put(field.getKey(), keys);
            column.add(left(fieldHead(field.getKey())));
            column.add(left(shownTags));
            if (out > 0) {
                JButton more = Comic.disclosure(out + " weitere, laut Sieb draußen",
                        "Begriffe, die das Sieb sicher draußen sieht; wählbar wie alle anderen");
                folded.setVisible(false);
                more.addActionListener(e -> {
                    boolean open = !Comic.disclosed(more);
                    Comic.disclose(more, open);
                    folded.setVisible(open);
                    column.revalidate();
                });
                column.add(left(more));
                column.add(left(folded));
            }
        }
        return column;
    }

    private JComponent fieldHead(String field) {
        if (field.isEmpty()) {
            JLabel label = new JLabel(OUTSIDE_FIELD);
            label.setFont(Comic.semiBold(13f));
            label.setBorder(BorderFactory.createEmptyBorder(10, 0, 2, 0));
            return label;
        }
        CountChip chip = CountChip.choice(field, Comic.BLUE).tips("Themenfeld wieder aufnehmen",
                "Themenfeld abwählen: Es gehört dann nicht zum Buch, seine Begriffe verlieren die Farbe");
        chip.setSelected(!deselectedFields.contains(field));
        chip.addActionListener(e -> toggleField(field, chip.isSelected()));
        fieldChips.put(field, chip);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(10, 0, 2, 0));
        row.add(chip);
        return row;
    }

    private void toggleField(String field, boolean wanted) {
        boolean judged = !survey.getVotes().isEmpty();
        if (wanted) {
            deselectedFields.remove(field);
            for (String key : termsOfField.getOrDefault(field, List.of())) {
                if (!judged || survey.verdict(key) != CornerstoneSieve.Verdict.SICHER_DRAUSSEN) {
                    selected.putIfAbsent(key, new LinkedHashSet<>());
                }
            }
        } else {
            deselectedFields.add(field);
            termsOfField.getOrDefault(field, List.of()).forEach(selected::remove);
        }
        refresh();
    }

    private CountChip chip(TermCandidates.Candidate candidate) {
        String key = candidate.getKey();
        CountChip chip = CountChip.choice(candidate.getTerm(), Comic.PETROL).tips(
                "Wählen: „" + candidate.getTerm() + "“ bekommt eigene Fragen",
                "Abwählen: keine eigenen Fragen, die Stellen bleiben Belege");
        chip.setSelected(selected.containsKey(key));
        chip.addActionListener(e -> {
            if (chip.isSelected()) {
                selected.put(key, new LinkedHashSet<>());
            } else {
                selected.remove(key);
            }
            show(key);
        });
        chip.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                show(key);
            }
        });
        chip.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                show(key);
            }
        });
        chips.put(key, chip);
        return chip;
    }

    private void refresh() {
        chips.forEach((key, chip) -> chip.setSelected(selected.containsKey(key)));
        fieldChips.forEach((field, chip) -> chip.setSelected(!deselectedFields.contains(field)));
        if (shown != null) {
            String again = shown;
            shown = null;
            show(again);
        }
    }

    /** Die Beispiele eines Begriffs rechts, nach Feld getrennt, wenn er in mehreren steht. */
    void show(String key) {
        if (key.equals(shown)) {
            refreshFieldChoices(key);
            return;
        }
        shown = key;
        TermCandidates.Candidate candidate = survey.candidate(key);
        details.removeAll();
        if (candidate == null) {
            details.revalidate();
            details.repaint();
            return;
        }
        JLabel title = new JLabel(candidate.getTerm());
        title.setFont(Comic.semiBold(15f));
        details.add(left(title));
        details.add(left(Comic.hint(candidate.getPassageIds().size() + " Stellen in " + candidate.getSources()
                + (candidate.getSources() == 1 ? " Quelle" : " Quellen"))));
        Map<String, List<Snippets.Snippet>> byField = survey.snippetsByField(key);
        boolean split = byField.size() > 1;
        for (Map.Entry<String, List<Snippets.Snippet>> field : byField.entrySet()) {
            if (split) {
                String name = field.getKey().isEmpty() ? FURTHER_SENTENCES : field.getKey();
                CountChip only = CountChip.choice(name, Comic.PETROL).tips("„" + candidate.getTerm() + "“ in diesem "
                        + "Themenfeld wählen", "„" + candidate.getTerm() + "“ in diesem Themenfeld abwählen");
                only.putClientProperty(FIELD, field.getKey());
                only.setSelected(selectedIn(key, field.getKey()));
                only.addActionListener(e -> chooseIn(key, field.getKey(), only.isSelected(), byField.keySet()));
                JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
                row.setOpaque(false);
                row.setBorder(BorderFactory.createEmptyBorder(10, 0, 2, 0));
                row.add(only);
                details.add(left(row));
            }
            for (Snippets.Snippet snippet : field.getValue()) {
                details.add(left(snippet(candidate, snippet)));
            }
        }
        details.revalidate();
        details.repaint();
    }

    private static final String FIELD = "spaceharvester.book.field";

    private boolean selectedIn(String key, String field) {
        Set<String> fields = selected.get(key);
        return fields != null && (fields.isEmpty() || fields.contains(field));
    }

    /** Wählt ein Wort je Feld; alle Felder gewählt ist das ganze Wort, keins ist abgewählt. */
    private void chooseIn(String key, String field, boolean on, Set<String> all) {
        Set<String> fields = selected.containsKey(key)
                ? selected.get(key).isEmpty() ? new LinkedHashSet<>(all) : new LinkedHashSet<>(selected.get(key))
                : new LinkedHashSet<>();
        if (on) {
            fields.add(field);
        } else {
            fields.remove(field);
        }
        if (fields.isEmpty()) {
            selected.remove(key);
        } else {
            selected.put(key, fields.containsAll(all) ? new LinkedHashSet<>() : fields);
        }
        CountChip chip = chips.get(key);
        if (chip != null) {
            chip.setSelected(selected.containsKey(key));
        }
        refreshFieldChoices(key);
    }

    private void refreshFieldChoices(String key) {
        for (Component row : details.getComponents()) {
            if (row instanceof JPanel panel && panel.getComponentCount() == 1
                    && panel.getComponent(0) instanceof CountChip only && only.getClientProperty(FIELD) != null) {
                only.setSelected(selectedIn(key, (String) only.getClientProperty(FIELD)));
            }
        }
    }

    /** Ein Beispiel: Quelle und Überschrift, der Satz mit Nachbarsätzen (Begriff markiert), aufklappbar zum Absatz. */
    private JComponent snippet(TermCandidates.Candidate candidate, Snippets.Snippet snippet) {
        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        String where = snippet.getSourceTitle() == null || snippet.getSourceTitle().isBlank() ? ""
                : snippet.getSourceTitle();
        if (snippet.getHeading() != null && !snippet.getHeading().isBlank()) {
            where = where.isEmpty() ? snippet.getHeading() : where + " · " + snippet.getHeading();
        }
        if (!where.isEmpty()) {
            box.add(left(Comic.hint(where)));
        }
        WrappedText text = Comic.wrapped(snippet.toString(), Comic.INK, 12.5f);
        mark(text, candidate.getTerm());
        box.add(left(text));
        JButton whole = Comic.disclosure("Ganzer Absatz", "Den ganzen Absatz zeigen, aus dem der Satz stammt");
        TermCandidates.Text passage = survey.passage(snippet.getPassageId());
        if (passage != null && passage.getText().length() > snippet.toString().length()) {
            WrappedText all = Comic.wrapped(passage.getText().replaceAll("\\s+", " ").trim(), Comic.TEXT, 12f);
            mark(all, candidate.getTerm());
            all.setVisible(false);
            whole.addActionListener(e -> {
                boolean open = !Comic.disclosed(whole);
                Comic.disclose(whole, open);
                all.setVisible(open);
                text.setVisible(!open);
                box.revalidate();
            });
            box.add(left(whole));
            box.add(left(all));
        }
        return box;
    }

    /** Markiert das Wort gelb, auch in der Mehrzahl (gesucht ohne den letzten Buchstaben, markiert bis zum Wortende). */
    private static void mark(WrappedText text, String term) {
        String[] words = term.toLowerCase(Locale.ROOT).split(" ");
        String last = words[words.length - 1];
        String needle = last.length() > 4 ? last.substring(0, last.length() - 1) : last;
        String content = text.getText().toLowerCase(Locale.ROOT);
        DefaultHighlighter.DefaultHighlightPainter yellow = new DefaultHighlighter.DefaultHighlightPainter(Comic.YELLOW);
        for (int at = content.indexOf(needle); at >= 0 && !needle.isEmpty(); at = content.indexOf(needle, at + 1)) {
            int end = at + needle.length();
            while (end < content.length() && Character.isLetter(content.charAt(end))) {
                end++;
            }
            try {
                text.getHighlighter().addHighlight(at, end, yellow);
            } catch (BadLocationException ignored) {
                return;
            }
        }
    }

    /** Die Wahl des Lesers für Writing4j. */
    CornerstoneSurvey.Chosen chosen(List<com.aresstack.knowledge4j.basket.OrderingHint> hints) {
        return CornerstoneSurvey.selected(survey, selected, deselectedFields, null, hints);
    }

    /** Die Wahl des Lesers für die Notizen am Ordner. */
    BookNotes remember(BookNotes notes) {
        Map<String, List<String>> chosen = new LinkedHashMap<>();
        selected.forEach((key, fields) -> chosen.put(key, List.copyOf(fields)));
        List<String> unchosen = new ArrayList<>();
        for (TermCandidates.Candidate candidate : survey.getCandidates().getCandidates()) {
            if (!selected.containsKey(candidate.getKey())) {
                unchosen.add(candidate.getKey());
            }
        }
        return notes.withChoice(chosen, unchosen, List.copyOf(deselectedFields));
    }

    Map<String, Set<String>> selected() {
        return selected;
    }

    Set<String> deselectedFields() {
        return deselectedFields;
    }

    /** Oben im Scrollbereich, in der Breite des Sichtfensters, nie in die Höhe gezogen. */
    private static JComponent top(JComponent column) {
        ScrollColumn holder = new ScrollColumn(new BorderLayout());
        holder.add(column, BorderLayout.NORTH);
        return holder;
    }

    private static JPanel wrap() {
        JPanel panel = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 6));
        panel.setOpaque(false);
        return panel;
    }

    private static <T extends JComponent> T left(T component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        return component;
    }
}
