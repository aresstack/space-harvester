package com.aresstack.writing4j.engine;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The sentence-embedding slot in the repeat check and claim-check stage 2, with a fake sentence model. */
public class SentenceSimilarityChecksTest {

    /** Sentences mentioning "Helm"/"Kopfschutz" point one way, everything else the other way. */
    private static final SentenceVectors FAKE = new SentenceVectors() {
        public List<float[]> embed(List<String> sentences) {
            List<float[]> out = new ArrayList<float[]>();
            for (String s : sentences) {
                boolean helmet = s.contains("Helm") || s.contains("Kopfschutz");
                out.add(helmet ? new float[]{1, 0.05f} : new float[]{0, 1});
            }
            return out;
        }
    };

    private static final SentenceVectors BROKEN = new SentenceVectors() {
        public List<float[]> embed(List<String> sentences) throws Exception {
            throw new java.io.IOException("model down");
        }
    };

    private static final String EARLIER = "Auf jeder Baustelle schreibt der Arbeitgeber das Tragen eines Helms vor.";
    private static final String PARAPHRASE = "Beschäftigte müssen beim Arbeiten im Rohbau stets Kopfschutz aufsetzen.";

    @Test
    public void paraphraseWithFewSharedWordsIsARepeatOnlyWithTheSentenceModel() {
        RepeatCheck.Memory lexical = new RepeatCheck(null, null).memory(Collections.singletonList(EARLIER));
        assertFalse(lexical.repeats(PARAPHRASE));
        RepeatCheck.Memory semantic = new RepeatCheck(null, null, new SentenceSimilarity(FAKE, null))
                .memory(Collections.singletonList(EARLIER));
        assertTrue(semantic.repeats(PARAPHRASE));
        RepeatCheck.Memory off = new RepeatCheck(new RepeatCheck.Criteria(6, 0.75, 0), null,
                new SentenceSimilarity(FAKE, null)).memory(Collections.singletonList(EARLIER));
        assertFalse(off.repeats(PARAPHRASE));
    }

    @Test
    public void aNewNumberIsNeverASemanticRepeat() {
        RepeatCheck.Memory semantic = new RepeatCheck(null, null, new SentenceSimilarity(FAKE, null))
                .memory(Collections.singletonList("Der Helm senkt Kopfverletzungen um 20 % auf allen Baustellen."));
        assertFalse(semantic.repeats("Kopfschutz verringert schwere Verletzungen beim Rohbau sogar um 40 %."));
    }

    @Test
    public void citedPassageSayingTheSameInOtherWordsCarriesTheClaim() {
        String passage = "Arbeitgeber verpflichten alle Beschäftigten auf der Baustelle zum Helm. Das gilt immer.";
        List<String> markers = Arrays.asList("1");
        List<String> texts = Arrays.asList(passage);
        String sentence = "Kopfschutz bleibt im Rohbau ausnahmslos vorgeschrieben für jede Person.";
        assertFalse(new ClaimCarryCheck(null).uncarried(sentence, markers, texts).isEmpty());
        assertEquals("", new ClaimCarryCheck(null, new SentenceSimilarity(FAKE, null))
                .uncarried(sentence, markers, texts));
    }

    @Test
    public void anUnreachableModelLeavesTheChecksLexical() {
        List<String> log = new ArrayList<String>();
        SentenceSimilarity broken = new SentenceSimilarity(BROKEN, log::add);
        RepeatCheck.Memory memory = new RepeatCheck(null, null, broken).memory(Collections.singletonList(EARLIER));
        assertFalse(memory.repeats(PARAPHRASE));
        assertFalse(broken.isAvailable());
        assertEquals(1, log.size());
        assertFalse(memory.repeats(PARAPHRASE));
        assertEquals(1, log.size());
    }
}
