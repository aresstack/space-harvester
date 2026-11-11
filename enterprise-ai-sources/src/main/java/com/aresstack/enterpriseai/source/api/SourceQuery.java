package com.aresstack.enterpriseai.source.api;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Suchanfrage an eine durchsuchbare Quelle ({@link SearchableKnowledgeSource}). Der Text wird von der
 * Quelle in ihrer eigenen Syntax interpretiert (MediaWiki-Volltextsuche, Confluence-CQL-Textsuche); mit
 * {@link #titleOnly()} nur im Titel, wo die Quelle das kann.
 */
public final class SourceQuery {

    public static final int DEFAULT_LIMIT = 20;

    private final String text;
    private final int limit;
    private final Set<String> excludedPartitions;
    private final Set<String> includedPartitions;
    private final boolean titleOnly;

    public SourceQuery(String text, int limit) {
        this(text, limit, Collections.<String>emptySet(), Collections.<String>emptySet(), false);
    }

    private SourceQuery(String text, int limit, Set<String> excludedPartitions, Set<String> includedPartitions,
                        boolean titleOnly) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        this.text = text;
        this.limit = limit;
        this.excludedPartitions = excludedPartitions;
        this.includedPartitions = includedPartitions;
        this.titleOnly = titleOnly;
    }

    public static SourceQuery of(String text) {
        return new SourceQuery(text, DEFAULT_LIMIT);
    }

    public String text() {
        return text;
    }

    public int limit() {
        return limit;
    }

    /** Schlüssel der Bereiche ({@link KnowledgeSourcePort#partitions()}), die die Suche auslässt; leer = alle. */
    public Set<String> excludedPartitions() {
        return excludedPartitions;
    }

    /**
     * Schlüssel der Bereiche, auf die die Suche sich beschränkt; leer = keine Beschränkung. Quellen ohne Bereiche
     * oder ohne eigene Bereichsfilterung dürfen das ignorieren; Aufrufer prüfen den Bereich der Treffer selbst.
     */
    public Set<String> includedPartitions() {
        return includedPartitions;
    }

    /**
     * Ob nur im Titel gesucht wird (Confluence: CQL {@code title ~}); {@code false} = Volltext. Quellen ohne
     * Titelsuche dürfen das ignorieren und suchen dann im Volltext; Aufrufer prüfen den Titel der Treffer selbst.
     */
    public boolean titleOnly() {
        return titleOnly;
    }

    /** Dieselbe Anfrage nur im Titel ({@code true}) oder im Volltext ({@code false}). */
    public SourceQuery withTitleOnly(boolean titleOnly) {
        return new SourceQuery(text, limit, excludedPartitions, includedPartitions, titleOnly);
    }

    /** Dieselbe Anfrage ohne die angegebenen Bereiche. */
    public SourceQuery withExcludedPartitions(Collection<String> keys) {
        return new SourceQuery(text, limit, keys(keys), includedPartitions, titleOnly);
    }

    /**
     * Dieselbe Anfrage nur in den angegebenen Bereichen, etwa wenn wenige Bereiche gewählt und sehr viele
     * abgewählt sind; ein Ausschluss gilt zusätzlich.
     */
    public SourceQuery withIncludedPartitions(Collection<String> keys) {
        return new SourceQuery(text, limit, excludedPartitions, keys(keys), titleOnly);
    }

    private static Set<String> keys(Collection<String> keys) {
        Set<String> result = new LinkedHashSet<String>();
        if (keys != null) {
            for (String key : keys) {
                if (key != null && !key.trim().isEmpty()) {
                    result.add(key.trim());
                }
            }
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public String toString() {
        return "SourceQuery{text='" + text + "', limit=" + limit + (titleOnly ? ", titleOnly" : "") + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SourceQuery)) {
            return false;
        }
        SourceQuery that = (SourceQuery) o;
        return limit == that.limit && titleOnly == that.titleOnly && text.equals(that.text)
                && excludedPartitions.equals(that.excludedPartitions)
                && includedPartitions.equals(that.includedPartitions);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * (31 * text.hashCode() + limit) + excludedPartitions.hashCode())
                + includedPartitions.hashCode()) + (titleOnly ? 1 : 0);
    }
}
