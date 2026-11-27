package com.aresstack.writing4j.cornerstone;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Examples in context on invented passages (rooftop beehives). */
public class SnippetsTest {

    @Test
    public void theSentenceComesWithItsNeighboursHeadingAndSourceAndStandingSentencesFirst() {
        Map<String, TermCandidates.Text> passages = new LinkedHashMap<String, TermCandidates.Text>();
        passages.put("p1", new TermCandidates.Text("p1", "q1", "Die Imkerei braucht Platz. Diese Verordnung gilt "
                + "auch für Dachbienen. Danach ist alles klar.", "Recht > Haltung", "Imkerrecht kompakt"));
        passages.put("p2", new TermCandidates.Text("p2", "q1", "Die Bienenschutzverordnung regelt den Einsatz von "
                + "Mitteln in der Nähe von Bienenständen. Sie gilt bundesweit.", "Recht", "Imkerrecht kompakt"));
        passages.put("p3", new TermCandidates.Text("p3", "q2", "Im Sommer wird geerntet. Die Verordnung über "
                + "Honig legt fest, was als Honig verkauft werden darf. Das ist wichtig.", "Honig", "Honigbuch"));
        TermCandidates.Candidate term = new TermCandidates.Candidate("Verordnung", "verordnung",
                Arrays.asList("p1", "p2", "p3"), 3, 2, 3, 1.0, 1.0);

        List<Snippets.Snippet> snippets = new Snippets(new Snippets.Options(2, 1, 40,
                Snippets.Options.DEFAULT_BACK_REFERENCES)).of(term, term.getPassageIds(), passages);

        assertEquals("a standing sentence of each source first", Arrays.asList("p2", "p3"), ids(snippets));
        Snippets.Snippet honey = snippets.get(1);
        assertEquals("Im Sommer wird geerntet.", honey.getBefore());
        assertTrue(honey.getSentence().startsWith("Die Verordnung über Honig"));
        assertEquals("Das ist wichtig.", honey.getAfter());
        assertEquals(1, honey.getSentenceIndex());
        assertEquals("Honig", honey.getHeading());
        assertEquals("Honigbuch", honey.getSourceTitle());
        assertFalse("a back reference does not stand alone",
                new Snippets(null).standsAlone("Diese Verordnung gilt auch für Dachbienen auf jedem Dach."));
        assertFalse(new Snippets(null).standsAlone("Satz 1 gilt entsprechend für alle Bienenstände im Land."));
        assertFalse("a very short sentence does not stand alone", new Snippets(null).standsAlone("Verordnung gilt."));
    }

    private static List<String> ids(List<Snippets.Snippet> snippets) {
        List<String> out = new java.util.ArrayList<String>();
        for (Snippets.Snippet snippet : snippets) {
            out.add(snippet.getPassageId());
        }
        return out;
    }
}
