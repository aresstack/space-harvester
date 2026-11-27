package com.aresstack.writing4j.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Component 7: what the memory holds for one question: the direct evidence and the leads, always apart, and the
 * counts the research lead (component 8) decides on. It records what was found and claims nothing about the
 * question: no field says it is answered or complete, and "Kein Beleg gefunden" says only that the passages judged
 * so far carry none. Immutable.
 */
public final class QuestionEvidence {

    public static final String LABEL_DIRECT = "Belegt";
    public static final String LABEL_LEAD = "Weiterführender Hinweis";
    public static final String LABEL_NONE = "Kein Beleg gefunden";

    private final ResearchQuestion question;
    private final List<EvidenceVerdict> direct;
    private final List<EvidenceVerdict> leads;
    private final int independentSources;
    private final int distinctDomains;
    private final int judgedPassages;
    private final int errors;
    private final int downgraded;
    private final int lastRoundWithNewEvidence;
    private final int roundsWithoutNewEvidence;
    private final int searchesWithoutNewEvidence;

    QuestionEvidence(ResearchQuestion question, List<EvidenceVerdict> direct, List<EvidenceVerdict> leads,
                     int independentSources, int distinctDomains, int judgedPassages, int errors, int downgraded,
                     int lastRoundWithNewEvidence, int roundsWithoutNewEvidence, int searchesWithoutNewEvidence) {
        this.question = question;
        this.direct = Collections.unmodifiableList(new ArrayList<EvidenceVerdict>(direct));
        this.leads = Collections.unmodifiableList(new ArrayList<EvidenceVerdict>(leads));
        this.independentSources = independentSources;
        this.distinctDomains = distinctDomains;
        this.judgedPassages = judgedPassages;
        this.errors = errors;
        this.downgraded = downgraded;
        this.lastRoundWithNewEvidence = lastRoundWithNewEvidence;
        this.roundsWithoutNewEvidence = roundsWithoutNewEvidence;
        this.searchesWithoutNewEvidence = searchesWithoutNewEvidence;
    }

    public ResearchQuestion getQuestion() {
        return question;
    }

    /** The direct evidence ("Belegt"), one verdict per distinct text, in the order it came in. */
    public List<EvidenceVerdict> getDirect() {
        return direct;
    }

    /** The leads ("Weiterführender Hinweis"), one per distinct text that is not direct evidence already. */
    public List<EvidenceVerdict> getLeads() {
        return leads;
    }

    public int getDirectCount() {
        return direct.size();
    }

    public int getLeadCount() {
        return leads.size();
    }

    /**
     * Independent sources among the direct evidence: sources with the same text are one group, whatever their domain.
     */
    public int getIndependentSources() {
        return independentSources;
    }

    /** Distinct domains among the direct evidence; a source without an address counts as a domain of its own. */
    public int getDistinctDomains() {
        return distinctDomains;
    }

    /** Passages with a verdict for this question, FEHLER left out. */
    public int getJudgedPassages() {
        return judgedPassages;
    }

    /** Passages whose last verdict is FEHLER; they are offered again ({@link ResearchMemory#toRetry}). */
    public int getErrors() {
        return errors;
    }

    /** Passages the model called BELEGT that the check lowered: quote not in the passage, or a number not in it. */
    public int getDowngraded() {
        return downgraded;
    }

    /** The last round in which a text became direct evidence for the first time; 0 when none did. */
    public int getLastRoundWithNewEvidence() {
        return lastRoundWithNewEvidence;
    }

    /** Rounds in which passages were judged for the question after the last new direct evidence. */
    public int getRoundsWithoutNewEvidence() {
        return roundsWithoutNewEvidence;
    }

    /** Finished searches naming the question after the last one that brought new direct evidence. */
    public int getSearchesWithoutNewEvidence() {
        return searchesWithoutNewEvidence;
    }

    /** The counts by the names a decision of the lead stores them under. */
    public Map<String, Integer> counts() {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        counts.put("direkt", direct.size());
        counts.put("hinweise", leads.size());
        counts.put("quellen", independentSources);
        counts.put("domains", distinctDomains);
        counts.put("geprueft", judgedPassages);
        counts.put("fehler", errors);
        counts.put("rundenOhneNeuenBeleg", roundsWithoutNewEvidence);
        counts.put("suchenOhneNeuenBeleg", searchesWithoutNewEvidence);
        return counts;
    }

    /** {@code "Belegt: 3 (2 Quellen) · Weiterführender Hinweis: 1"} or {@code "Kein Beleg gefunden"}. */
    public String summary() {
        if (direct.isEmpty() && leads.isEmpty()) {
            return LABEL_NONE;
        }
        return LABEL_DIRECT + ": " + direct.size()
                + (direct.isEmpty() ? "" : " (" + independentSources + (independentSources == 1 ? " Quelle)"
                : " Quellen)"))
                + " · " + LABEL_LEAD + ": " + leads.size();
    }
}
