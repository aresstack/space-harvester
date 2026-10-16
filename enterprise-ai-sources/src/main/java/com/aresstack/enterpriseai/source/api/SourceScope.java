package com.aresstack.enterpriseai.source.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Welcher Ausschnitt einer Quelle erfasst werden soll.
 *
 * <p>Startpunkte sind quellennative Locator, die nur der Adapter interpretiert (MediaWiki: Seitentitel,
 * Confluence: Space-Key oder Page-ID, Dateien: Verzeichnis). Ab den Startpunkten folgt der Adapter Links
 * bzw. Kindern bis {@link #maxDepth()}; {@code 0} heißt nur die Startpunkte selbst. Höchstens
 * {@link #maxResources()} Ressourcen werden geliefert. Bereiche in {@link #excludedPartitions()} (Schlüssel aus
 * {@link KnowledgeSourcePort#partitions()}) lässt der Adapter aus. Die Häkchen des Benutzers gelten für Indexierung
 * und Suche; wer gezielt einen Bereich aufschlägt (Klick auf einen Space im Drawer), baut den Umfang mit
 * {@link Builder#ignoringSelection()}, dann bleibt die Auswahl außen vor ({@link #selectionApplies()}).
 */
public final class SourceScope {

    /** Obergrenze, wenn keine angegeben wird; schützt vor ungewollt großen Crawls. */
    public static final int DEFAULT_MAX_RESOURCES = 500;

    private final List<String> startPoints;
    private final int maxDepth;
    private final int maxResources;
    private final Set<String> excludedPartitions;
    private final boolean selectionApplies;

    private SourceScope(List<String> startPoints, int maxDepth, int maxResources, Set<String> excludedPartitions,
                        boolean selectionApplies) {
        this.startPoints = startPoints;
        this.maxDepth = maxDepth;
        this.maxResources = maxResources;
        this.excludedPartitions = excludedPartitions;
        this.selectionApplies = selectionApplies;
    }

    /** Nur die angegebenen Startpunkte, ohne Links zu folgen. */
    public static SourceScope of(String... startPoints) {
        return builder().startPoints(startPoints).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<String> startPoints() {
        return startPoints;
    }

    public int maxDepth() {
        return maxDepth;
    }

    public int maxResources() {
        return maxResources;
    }

    /** Schlüssel der Bereiche, die der Benutzer abgehakt hat; leer = alle. */
    public Set<String> excludedPartitions() {
        return excludedPartitions;
    }

    /** {@code false}: gezieltes Aufschlagen, die abgehakten Bereiche des Benutzers gelten hier nicht. */
    public boolean selectionApplies() {
        return selectionApplies;
    }

    /** Derselbe Umfang ohne die angegebenen Bereiche. */
    public SourceScope withExcludedPartitions(Collection<String> keys) {
        Set<String> excluded = new LinkedHashSet<String>();
        if (keys != null) {
            for (String key : keys) {
                if (key != null && !key.trim().isEmpty()) {
                    excluded.add(key.trim());
                }
            }
        }
        return new SourceScope(startPoints, maxDepth, maxResources, Collections.unmodifiableSet(excluded),
                selectionApplies);
    }

    @Override
    public String toString() {
        return "SourceScope{startPoints=" + startPoints + ", maxDepth=" + maxDepth
                + ", maxResources=" + maxResources
                + (excludedPartitions.isEmpty() ? "" : ", excludedPartitions=" + excludedPartitions)
                + (selectionApplies ? "" : ", ignoringSelection") + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SourceScope)) {
            return false;
        }
        SourceScope that = (SourceScope) o;
        return maxDepth == that.maxDepth && maxResources == that.maxResources
                && selectionApplies == that.selectionApplies && startPoints.equals(that.startPoints) && excludedPartitions.equals(that.excludedPartitions);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * startPoints.hashCode() + maxDepth) + maxResources) + excludedPartitions.hashCode();
    }

    public static final class Builder {

        private final List<String> startPoints = new ArrayList<String>();
        private int maxDepth;
        private int maxResources = DEFAULT_MAX_RESOURCES;
        private boolean selectionApplies = true;

        private Builder() {
        }

        public Builder startPoint(String startPoint) {
            if (startPoint == null || startPoint.trim().isEmpty()) {
                throw new IllegalArgumentException("startPoint must not be blank");
            }
            if (!startPoints.contains(startPoint)) {
                startPoints.add(startPoint);
            }
            return this;
        }

        public Builder startPoints(String... values) {
            for (String value : values) {
                startPoint(value);
            }
            return this;
        }

        public Builder startPoints(List<String> values) {
            for (String value : values) {
                startPoint(value);
            }
            return this;
        }

        public Builder maxDepth(int value) {
            if (value < 0) {
                throw new IllegalArgumentException("maxDepth must be >= 0");
            }
            this.maxDepth = value;
            return this;
        }

        public Builder maxResources(int value) {
            if (value < 1) {
                throw new IllegalArgumentException("maxResources must be >= 1");
            }
            this.maxResources = value;
            return this;
        }

        /** Gezieltes Aufschlagen eines Bereichs: die Häkchen des Benutzers gelten nicht. */
        public Builder ignoringSelection() {
            this.selectionApplies = false;
            return this;
        }

        public SourceScope build() {
            if (startPoints.isEmpty()) {
                throw new IllegalArgumentException("at least one start point is required");
            }
            return new SourceScope(Collections.unmodifiableList(new ArrayList<String>(startPoints)),
                    maxDepth, maxResources, Collections.<String>emptySet(), selectionApplies);
        }
    }
}
