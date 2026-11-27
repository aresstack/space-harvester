package com.aresstack.writing4j.memory;

import com.aresstack.writing4j.memory.EvidenceVerdict.Kind;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.aresstack.writing4j.memory.ResearchMemoryTest.Q;
import static com.aresstack.writing4j.memory.ResearchMemoryTest.TEXT_A;
import static com.aresstack.writing4j.memory.ResearchMemoryTest.direct;
import static com.aresstack.writing4j.memory.ResearchMemoryTest.memoryWithQuestion;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Component 7 with the page-noise check's marks: verdicts on page leftovers count nowhere, the log keeps them. */
public class ResearchMemoryNoiseTest {

    static final String AD = "Jetzt anmelden: Unser Seminar zum Betriebsverfassungsrecht kostet nur 199 Euro, und "
            + "die Unterlagen bekommen Sie gratis dazu.";
    static final String CONTENTS = "Inhalt: 1 Einleitung, 2 Mitbestimmung nach dem BetrVG, 3 Datenschutz im Betrieb, "
            + "4 Muster einer Betriebsvereinbarung, 5 Literatur.";
    static final String MENU = "Startseite Seminare Termine Referenten Anmeldung Kontakt Impressum Datenschutz und "
            + "noch mehr Seiten zum Klicken.";

    private static ResearchMemory memoryWithLeftovers() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.record(direct("p1", "s1", "https://www.example.org/recht", TEXT_A));
        memory.record(direct("p2", "s2", "https://werbung.example/seminar", AD));
        memory.record(EvidenceVerdict.judged(Q, "p3", "s3", "https://verlag.example/buch", CONTENTS, Kind.HINWEIS,
                "", "nennt das Kapitel", "gemma4:12b", 1, 1000L));
        memory.record(EvidenceVerdict.judged(Q, "p4", "s4", "", MENU, Kind.FEHLER, "", "", "gemma4:12b", 1, 1000L));
        return memory;
    }

    private static PassageNoise leftovers() {
        Set<String> keys = new HashSet<String>(Arrays.asList(PassageText.key(AD), PassageText.key(CONTENTS),
                PassageText.key(MENU)));
        return keys::contains;
    }

    @Test
    public void verdictsOnPageLeftoversCountNowhere() throws Exception {
        ResearchMemory memory = memoryWithLeftovers();
        QuestionEvidence before = memory.evidenceOf(Q);
        assertEquals(2, before.getDirectCount());
        assertEquals(1, before.getLeadCount());
        assertEquals(1, before.getErrors());
        assertEquals(1, memory.toRetry().size());

        memory.useNoise(leftovers());

        QuestionEvidence evidence = memory.evidenceOf(Q);
        assertEquals(1, evidence.getDirectCount());
        assertEquals("p1", evidence.getDirect().get(0).getPassageId());
        assertEquals(1, evidence.getIndependentSources());
        assertEquals(1, evidence.getDistinctDomains());
        assertEquals("a table of contents is no lead", 0, evidence.getLeadCount());
        assertEquals(1, evidence.getJudgedPassages());
        assertEquals(0, evidence.getErrors());
        assertTrue("a leftover is not offered to the judge again", memory.toRetry().isEmpty());
        TopicEvidence topic = memory.evidenceOfTopic("T-recht");
        assertEquals(1, topic.getDirectCount());
        assertEquals(0, topic.getLeadCount());
        assertEquals(1, topic.getIndependentSources());
    }

    @Test
    public void withoutMarksEverythingCountsAgain() throws Exception {
        ResearchMemory memory = memoryWithLeftovers();
        memory.useNoise(leftovers());
        memory.useNoise(null);

        assertEquals(2, memory.evidenceOf(Q).getDirectCount());
        assertEquals(1, memory.evidenceOf(Q).getLeadCount());
        assertEquals(1, memory.toRetry().size());
        assertEquals(2, memory.evidenceOfTopic("T-recht").getIndependentSources());
    }

    @Test
    public void theLogKeepsTheVerdictsOnLeftovers() throws Exception {
        List<String> logged = new ArrayList<String>();
        ResearchMemory memory = new ResearchMemory(new ResearchMemory.Log() {
            @Override
            public void topic(ResearchTopic topic) {
            }

            @Override
            public void question(ResearchQuestion question) {
            }

            @Override
            public void verdict(EvidenceVerdict verdict) {
                logged.add(verdict.getPassageId());
            }

            @Override
            public void search(SearchRecord search) {
            }

            @Override
            public void decision(LeadDecision decision) {
            }

            @Override
            public void contradiction(Contradiction contradiction) {
            }
        });
        memory.addTopic(ResearchTopic.found("T-recht", "Recht, Datenschutz und Betriebsrat", "", null, "", 1L)
                .withFence(ResearchTopic.Fence.INNEN, "", "", 1L));
        memory.addQuestion(ResearchQuestion.open(Q, "Welche Rechte hat der Betriebsrat bei KI-Systemen?", "T-recht",
                ResearchQuestion.Origin.NUTZER, 1L));
        memory.useNoise(leftovers());

        memory.record(direct("p2", "s2", "https://werbung.example/seminar", AD));

        assertEquals(Arrays.asList("p2"), logged);
        assertEquals(0, memory.evidenceOf(Q).getDirectCount());
        assertEquals(Kind.BELEGT, memory.verdict(Q, "p2").getKind());
    }
}
