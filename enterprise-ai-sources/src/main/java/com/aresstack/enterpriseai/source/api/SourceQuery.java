package com.aresstack.enterpriseai.source.api;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Suchanfrage an eine durchsuchbare Quelle ({@link SearchableKnowledgeSource}). Der Text wird von der
 * Quelle in ihrer eigenen Syntax interpretiert (MediaWiki-Volltextsuche, Confluence-CQL-Textsuche).
 */
public final class SourceQuery {

    public static final int DEFAULT_LIMIT = 20;

    private final String text;
    private final int limit;
    private final Set<String> excludedPartitions;

    public SourceQuery(String text, int limit) {
        this(text, limit, Collections.<String>emptySet());
    }

    private SourceQuery(String text, int limit, Set<String> excludedPartitions) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        this.text = text;
        this.limit = limit;
        this.excludedPartitions = excludedPartitions;
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

    /** Dieselbe Anfrage ohne die angegebenen Bereiche. */
    public SourceQuery withExcludedPartitions(Collection<String> keys) {
        Set<String> excluded = new LinkedHashSet<String>();
        if (keys != null) {
            for (String key : keys) {
                if (key != null && !key.trim().isEmpty()) {
                    excluded.add(key.trim());
                }
            }
        }
        return new SourceQuery(text, limit, Collections.unmodifiableSet(excluded));
    }

    @Override
    public String toString() {
        return "SourceQuery{text='" + text + "', limit=" + limit + "}";
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
        return limit == that.limit && text.equals(that.text) && excludedPartitions.equals(that.excludedPartitions);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * text.hashCode() + limit) + excludedPartitions.hashCode();
    }
}
