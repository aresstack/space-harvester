package com.aresstack.spaceharvester.application.changelog;

import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Unterschied der Lesetexte nach Absätzen (ADR 0030, Abschnitt 4) über die Zuordnung {@link ParagraphAlignment}:
 * gemeinsamer Anfang und gemeinsames Ende fallen weg, der Rest wird über die längste gemeinsame Folge verglichen;
 * entfallene und hinzugekommene Absätze an derselben Stelle gelten paarweise als geändert. In der Länge begrenzt.
 */
public final class TextContentDiffer implements ContentDiffer {

    static final int MAX_CHANGES = 50;
    static final int MAX_PARAGRAPH = 500;
    static final int MAX_TOTAL = 8000;
    /** Höchstens so viele Überschriften im Pfad eines Absatzes, die tiefsten; je Überschrift höchstens so lang. */
    static final int MAX_HEADINGS = 4;
    static final int MAX_HEADING = 120;

    private final PageTextProjector projector;

    public TextContentDiffer(PageTextProjector projector) {
        this.projector = Objects.requireNonNull(projector, "projector");
    }

    @Override
    public Optional<TextDiff> diff(PageSnapshot older, PageSnapshot newer) {
        Optional<PageText> before = projector.project(older);
        Optional<PageText> after = projector.project(newer);
        if (before.isEmpty() || after.isEmpty()) {
            return Optional.empty();
        }
        List<List<String>> beforeHeadings = new ArrayList<>();
        List<List<String>> afterHeadings = new ArrayList<>();
        List<String> x = paragraphs(before.get(), beforeHeadings);
        List<String> y = paragraphs(after.get(), afterHeadings);
        return Optional.of(diff(x, y, beforeHeadings, afterHeadings));
    }

    static List<String> paragraphs(String text) {
        return paragraphs(new PageText("plain", text, List.of()), new ArrayList<>());
    }

    /**
     * Die Absätze des Lesetexts; {@code headings} erhält parallel je Absatz den Überschriftenpfad seines Abschnitts
     * (ADR 0031), vor der ersten Überschrift leer.
     */
    static List<String> paragraphs(PageText text, List<List<String>> headings) {
        List<List<String>> sectionPaths = new ArrayList<>();
        List<PageText.Section> open = new ArrayList<>();
        for (PageText.Section section : text.sections()) {
            open.removeIf(outer -> outer.level() >= section.level());
            open.add(section);
            sectionPaths.add(open.stream().map(found -> cut(found.heading().strip(), MAX_HEADING)).toList());
        }
        List<String> paragraphs = new ArrayList<>();
        String content = text.text();
        int offset = 0;
        for (String line : content.split("\n", -1)) {
            String paragraph = line.strip();
            if (!paragraph.isEmpty()) {
                paragraphs.add(paragraph);
                headings.add(path(text.sections(), sectionPaths, offset));
            }
            offset += line.length() + 1;
        }
        return paragraphs;
    }

    private static List<String> path(List<PageText.Section> sections, List<List<String>> paths, int offset) {
        int found = -1;
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).start() <= offset) {
                found = i;
            }
        }
        if (found < 0) {
            return List.of();
        }
        List<String> path = paths.get(found);
        return path.size() <= MAX_HEADINGS ? path : List.copyOf(path.subList(path.size() - MAX_HEADINGS,
                path.size()));
    }

    static TextDiff diff(List<String> before, List<String> after) {
        return diff(before, after, null, null);
    }

    /**
     * Mit Überschriftenpfaden je Absatz, parallel zu den Absätzen; {@code null} ohne. Die Zuordnung kommt aus
     * {@link ParagraphAlignment}; hier kommen nur Kürzung und Obergrenzen dazu. Ein zu großer Mittelteil gilt als ganz
     * ersetzt.
     */
    static TextDiff diff(List<String> before, List<String> after, List<List<String>> beforeHeadings,
                         List<List<String>> afterHeadings) {
        Limits limits = new Limits();
        // Der Pfad kommt aus dem neuen Text, bei entfallenen Absätzen aus dem alten.
        for (ParagraphAlignment.Step step : ParagraphAlignment.alignOrReplace(before, after)) {
            switch (step.kind()) {
                case EQUAL -> { }
                case CHANGE -> limits.add(ParagraphChange.Kind.CHANGED, before.get(step.before()),
                        after.get(step.after()), headings(afterHeadings, step.after()));
                case REMOVE -> limits.add(ParagraphChange.Kind.REMOVED, before.get(step.before()), null,
                        headings(beforeHeadings, step.before()));
                case ADD -> limits.add(ParagraphChange.Kind.ADDED, null, after.get(step.after()),
                        headings(afterHeadings, step.after()));
            }
        }
        return new TextDiff(limits.changes, limits.truncated);
    }

    private static List<String> headings(List<List<String>> headings, int index) {
        return headings == null || index >= headings.size() ? List.of() : headings.get(index);
    }

    private static String cut(String paragraph, int limit) {
        if (paragraph == null || paragraph.length() <= limit) {
            return paragraph;
        }
        int end = limit - 1;
        if (Character.isHighSurrogate(paragraph.charAt(end - 1))) {
            end--;
        }
        return paragraph.substring(0, end) + "…";
    }

    private static final class Limits {
        final List<ParagraphChange> changes = new ArrayList<>();
        int total;
        boolean truncated;

        void add(ParagraphChange.Kind kind, String before, String after, List<String> headings) {
            if (truncated) {
                return;
            }
            String shortBefore = cut(before, MAX_PARAGRAPH);
            String shortAfter = cut(after, MAX_PARAGRAPH);
            int size = (shortBefore == null ? 0 : shortBefore.length()) + (shortAfter == null ? 0 : shortAfter.length());
            if (changes.size() >= MAX_CHANGES || total + size > MAX_TOTAL) {
                truncated = true;
                return;
            }
            total += size;
            changes.add(new ParagraphChange(kind, shortBefore, shortAfter, headings));
        }

    }
}
