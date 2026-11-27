package com.aresstack.writing4j.missionreport;

import com.aresstack.writing4j.sentencecheck.SentenceVerdict;
import com.aresstack.writing4j.sentencecheck.SentenceVerdict.Attribution;
import com.aresstack.writing4j.sentencecheck.SentenceVerdict.Jurisdiction;
import com.aresstack.writing4j.sentencecheck.SentenceVerdict.Verdict;
import com.aresstack.writing4j.sentencecheck.SentenceVerdicts;

import java.util.ArrayList;
import java.util.List;

/** What the sentence check (component 11) found for one sentence of the text and the passages it cites. */
public interface SentenceLookup {

    /** The finding for one sentence. */
    final class State {
        /** No verdict: the sentence was not checked. */
        public static final State UNCHECKED = new State(null, false, false);

        private final Verdict verdict;
        private final boolean statedAsFact;
        private final boolean foreignRuleUnmarked;

        public State(Verdict verdict, boolean statedAsFact, boolean foreignRuleUnmarked) {
            this.verdict = verdict;
            this.statedAsFact = statedAsFact;
            this.foreignRuleUnmarked = foreignRuleUnmarked;
        }

        /**
         * GEDECKT, TEILWEISE or NICHT_GEDECKT; {@code null} while the sentence is not checked against every passage
         * it cites, or a check failed, and no checked passage carries it.
         */
        public Verdict getVerdict() {
            return verdict;
        }

        /** A claim of a maker, trader or association stated as a fact. */
        public boolean isStatedAsFact() {
            return statedAsFact;
        }

        /** A rule of another legal space without saying where it holds. */
        public boolean isForeignRuleUnmarked() {
            return foreignRuleUnmarked;
        }
    }

    /**
     * @param sentence the sentence as the text holds it, markers included
     * @param cited    the passages its markers cite, at least one
     */
    State of(String sentence, List<BookSection.Citation> cited);

    /** No sentence check ran: every sentence is unchecked. */
    SentenceLookup NONE = (sentence, cited) -> State.UNCHECKED;

    /**
     * The verdicts the sentence check stored: per cited passage the latest verdict on the sentence and the passage
     * text, combined as {@link SentenceVerdict#ofSentence} combines them. A passage not checked yet leaves the
     * sentence unchecked unless a checked passage carries it (GEDECKT or TEILWEISE).
     */
    static SentenceLookup of(SentenceVerdicts verdicts) {
        return (sentence, cited) -> {
            String key = SentenceVerdict.sentenceKey("", sentence);
            List<SentenceVerdict> found = new ArrayList<SentenceVerdict>();
            boolean missing = false;
            for (BookSection.Citation citation : cited) {
                SentenceVerdict verdict = verdicts.verdict(key,
                        SentenceVerdict.textKey(citation.getPassageId(), citation.getPassageText()));
                if (verdict == null) {
                    missing = true;
                } else {
                    found.add(verdict);
                }
            }
            Verdict combined = SentenceVerdict.ofSentence(found);
            if (missing && combined == Verdict.NICHT_GEDECKT) {
                combined = null;
            }
            boolean fact = false;
            boolean foreign = false;
            for (SentenceVerdict verdict : found) {
                fact |= verdict.getAttribution() == Attribution.ALS_TATSACHE;
                foreign |= verdict.getJurisdiction() == Jurisdiction.NICHT_GEKENNZEICHNET;
            }
            return new State(combined, fact, foreign);
        };
    }
}
