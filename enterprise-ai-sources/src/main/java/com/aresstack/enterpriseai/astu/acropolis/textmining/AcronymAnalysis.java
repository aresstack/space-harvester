package com.aresstack.enterpriseai.astu.acropolis.textmining;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the acronym extractor found in one document version: acronyms and technical identifiers with their
 * occurrences and the long forms the document states. Independent of any display.
 */
public final class AcronymAnalysis {

    private final List<AcronymFinding> findings;
    private final String sentenceMethod;

    public AcronymAnalysis(List<AcronymFinding> findings, String sentenceMethod) {
        this.findings = Collections.unmodifiableList(new ArrayList<AcronymFinding>(findings));
        this.sentenceMethod = sentenceMethod == null ? "" : sentenceMethod;
    }

    public static AcronymAnalysis empty() {
        return new AcronymAnalysis(Collections.<AcronymFinding>emptyList(), "");
    }

    /** All findings in order of their first occurrence. */
    public List<AcronymFinding> findings() {
        return findings;
    }

    /** Only the acronyms (what a reader marks). */
    public List<AcronymFinding> acronyms() {
        return ofKind(TermKind.ACRONYM);
    }

    /** Only the technical identifiers. */
    public List<AcronymFinding> identifiers() {
        return ofKind(TermKind.TECHNICAL_IDENTIFIER);
    }

    /** The acronym with this search form, or {@code null}. */
    public AcronymFinding acronym(String normalized) {
        for (AcronymFinding finding : findings) {
            if (finding.kind() == TermKind.ACRONYM && finding.normalized().equals(normalized)) {
                return finding;
            }
        }
        return null;
    }

    /** Which sentence segmentation was used (e.g. {@code opennlp-de} or {@code splitter}). */
    public String sentenceMethod() {
        return sentenceMethod;
    }

    private List<AcronymFinding> ofKind(TermKind kind) {
        List<AcronymFinding> selected = new ArrayList<AcronymFinding>();
        for (AcronymFinding finding : findings) {
            if (finding.kind() == kind) {
                selected.add(finding);
            }
        }
        return Collections.unmodifiableList(selected);
    }

    @Override
    public String toString() {
        return "AcronymAnalysis[" + findings + ", sentences=" + sentenceMethod + "]";
    }
}
