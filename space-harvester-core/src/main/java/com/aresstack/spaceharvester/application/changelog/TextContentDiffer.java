package com.aresstack.spaceharvester.application.changelog;

import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Unterschied der Lesetexte nach Absätzen (ADR 0030, Abschnitt 4): gemeinsamer Anfang und gemeinsames Ende fallen weg,
 * der Rest wird über die längste gemeinsame Folge verglichen; entfallene und hinzugekommene Absätze an derselben
 * Stelle gelten paarweise als geändert. In der Länge begrenzt.
 */
public final class TextContentDiffer implements ContentDiffer {

    static final int MAX_CHANGES = 50;
    static final int MAX_PARAGRAPH = 500;
    static final int MAX_TOTAL = 8000;
    /** Höchstens so viele Überschriften im Pfad eines Absatzes, die tiefsten; je Überschrift höchstens so lang. */
    static final int MAX_HEADINGS = 4;
    static final int MAX_HEADING = 120;
    /** Größte Vergleichstabelle; darüber gilt der ganze Mittelteil als ersetzt. */
    private static final long MAX_CELLS = 1_000_000;

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

    private enum Op { EQUAL, REMOVE, ADD }

    static TextDiff diff(List<String> before, List<String> after) {
        return diff(before, after, null, null);
    }

    /** Mit Überschriftenpfaden je Absatz, parallel zu den Absätzen; {@code null} ohne. */
    static TextDiff diff(List<String> before, List<String> after, List<List<String>> beforeHeadings,
                         List<List<String>> afterHeadings) {
        int start = 0;
        while (start < before.size() && start < after.size() && before.get(start).equals(after.get(start))) {
            start++;
        }
        int endBefore = before.size();
        int endAfter = after.size();
        while (endBefore > start && endAfter > start && before.get(endBefore - 1).equals(after.get(endAfter - 1))) {
            endBefore--;
            endAfter--;
        }
        List<String> x = before.subList(start, endBefore);
        List<String> y = after.subList(start, endAfter);
        List<Op> ops = (long) (x.size() + 1) * (y.size() + 1) > MAX_CELLS ? replaced(x.size(), y.size())
                : common(x, y);
        Limits limits = new Limits();
        int i = 0;
        int j = 0;
        int k = 0;
        while (k < ops.size()) {
            if (ops.get(k) == Op.EQUAL) {
                i++;
                j++;
                k++;
                continue;
            }
            List<Integer> removed = new ArrayList<>();
            List<Integer> added = new ArrayList<>();
            while (k < ops.size() && ops.get(k) != Op.EQUAL) {
                if (ops.get(k) == Op.REMOVE) {
                    removed.add(start + i++);
                } else {
                    added.add(start + j++);
                }
                k++;
            }
            int paired = Math.min(removed.size(), added.size());
            // Der Pfad kommt aus dem neuen Text, bei entfallenen Absätzen aus dem alten.
            for (int p = 0; p < paired; p++) {
                limits.add(ParagraphChange.Kind.CHANGED, before.get(removed.get(p)), after.get(added.get(p)),
                        headings(afterHeadings, added.get(p)));
            }
            for (int p = paired; p < removed.size(); p++) {
                limits.add(ParagraphChange.Kind.REMOVED, before.get(removed.get(p)), null,
                        headings(beforeHeadings, removed.get(p)));
            }
            for (int p = paired; p < added.size(); p++) {
                limits.add(ParagraphChange.Kind.ADDED, null, after.get(added.get(p)),
                        headings(afterHeadings, added.get(p)));
            }
        }
        return new TextDiff(limits.changes, limits.truncated);
    }

    private static List<String> headings(List<List<String>> headings, int index) {
        return headings == null || index >= headings.size() ? List.of() : headings.get(index);
    }

    private static List<Op> replaced(int removed, int added) {
        List<Op> ops = new ArrayList<>(removed + added);
        for (int i = 0; i < removed; i++) {
            ops.add(Op.REMOVE);
        }
        for (int j = 0; j < added; j++) {
            ops.add(Op.ADD);
        }
        return ops;
    }

    /** Längste gemeinsame Folge, entfallene vor hinzugekommenen Absätzen. */
    private static List<Op> common(List<String> x, List<String> y) {
        int n = x.size();
        int m = y.size();
        int[][] length = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                length[i][j] = x.get(i).equals(y.get(j)) ? length[i + 1][j + 1] + 1
                        : Math.max(length[i + 1][j], length[i][j + 1]);
            }
        }
        List<Op> ops = new ArrayList<>(n + m);
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (x.get(i).equals(y.get(j))) {
                ops.add(Op.EQUAL);
                i++;
                j++;
            } else if (length[i + 1][j] >= length[i][j + 1]) {
                ops.add(Op.REMOVE);
                i++;
            } else {
                ops.add(Op.ADD);
                j++;
            }
        }
        while (i++ < n) {
            ops.add(Op.REMOVE);
        }
        while (j++ < m) {
            ops.add(Op.ADD);
        }
        return ops;
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
