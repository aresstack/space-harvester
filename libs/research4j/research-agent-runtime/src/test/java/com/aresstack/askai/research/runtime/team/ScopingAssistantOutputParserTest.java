package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The scoping phase's own structured output contract: brief + suggestions (no visualization) + round-trip. */
public class ScopingAssistantOutputParserTest {

    @Test
    public void aSubstantiveTurnWithMessageBriefAndSuggestionIsValid() {
        ScopingAssistantOutputParser.Result r = ScopingAssistantOutputParser.parse(
                "{\"assistantMessage\":\"You want to explore wearables.\","
                        + "\"researchBriefMarkdown\":\"# Research Brief\\n\\n## Fragestellung\\n\\nWearables?\","
                        + "\"searchSuggestions\":[{\"query\":\"wearables 2026\",\"priority\":1}]}");
        assertTrue(r.getError(), r.isOk());
        assertEquals("You want to explore wearables.", r.getOutput().getAssistantMessage());
        assertTrue(r.getOutput().getResearchBriefMarkdown().contains("Wearables?"));
        assertEquals(1, r.getOutput().getSearchSuggestions().size());
        assertEquals(PhaseAdviceRecommendation.NEUTRAL, r.getOutput().getAdvice().getRecommendation());
    }

    @Test
    public void fullOutputWithSuggestionsAndAdviceIsValid() {
        ScopingAssistantOutputParser.Result r = ScopingAssistantOutputParser.parse(
                "{\"assistantMessage\":\"Got it.\",\"researchBriefMarkdown\":\"# Brief\\nWearables\","
                        + "\"searchSuggestions\":["
                        + "{\"query\":\"wearables current technology 2026\",\"purpose\":\"tech\",\"priority\":1},"
                        + "{\"query\":\"smart glasses privacy\",\"purpose\":\"privacy\",\"priority\":2}],"
                        + "\"advice\":{\"recommendation\":\"CONTINUE\",\"reason\":\"precise enough\"}}");
        assertTrue(r.getError(), r.isOk());
        assertEquals(2, r.getOutput().getSearchSuggestions().size());
        assertEquals("wearables current technology 2026", r.getOutput().getSearchSuggestions().get(0).getQuery());
        assertEquals(2, r.getOutput().getSearchSuggestions().get(1).getPriority());
        assertEquals(PhaseAdviceRecommendation.CONTINUE, r.getOutput().getAdvice().getRecommendation());
        assertEquals("precise enough", r.getOutput().getAdvice().getReason());
    }

    @Test
    public void adviceTokensParseToTheThreeRecommendations() {
        assertEquals(PhaseAdviceRecommendation.STAY, adviceOf("STAY"));
        assertEquals(PhaseAdviceRecommendation.CONTINUE, adviceOf("continue"));
        assertEquals(PhaseAdviceRecommendation.NEUTRAL, adviceOf("NEUTRAL"));
        assertEquals("an unknown token is neutral", PhaseAdviceRecommendation.NEUTRAL, adviceOf("go-for-it"));
    }

    /**
     * ONLY the visible answer is required. The structured scope is the truth of this phase; a brief is a
     * projection of it and may well be absent in a turn that just asks a question.
     */
    @Test
    public void aTurnWithNothingButAVisibleAnswerIsValid() {
        ScopingAssistantOutputParser.Result r = ScopingAssistantOutputParser.parse(
                "{\"assistantMessage\":\"Bibliotheken ist noch sehr breit. Geht es dir eher um die "
                        + "Institution, die Architektur oder die Nutzung?\"}");
        assertTrue(r.getError(), r.isOk());
        assertEquals("", r.getOutput().getResearchBriefMarkdown());
        assertTrue(r.getOutput().getSearchSuggestions().isEmpty());
        assertEquals(null, r.getOutput().getScopeUpdate());
    }

    /**
     * Zero search suggestions is a normal turn. Demanding one would train the assistant to invent searches
     * nobody asked for just so the parser accepts the answer — the very behaviour we are removing.
     */
    @Test
    public void anInterviewReplyWithoutSuggestionsIsValid() {
        ScopingAssistantOutputParser.Result r = ScopingAssistantOutputParser.parse(
                "{\"assistantMessage\":\"Welche Nutzergruppe meinst du?\","
                        + "\"researchBriefMarkdown\":\"# Brief\\nX\"}");
        assertTrue(r.getError(), r.isOk());
        assertTrue(r.getOutput().getSearchSuggestions().isEmpty());
    }

    @Test
    public void aSearchSuggestionWithABlankQueryIsRejected() {
        ScopingAssistantOutputParser.Result r = ScopingAssistantOutputParser.parse(
                "{\"assistantMessage\":\"Hi.\",\"researchBriefMarkdown\":\"# Brief\\nX\","
                        + "\"searchSuggestions\":[{\"query\":\"   \",\"priority\":1}]}");
        assertFalse(r.isOk());
        assertTrue(r.getError().contains("query"));
    }

    @Test
    public void canonicalCodecRoundTripPreservesEveryScopingField() {
        ScopingAssistantOutput original = new ScopingAssistantOutput(
                "Message with \"quotes\" and \n newline",
                "# Research Brief\n\n## Fragestellung\n\nWearables mit Audio & Video?",
                Arrays.asList(new SearchSuggestion("wearables audio video", "scope", 1),
                        new SearchSuggestion("smart glasses privacy GDPR", "", 3)),
                new PhaseAdvice(PhaseAdviceRecommendation.STAY, "one open point remains"));

        ScopingAssistantOutputParser.Result reparsed =
                ScopingAssistantOutputParser.parse(original.canonicalJson());

        assertTrue(reparsed.getError(), reparsed.isOk());
        ScopingAssistantOutput back = reparsed.getOutput();
        assertEquals(original.getAssistantMessage(), back.getAssistantMessage());
        assertEquals(original.getResearchBriefMarkdown(), back.getResearchBriefMarkdown());
        assertEquals(2, back.getSearchSuggestions().size());
        assertEquals("wearables audio video", back.getSearchSuggestions().get(0).getQuery());
        assertEquals(3, back.getSearchSuggestions().get(1).getPriority());
        assertEquals(PhaseAdviceRecommendation.STAY, back.getAdvice().getRecommendation());
        assertEquals("one open point remains", back.getAdvice().getReason());
    }

    private static PhaseAdviceRecommendation adviceOf(String token) {
        ScopingAssistantOutputParser.Result r = ScopingAssistantOutputParser.parse(
                "{\"assistantMessage\":\"Hi.\",\"researchBriefMarkdown\":\"# B\\nX\","
                        + "\"searchSuggestions\":[{\"query\":\"x\",\"priority\":1}],"
                        + "\"advice\":{\"recommendation\":\"" + token + "\"}}");
        assertTrue(r.getError(), r.isOk());
        return r.getOutput().getAdvice().getRecommendation();
    }
}
