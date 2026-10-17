package com.aresstack.spaceharvester.nlp.syntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConllUTest {

    private static final String SENTENCE = """
            # sent_id = 1
            # text = Am Montag kommt er.
            1-2\tAm\t_\t_\t_\t_\t_\t_\t_\t_
            1\tAn\tan\tADP\tAPPR\t_\t3\tcase\t_\t_
            2\tdem\tder\tDET\tART\tCase=Dat\t3\tdet\t_\t_
            3\tMontag\tMontag\tNOUN\tNN\tCase=Dat|Gender=Masc\t4\tobl\t_\t_
            3.1\tleer\t_\t_\t_\t_\t_\t_\t_\t_
            4\tkommt\tkommen\tVERB\tVVFIN\t_\t0\troot\t_\t_
            5\ter\ter\tPRON\tPPER\tCase=Nom\t4\tnsubj\t_\tSpaceAfter=No
            6\t.\t.\tPUNCT\t$.\t_\t4\tpunct\t_\t_
            """;

    @Test
    void readsMultiwordTokensSpansAndFeaturesAndSkipsEmptyNodes() {
        ParsedDocument document = ConllU.read(new DocumentRef("s", "d", null), SENTENCE, List.of());
        Sentence sentence = document.sentences().get(0);

        assertEquals("Am Montag kommt er.", document.text());
        assertEquals(6, sentence.tokens().size());
        MultiwordToken am = sentence.multiwordTokens().get(0);
        assertEquals("Am", am.form());
        assertEquals(0, sentence.token(1).start());
        assertEquals(2, sentence.token(2).end(), "Teile tragen die Spanne des Mehrworttokens");
        assertEquals("Montag", document.text().substring(sentence.token(3).start(), sentence.token(3).end()));
        assertEquals(Map.of("Case", "Dat", "Gender", "Masc"), sentence.token(3).feats());
        assertEquals("obl", sentence.token(3).deprel());
        assertTrue(sentence.parsed());
    }

    @Test
    void writesWhatItReads() {
        ParsedDocument document = ConllU.read(new DocumentRef("s", "d", "7"), SENTENCE, List.of());
        String written = ConllU.write(document);

        assertTrue(written.startsWith("# newdoc id = s/d@7\n"));
        assertTrue(written.contains("1-2\tAm\t_\t_\t_\t_\t_\t_\t_\t_\n"));
        assertTrue(written.contains("5\ter\ter\tPRON\tPPER\tCase=Nom\t4\tnsubj\t_\tSpaceAfter=No\n"));
        ParsedDocument again = ConllU.read(new DocumentRef("s", "d", "7"), written, List.of());
        assertEquals(document.sentences(), again.sentences());
    }
}
