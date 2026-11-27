package com.aresstack.spaceharvester.source;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Überschriften eines PDFs nach der Schriftgröße (Seitenstruktur, Angelo 27.11.2026). Ein PDF kennt keine Überschriften;
 * eine Zeile gilt als Überschrift, wenn ihre Schrift größer ist als die des Fließtexts (die Größe mit den meisten
 * Zeichen). Die größte Schrift ist Ebene 1, jede kleinere die nächste. Fett allein macht keine Überschrift (Angelo
 * 27.11.2026: fett gesetzte Absätze sind keine Überschriften). Kehrt derselbe Text auf mehreren Seiten groß wieder, ist
 * er eine Kopfzeile, keine Überschrift; Zeilen ohne Buchstaben (etwa Seitenzahlen) auch nicht.
 *
 * <p>Gelesen wird mit PDFBox, derselben Bibliothek, die Tika für PDFs nutzt; ein Absatz des Tika-Texts ist eine
 * Überschrift, wenn er genau einer Überschriftenzeile entspricht oder mehreren aufeinanderfolgenden derselben Ebene (eine
 * umbrochene Überschrift). Scheitert das Lesen (verschlüsselt, beschädigt), gibt es keine Überschriften, der Text bleibt.
 */
final class PdfHeadings {

    /** Ebenen wie bei HTML-Überschriften ({@code h1} bis {@code h6}). */
    private static final int DEEPEST_LEVEL = 6;

    /** Schriftgrößen werden auf Zehntelpunkt verglichen, damit Rundungsreste der Umrechnung keine eigene Ebene bilden. */
    private static final float SIZE_STEPS_PER_POINT = 10f;

    static final PdfHeadings NONE = new PdfHeadings(Map.of());

    private final Map<String, Integer> levels;

    private PdfHeadings(Map<String, Integer> levels) {
        this.levels = Map.copyOf(levels);
    }

    /** Ebene des Absatzes, wenn er eine Überschrift ist. */
    Optional<Integer> level(String paragraph) {
        if (levels.isEmpty() || paragraph == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(levels.get(key(paragraph)));
    }

    boolean isEmpty() {
        return levels.isEmpty();
    }

    /**
     * Liest die Überschriften der Datei.
     *
     * @param onPage nach jeder Seite gerufen, etwa damit die Fortschrittsüberwachung den Lauf nicht für hängend hält
     */
    static PdfHeadings read(Path file, Runnable onPage) {
        if (file == null) {
            return NONE;
        }
        try (PDDocument document = Loader.loadPDF(file.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            Lines lines = new Lines(onPage);
            lines.writeText(document, new StringWriter());
            lines.endLine();
            return of(lines.lines);
        } catch (IOException | RuntimeException e) {
            return NONE;
        }
    }

    /** Überschriften aus Zeilen mit Text, Schriftgröße und Seite; sichtbar für Tests. */
    static PdfHeadings of(List<Line> lines) {
        Map<Float, Long> charsPerSize = new HashMap<>();
        for (Line line : lines) {
            charsPerSize.merge(line.size(), (long) line.text().length(), Long::sum);
        }
        Optional<Float> body = charsPerSize.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
        if (body.isEmpty()) {
            return NONE;
        }
        Map<String, Set<Integer>> pagesPerText = new HashMap<>();
        List<Line> larger = new ArrayList<>();
        for (Line line : lines) {
            if (line.size() > body.get() && line.text().codePoints().anyMatch(Character::isLetter)) {
                larger.add(line);
                pagesPerText.computeIfAbsent(key(line.text()), k -> new HashSet<>()).add(line.page());
            }
        }
        Set<Line> headings = new HashSet<>(larger.stream()
                .filter(line -> pagesPerText.get(key(line.text())).size() == 1).toList());
        List<Float> sizes = new ArrayList<>(new TreeSet<>(headings.stream().map(Line::size).toList()).descendingSet());
        Map<String, Integer> levels = new HashMap<>();
        Line previous = null;
        StringBuilder run = new StringBuilder();
        for (Line line : lines) {
            boolean heading = headings.contains(line);
            int level = heading ? Math.min(DEEPEST_LEVEL, sizes.indexOf(line.size()) + 1) : 0;
            if (heading) {
                levels.putIfAbsent(key(line.text()), level);
                // Umbrochene Überschrift: Folgezeilen derselben Größe auf derselben Seite gehören dazu.
                if (previous != null && previous.size() == line.size() && previous.page() == line.page()) {
                    run.append(' ').append(line.text());
                    levels.putIfAbsent(key(run.toString()), level);
                } else {
                    run.setLength(0);
                    run.append(line.text());
                }
                previous = line;
            } else {
                previous = null;
                run.setLength(0);
            }
        }
        return new PdfHeadings(levels);
    }

    private static String key(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    /** Eine Zeile des PDFs: Text, vorherrschende Schriftgröße (Punkt, auf Zehntel gerundet) und Seite. */
    record Line(String text, float size, int page) {
    }

    /** Sammelt Zeilen; die Größe einer Zeile ist die mit den meisten Zeichen. */
    private static final class Lines extends PDFTextStripper {

        private final Runnable onPage;
        private final List<Line> lines = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private final Map<Float, Integer> sizes = new HashMap<>();

        Lines(Runnable onPage) {
            this.onPage = onPage;
        }

        @Override
        protected void writeString(String string, List<TextPosition> positions) throws IOException {
            text.append(string);
            for (TextPosition position : positions) {
                float size = Math.round(position.getFontSizeInPt() * SIZE_STEPS_PER_POINT) / SIZE_STEPS_PER_POINT;
                sizes.merge(size, position.getUnicode() == null ? 1 : position.getUnicode().length(), Integer::sum);
            }
        }

        @Override
        protected void writeWordSeparator() throws IOException {
            text.append(' ');
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            endLine();
        }

        @Override
        protected void writeParagraphEnd() throws IOException {
            endLine();
        }

        @Override
        protected void endPage(org.apache.pdfbox.pdmodel.PDPage page) throws IOException {
            endLine();
            if (onPage != null) {
                onPage.run();
            }
        }

        void endLine() {
            String line = key(text.toString());
            if (!line.isEmpty() && !sizes.isEmpty()) {
                float size = sizes.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
                lines.add(new Line(line, size, getCurrentPageNo()));
            }
            text.setLength(0);
            sizes.clear();
        }
    }
}
