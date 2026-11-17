package com.aresstack.spaceharvester.application.standing;

import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Liest Hinweise auf überholte Seiten und Entwürfe aus Labels und Titeln der Seite und ihrer Vorfahren (ADR 0032, FL6).
 * Labels sind alle Attribute der Quelle, deren Name auf {@code .labels} endet (kommagetrennt, bei Confluence
 * {@code confluence.labels}); Labels der Vorfahren zählen nicht, nur deren Titel („Lage“).
 */
final class PageMarks {

    /** Höchstens so viele Vorfahren; schützt vor Zyklen in fehlerhaften Kopfdaten. */
    static final int MAX_DEPTH = 64;
    private static final Pattern WORDS = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final Function<PageKey, Optional<PageHeader>> headers;
    private final StandingWords words;

    PageMarks(Function<PageKey, Optional<PageHeader>> headers, StandingWords words) {
        this.headers = Objects.requireNonNull(headers, "headers");
        this.words = Objects.requireNonNull(words, "words");
    }

    /** Hinweise zur Seite; leer ohne Kopfdaten. */
    List<PageMark> of(PageKey key) {
        List<PageMark> marks = new ArrayList<>();
        Optional<PageHeader> header = headers.apply(key);
        if (header.isEmpty()) {
            return marks;
        }
        PageMetadata metadata = header.get().metadata();
        for (Map.Entry<String, String> attribute : metadata.attributes().entrySet()) {
            if (attribute.getKey().endsWith(".labels") && attribute.getValue() != null) {
                for (String label : attribute.getValue().split(",")) {
                    for (String word : split(label)) {
                        add(marks, word, PageMark.Where.LABEL, key.pageId());
                    }
                }
            }
        }
        for (String word : split(metadata.title())) {
            add(marks, word, PageMark.Where.TITLE, key.pageId());
        }
        Set<String> seen = new HashSet<>(Set.of(key.pageId()));
        String parent = metadata.parentPageId();
        for (int depth = 0; parent != null && depth < MAX_DEPTH && seen.add(parent); depth++) {
            Optional<PageHeader> ancestor = headers.apply(new PageKey(key.sourceId(), parent));
            if (ancestor.isEmpty()) {
                break;
            }
            for (String word : split(ancestor.get().metadata().title())) {
                add(marks, word, PageMark.Where.ANCESTOR_TITLE, parent);
            }
            parent = ancestor.get().metadata().parentPageId();
        }
        return List.copyOf(marks);
    }

    private void add(List<PageMark> marks, String word, PageMark.Where where, String pageId) {
        String lower = StandingWords.lower(word);
        PageMark.Kind kind = words.outdated(lower) ? PageMark.Kind.OUTDATED
                : words.draft(lower) ? PageMark.Kind.DRAFT : null;
        if (kind != null) {
            PageMark mark = new PageMark(kind, where, lower, pageId);
            if (!marks.contains(mark)) {
                marks.add(mark);
            }
        }
    }

    private static List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return List.of(WORDS.split(text.strip())).stream().filter(word -> !word.isEmpty()).toList();
    }
}
