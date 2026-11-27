package com.aresstack.writing4j.cornerstone;

import com.aresstack.knowledge4j.scope.Fence;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Candidates without a model on invented passages about a made-up subject (rooftop beehives). */
public class TermCandidatesTest {

    private static final Fence FENCE = new Fence("Ein Ratgeber über Dachbienen.", "",
            Collections.<String>emptyList(), Collections.<String>emptyList(), "", "");

    @Test
    public void theGermanListOfTheGeneralLanguageComesWithTheLibrary() {
        GeneralLanguage german = GeneralLanguage.german();
        assertTrue(german.size() > 100_000);
        assertEquals(4.04, german.zipf("Baustelle"), 0.001);
        assertEquals(german.zipf("Straße"), german.zipf("strasse"), 0.0);
        assertEquals(0, german.zipf("Dachbienenstockwaage"), 0.0);
        assertEquals("another language has no list", 0, GeneralLanguage.forLanguage("en").size());
    }

    @Test
    public void nounsAbbreviationsAndPairsSpecificToTheBasketRankFirst() {
        List<TermCandidates.Text> passages = new ArrayList<TermCandidates.Text>();
        for (int i = 0; i < 4; i++) {
            String source = "q" + i;
            passages.add(new TermCandidates.Text("a" + i, source, "Auf dem Dach stehen die Dachbienen in einer "
                    + "Beute mit Rähmchen. Die Varroamilbe befällt jede Beute, deshalb hilft eine "
                    + "biologische Behandlung im Sommer. Das DIB empfiehlt die Zeit nach der Ernte."));
            passages.add(new TermCandidates.Text("b" + i, source, "Das Wetter in der Stadt war gut, und die Zeit "
                    + "verging. Die Varroamilben bleiben ein Thema."));
        }
        TermCandidates.Result result = new TermCandidates(GeneralLanguage.of(general()),
                new TermCandidates.Options(3, 2, 10, 1.5)).find(passages, FENCE, Collections.<String>emptyList());

        Map<String, TermCandidates.Candidate> byTerm = new LinkedHashMap<String, TermCandidates.Candidate>();
        for (TermCandidates.Candidate candidate : result.getCandidates()) {
            byTerm.put(candidate.getTerm(), candidate);
        }
        assertEquals("the subject word of the mission finds the anchors", Collections.singletonList("dachbien"),
                result.getSubjectWords());
        assertEquals(4, result.getAnchorPassages());
        assertFalse("the subject itself is no candidate", byTerm.containsKey("Dachbienen"));
        assertTrue(byTerm.toString(), byTerm.containsKey("Beute"));
        assertTrue("an abbreviation", byTerm.containsKey("DIB"));
        assertTrue("adjective and noun", byTerm.containsKey("biologische Behandlung"));
        TermCandidates.Candidate mite = byTerm.get("Varroamilbe");
        assertTrue("singular and plural are one term", mite != null && mite.getOccurrences() == 8);
        assertEquals("anchor passages first", "a0", mite.getPassageIds().get(0));
        assertEquals(8, mite.getPassageIds().size());
        assertFalse("sentence starts are no nouns of their own", byTerm.containsKey("Auf"));
        int beute = result.getCandidates().indexOf(byTerm.get("Beute"));
        int zeit = result.getCandidates().indexOf(byTerm.get("Zeit"));
        assertTrue("a word as common as everywhere ranks below a word of the subject", zeit < 0 || zeit > beute);
        assertFalse("the weather stands only outside the anchors", byTerm.containsKey("Wetter"));
    }

    @Test
    public void withoutAnchorsEveryPassageCountsAndTheMinimumsHold() {
        List<TermCandidates.Text> passages = Arrays.asList(
                new TermCandidates.Text("p1", "q1", "Wir prüfen die Rähmchen jeden Tag."),
                new TermCandidates.Text("p2", "q1", "Neue Rähmchen kommen im Mai."),
                new TermCandidates.Text("p3", "q2", "Alte Rähmchen werden gereinigt."),
                new TermCandidates.Text("p4", "q2", "Wir zählen die Völker."));
        TermCandidates.Result result = new TermCandidates(GeneralLanguage.of(general()),
                new TermCandidates.Options(3, 2, 10, 1.5)).find(passages, Fence.of("Ein Buch über Honig."),
                Collections.<String>emptyList());
        assertTrue(result.getSubjectWords().isEmpty());
        assertEquals(1, result.getCandidates().size());
        assertEquals("Rähmchen", result.getCandidates().get(0).getTerm());
    }

    private static Map<String, Double> general() {
        Map<String, Double> zipf = new LinkedHashMap<String, Double>();
        zipf.put("zeit", 5.9);
        zipf.put("stadt", 5.4);
        zipf.put("wetter", 4.6);
        zipf.put("dach", 4.3);
        zipf.put("thema", 5.2);
        zipf.put("sommer", 4.9);
        zipf.put("ernte", 3.9);
        zipf.put("beute", 3.4);
        zipf.put("behandlung", 4.4);
        zipf.put("dachbienen", 1.0);
        return zipf;
    }
}
