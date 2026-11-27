package com.aresstack.knowledge4j.source;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Where a text came from: every source it was derived from and the highest classification among them. It
 * travels with derived texts (prompts with passages, model answers, derived search queries, summaries) and is
 * only ever widened by {@link #merge}, never narrowed. A text that has no source of its own, such as the task
 * the user typed, still has a classification: the one the host gave the run.
 */
public final class Provenance {

    private static final Provenance UNKNOWN = new Provenance(Collections.emptySet(), Classification.INTERNAL);

    private final Set<SourceRef> sources;
    private final Classification classification;

    private Provenance(Set<SourceRef> sources, Classification classification) {
        this.sources = Collections.unmodifiableSet(new LinkedHashSet<>(sources));
        this.classification = classification == null ? Classification.INTERNAL : classification;
    }

    /** No known origin: counts as {@link Classification#INTERNAL}. */
    public static Provenance unknown() {
        return UNKNOWN;
    }

    /** A text without sources of its own, e.g. the task, classified by the host. */
    public static Provenance classified(Classification classification) {
        return new Provenance(Collections.emptySet(), classification);
    }

    public static Provenance of(Classification classification, Collection<SourceRef> sources) {
        return new Provenance(sources == null ? Collections.emptySet() : new LinkedHashSet<>(sources),
                classification);
    }

    public static Provenance of(Classification classification, SourceRef source) {
        return of(classification, source == null ? Collections.emptySet() : Set.of(source));
    }

    /** The union of both origins with the higher classification. */
    public Provenance merge(Provenance other) {
        if (other == null) {
            return this;
        }
        Set<SourceRef> all = new LinkedHashSet<>(sources);
        all.addAll(other.sources);
        return new Provenance(all, classification.max(other.classification));
    }

    public Set<SourceRef> sources() {
        return sources;
    }

    public Classification classification() {
        return classification;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Provenance p && p.classification == classification && p.sources.equals(sources);
    }

    @Override
    public int hashCode() {
        return 31 * sources.hashCode() + classification.hashCode();
    }

    /** Sizes only; never the content of the sources. */
    @Override
    public String toString() {
        return "Provenance[" + classification + ", " + sources.size() + " sources]";
    }
}
