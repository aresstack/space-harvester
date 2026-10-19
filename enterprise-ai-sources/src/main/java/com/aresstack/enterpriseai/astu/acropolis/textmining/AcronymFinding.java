package com.aresstack.enterpriseai.astu.acropolis.textmining;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * All occurrences of one acronym or technical identifier in one document, grouped by its normalized form, with the
 * long forms this document states for it. Two different long forms stay two definitions; they are never merged.
 */
public final class AcronymFinding {

    private final String normalized;
    private final TermKind kind;
    private final List<String> surfaceForms;
    private final List<TextSpan> occurrences;
    private final List<AcronymDefinition> definitions;

    public AcronymFinding(String normalized, TermKind kind, List<String> surfaceForms, List<TextSpan> occurrences,
                          List<AcronymDefinition> definitions) {
        if (normalized == null || normalized.isEmpty() || kind == null) {
            throw new IllegalArgumentException("normalized form and kind are required");
        }
        this.normalized = normalized;
        this.kind = kind;
        this.surfaceForms = Collections.unmodifiableList(new ArrayList<String>(new LinkedHashSet<String>(surfaceForms)));
        List<TextSpan> sorted = new ArrayList<TextSpan>(occurrences);
        Collections.sort(sorted);
        this.occurrences = Collections.unmodifiableList(sorted);
        this.definitions = Collections.unmodifiableList(new ArrayList<AcronymDefinition>(definitions));
    }

    /** The search form: upper case, without dots and plural {@code s}. */
    public String normalized() {
        return normalized;
    }

    public TermKind kind() {
        return kind;
    }

    /** Every spelling seen in the text, most frequent first. */
    public List<String> surfaceForms() {
        return surfaceForms;
    }

    /** The most frequent spelling. */
    public String shortForm() {
        return surfaceForms.isEmpty() ? normalized : surfaceForms.get(0);
    }

    /** Where it stands, in text order. */
    public List<TextSpan> occurrences() {
        return occurrences;
    }

    public List<AcronymDefinition> definitions() {
        return definitions;
    }

    /** {@link FindingStatus#EVIDENCED} as soon as this document states a long form. */
    public FindingStatus status() {
        return definitions.isEmpty() ? FindingStatus.CANDIDATE : FindingStatus.EVIDENCED;
    }

    /** The distinct long forms in order of first appearance (by {@link AcronymDefinition#normalizedLongForm()}). */
    public List<String> longForms() {
        Map<String, String> distinct = new LinkedHashMap<String, String>();
        for (AcronymDefinition definition : definitions) {
            String key = definition.normalizedLongForm();
            if (!distinct.containsKey(key)) {
                distinct.put(key, definition.longForm());
            }
        }
        return Collections.unmodifiableList(new ArrayList<String>(distinct.values()));
    }

    /** Whether the document states more than one different long form. */
    public boolean hasConflictingLongForms() {
        return longForms().size() > 1;
    }

    /** The spellings as a set, for matching rendered text. */
    public Set<String> surfaceSet() {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(surfaceForms));
    }

    @Override
    public String toString() {
        return "AcronymFinding[" + normalized + ", " + kind + ", " + status() + ", " + occurrences.size() + "x, "
                + longForms() + "]";
    }
}
