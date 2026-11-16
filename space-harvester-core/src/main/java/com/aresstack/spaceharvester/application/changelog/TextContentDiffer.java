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
        return Optional.of(diff(paragraphs(before.get().text()), paragraphs(after.get().text())));
    }

    static List<String> paragraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        for (String line : text.split("\n")) {
            String paragraph = line.strip();
            if (!paragraph.isEmpty()) {
                paragraphs.add(paragraph);
            }
        }
        return paragraphs;
    }

    private enum Op { EQUAL, REMOVE, ADD }

    static TextDiff diff(List<String> before, List<String> after) {
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
            List<String> removed = new ArrayList<>();
            List<String> added = new ArrayList<>();
            while (k < ops.size() && ops.get(k) != Op.EQUAL) {
                if (ops.get(k) == Op.REMOVE) {
                    removed.add(x.get(i++));
                } else {
                    added.add(y.get(j++));
                }
                k++;
            }
            int paired = Math.min(removed.size(), added.size());
            for (int p = 0; p < paired; p++) {
                limits.add(ParagraphChange.Kind.CHANGED, removed.get(p), added.get(p));
            }
            for (int p = paired; p < removed.size(); p++) {
                limits.add(ParagraphChange.Kind.REMOVED, removed.get(p), null);
            }
            for (int p = paired; p < added.size(); p++) {
                limits.add(ParagraphChange.Kind.ADDED, null, added.get(p));
            }
        }
        return new TextDiff(limits.changes, limits.truncated);
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

    private static final class Limits {
        final List<ParagraphChange> changes = new ArrayList<>();
        int total;
        boolean truncated;

        void add(ParagraphChange.Kind kind, String before, String after) {
            if (truncated) {
                return;
            }
            String shortBefore = cut(before);
            String shortAfter = cut(after);
            int size = (shortBefore == null ? 0 : shortBefore.length()) + (shortAfter == null ? 0 : shortAfter.length());
            if (changes.size() >= MAX_CHANGES || total + size > MAX_TOTAL) {
                truncated = true;
                return;
            }
            total += size;
            changes.add(new ParagraphChange(kind, shortBefore, shortAfter));
        }

        private static String cut(String paragraph) {
            if (paragraph == null || paragraph.length() <= MAX_PARAGRAPH) {
                return paragraph;
            }
            int end = MAX_PARAGRAPH - 1;
            if (Character.isHighSurrogate(paragraph.charAt(end - 1))) {
                end--;
            }
            return paragraph.substring(0, end) + "…";
        }
    }
}
