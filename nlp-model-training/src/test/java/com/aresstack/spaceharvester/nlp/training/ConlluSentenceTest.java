package com.aresstack.spaceharvester.nlp.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import org.junit.jupiter.api.Test;

class ConlluSentenceTest {

    static final String SAMPLE = """
            # Dateikopf ohne Satz

            # sent_id = s1
            # text = Er ist im Haus.
            1\tEr\ter\tPRON\tPPER\tCase=Nom\t2\tnsubj\t_\t_
            2\tist\tsein\tVERB\tVAFIN\t_\t0\troot\t_\t_
            3-4\tim\t_\t_\t_\t_\t_\t_\t_\t_
            3\tin\tin\tADP\tAPPR\t_\t5\tcase\t_\t_
            4\tdem\tder\tDET\tART\t_\t5\tdet\t_\t_
            4.1\tdort\tdort\tADV\tADV\t_\t_\t_\t5:advmod\t_
            5\tHaus\tHaus\tNOUN\tNN\t_\t2\tobl\t_\tSpaceAfter=No
            6\t.\t.\tPUNCT\t$.\t_\t2\tpunct\t_\t_

            # sent_id = s2
            1\tJa\tja\tINTJ\tITJ\t_\t0\troot\t_\t_
            """;

    static List<ConlluSentence> sample() {
        try {
            return ConlluSentence.read(new BufferedReader(new StringReader(SAMPLE)), "sample");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void readsSentencesWithSpecialLines() {
        List<ConlluSentence> sentences = sample();
        assertEquals(2, sentences.size());
        ConlluSentence first = sentences.get(0);
        assertEquals("s1", first.sentenceId());
        assertEquals(8, first.lines().size());
        assertEquals(List.of("1", "2", "3", "4", "5", "6"), first.words().stream().map(w -> w[0]).toList());
        assertEquals("s2", sentences.get(1).sentenceId());
    }

    @Test
    void formatRoundTrips() throws IOException {
        ConlluSentence first = sample().get(0);
        ConlluSentence again = ConlluSentence.read(new BufferedReader(new StringReader(first.format())), "x").get(0);
        assertEquals(first.format(), again.format());
    }

    @Test
    void rejectsWrongColumnCount() {
        assertThrows(ConlluSentence.ConlluException.class, () -> ConlluSentence.read(
                new BufferedReader(new StringReader("1\tEr\ter\n")), "kaputt"));
    }
}
