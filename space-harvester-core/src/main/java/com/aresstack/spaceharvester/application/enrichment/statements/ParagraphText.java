package com.aresstack.spaceharvester.application.enrichment.statements;

import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.passages.PassageSegmentation;
import com.aresstack.spaceharvester.application.passages.SentenceSegmenter;
import java.util.ArrayList;
import java.util.List;

/**
 * Eine Einheit der Analyse: ein Absatz der Textprojektion (oder eine Folge seiner Sätze, wenn der Absatz länger als die
 * Grenze ist), in Sätze mit Wortbereichen zerlegt. Alles zeigt in den Text der Projektion, damit eine Aussage ihre Stelle
 * in genau dieser Fassung behält.
 *
 * <p>Abweichung vom Enterprise AI Client: Die Absätze kommen aus den Strukturblöcken der Passagenbildung
 * ({@link PassageSegmentation#blocks}, ADR 0020) statt aus Leerzeilen, denn eine Confluence-Projektion trennt Blöcke mit
 * einer Zeile. Absätze, Listenpunkte und Zitate gelten; Überschriften, Tabellenzeilen und Code nicht, ebenso Zeilen ohne
 * Fließtext ({@link #isRunningText}).
 */
public final class ParagraphText {

    private final String documentText;
    private final int index;
    private final TextSpan span;
    private final List<SentenceText> sentences;

    private ParagraphText(String documentText, int index, TextSpan span, List<SentenceText> sentences) {
        this.documentText = documentText;
        this.index = index;
        this.span = span;
        this.sentences = List.copyOf(sentences);
    }

    /** Ein Text als eine Einheit (Tests): seine Sätze nach {@code segmenter}. */
    public static ParagraphText of(String text, SentenceSegmenter segmenter) {
        List<ParagraphText> units = split(text, segmenter, Integer.MAX_VALUE);
        if (units.size() == 1) {
            return units.getFirst();
        }
        return new ParagraphText(text, 0, new TextSpan(0, text.length()), sentences(text, 0, text.length(),
                segmenter));
    }

    /** Wie {@link #split(PageText, SentenceSegmenter, int)} für einen Text ohne Abschnitte (Dateien, Tests). */
    public static List<ParagraphText> split(String documentText, SentenceSegmenter segmenter, int maxChars) {
        if (documentText == null || documentText.isEmpty()) {
            return List.of();
        }
        return split(new PageText("statements-text/v1", documentText, List.of()), segmenter, maxChars);
    }

    /**
     * Die analysierbaren Einheiten einer Textprojektion: ihre Absätze, lange zwischen Sätzen geteilt, sodass keine
     * Einheit {@code maxChars} überschreitet (ein einzelner längerer Satz bleibt ganz).
     */
    public static List<ParagraphText> split(PageText page, SentenceSegmenter segmenter, int maxChars) {
        List<ParagraphText> units = new ArrayList<>();
        String documentText = page.text();
        for (PassageSegmentation.Block block : PassageSegmentation.blocks(page)) {
            if (block.kind() == PassageSegmentation.Kind.TABLE_ROW || block.kind() == PassageSegmentation.Kind.CODE) {
                continue;
            }
            TextSpan paragraph = trim(documentText, new TextSpan(block.start(), block.end()));
            if (!isRunningText(paragraph.of(documentText))) {
                continue;
            }
            List<SentenceText> all = sentences(documentText, paragraph.start(), paragraph.end(), segmenter);
            List<SentenceText> run = new ArrayList<>();
            for (SentenceText sentence : all) {
                if (!run.isEmpty() && sentence.span().end() - run.getFirst().span().start() > maxChars) {
                    units.add(unit(documentText, units.size(), run));
                    run = new ArrayList<>();
                }
                run.add(sentence);
            }
            if (!run.isEmpty()) {
                units.add(unit(documentText, units.size(), run));
            }
        }
        return units;
    }

    /** 0-basierte Stelle unter den Einheiten der Seite. */
    public int index() {
        return index;
    }

    public TextSpan span() {
        return span;
    }

    public String text() {
        return span.of(documentText);
    }

    /** Der Text der ganzen Projektion. */
    public String documentText() {
        return documentText;
    }

    /** Die Sätze, ab 1 nummeriert. */
    public List<SentenceText> sentences() {
        return sentences;
    }

    /** Der Satz mit {@code number} oder {@code null}. */
    public SentenceText sentence(int number) {
        return number < 1 || number > sentences.size() ? null : sentences.get(number - 1);
    }

    /** Die Einheit, wie das Modell sie sieht: eine Zeile je Satz mit nummerierten Wörtern. */
    public String numbered() {
        StringBuilder out = new StringBuilder();
        for (SentenceText sentence : sentences) {
            out.append("Satz ").append(sentence.number()).append(": ").append(sentence.numbered(documentText))
                    .append('\n');
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return "ParagraphText[" + index + " " + span + ", " + sentences.size() + " Sätze]";
    }

    private static ParagraphText unit(String documentText, int index, List<SentenceText> run) {
        List<SentenceText> renumbered = new ArrayList<>();
        for (SentenceText sentence : run) {
            renumbered.add(SentenceText.of(documentText, renumbered.size() + 1, sentence.span()));
        }
        return new ParagraphText(documentText, index, new TextSpan(run.getFirst().span().start(),
                run.getLast().span().end()), renumbered);
    }

    /** Satzbereiche von {@code [start, end)}: die Sätze des Segmentierers, der Reihe nach wiedergefunden. */
    private static List<SentenceText> sentences(String documentText, int start, int end, SentenceSegmenter segmenter) {
        String paragraph = documentText.substring(start, end);
        List<String> found = segmenter.sentences(paragraph);
        List<SentenceText> sentences = new ArrayList<>();
        int cursor = 0;
        for (String sentence : found == null ? List.<String>of() : found) {
            String trimmed = sentence == null ? "" : sentence.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int at = paragraph.indexOf(trimmed, cursor);
            if (at < 0) {
                // Der Segmentierer hat Leerraum verändert: dann keine Sätze, der Absatz bleibt ganz.
                sentences.clear();
                sentences.add(SentenceText.of(documentText, 1, trim(documentText, new TextSpan(start, end))));
                return sentences;
            }
            sentences.add(SentenceText.of(documentText, sentences.size() + 1,
                    new TextSpan(start + at, start + at + trimmed.length())));
            cursor = at + trimmed.length();
        }
        if (sentences.isEmpty()) {
            sentences.add(SentenceText.of(documentText, 1, trim(documentText, new TextSpan(start, end))));
        }
        return sentences;
    }

    /** Fließtext: mehrere Wörter mit Kleinbuchstaben, keine Überschrift, Tabellenzeile oder Liste von Kennungen. */
    static boolean isRunningText(String text) {
        if (text.isEmpty() || text.startsWith("#") || text.startsWith("|")) {
            return false;
        }
        int letters = 0;
        int lower = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isLowerCase(c)) {
                    lower++;
                }
            }
        }
        int words = text.trim().split("\\s+").length;
        return words >= 3 && letters * 2 >= text.length() && lower * 2 >= letters;
    }

    private static TextSpan trim(String text, TextSpan span) {
        int start = span.start();
        int end = span.end();
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return new TextSpan(start, end);
    }
}
