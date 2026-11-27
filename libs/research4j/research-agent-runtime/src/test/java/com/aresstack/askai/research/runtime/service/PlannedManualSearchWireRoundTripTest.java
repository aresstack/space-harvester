package com.aresstack.askai.research.runtime.service;

import com.aresstack.askai.research.runtime.loop.ResearchRunWire;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Plan PR 14: the manual search command of a planned query carries its ranking choice, visit limit and per-query
 * source cap from the HOST encoder (ui-plugin, on this test classpath) into the RUNTIME parser; the completed line
 * carries the keyword fallback back into the HOST mapper.
 */
public class PlannedManualSearchWireRoundTripTest {

    @Test
    public void aPlannedQueryKeepsItsRankingLimitAndCap() {
        String envelope = com.aresstack.askai.research.search.ResearchServiceCommandWire.manualSearch("req-1",
                "Classloader Isolation Java", "de", "reranker", 9, 3);
        ResearchServiceCommand command = ResearchServiceCommandWire.parse(envelope);
        assertEquals(ResearchServiceCommand.TYPE_MANUAL_SEARCH, command.getType());
        assertEquals("Classloader Isolation Java", command.getQuery());
        assertEquals("de", command.getLanguage());
        assertEquals(ResearchServiceCommand.RANKING_RERANKER, command.getRanking());
        assertEquals(9, command.getVisitLimit());
        assertEquals(3, command.getSourceCap());
    }

    @Test
    public void aTypedSearchCarriesNoRanking() {
        String envelope = com.aresstack.askai.research.search.ResearchServiceCommandWire.manualSearch("req-2",
                "jpms layer", "en");
        ResearchServiceCommand command = ResearchServiceCommandWire.parse(envelope);
        assertEquals("", command.getRanking());
        assertEquals(0, command.getVisitLimit());
        assertEquals(0, command.getSourceCap());
    }

    @Test
    public void bm25IsKeptAndUnknownRankingsAreDropped() {
        assertEquals(ResearchServiceCommand.RANKING_BM25, ResearchServiceCommandWire.parse(
                com.aresstack.askai.research.search.ResearchServiceCommandWire.manualSearch("r", "q x", "en",
                        "bm25", 0, 0)).getRanking());
        assertEquals("", ResearchServiceCommandWire.parse(
                "#RSC1# manual_search request_id=r query=q ranking=engine").getRanking());
    }

    @Test
    public void theKeywordFallbackReachesTheHost() {
        String line = ResearchRunWire.manualSearchCompleted("req-3", 2, "TARGET_REACHED", true);
        assertTrue(line.endsWith(" ranked_by=keywords"));
        com.aresstack.askai.research.backend.ResearchBackendEvent event =
                com.aresstack.askai.research.agentwire.ResearchAgentEventMapper.mapMessage("p", line).build();
        assertEquals("2 keywords", event.getPublicMessage());
        assertEquals("req-3", event.getTechnicalDetail());
        String plain = ResearchRunWire.manualSearchCompleted("req-4", 2, "TARGET_REACHED", false);
        assertEquals("2", com.aresstack.askai.research.agentwire.ResearchAgentEventMapper.mapMessage("p", plain)
                .build().getPublicMessage());
    }

    @Test
    public void aTeamQueryCarriesThePageWorkersBrief() {
        String envelope = com.aresstack.askai.research.search.ResearchServiceCommandWire.teamSearch("req-5",
                "Hitzestress Bau Smartwatch", "de", "reranker", 9, 3, "Hitze & Gesundheit", "Wearables auf Baustellen",
                2, 12000, 512, 4, 2, "IN: Hitzeschutz am Bau\nOUT: Freizeitsport");
        ResearchServiceCommand command = ResearchServiceCommandWire.parse(envelope);
        assertTrue(command.isTeam());
        assertEquals("Hitzestress Bau Smartwatch", command.getQuery());
        assertEquals("Hitze & Gesundheit", command.getTeamNode());
        assertEquals("Wearables auf Baustellen", command.getTeamBook());
        assertEquals(2, command.getTeamFollow());
        assertEquals(12000, command.getTeamPageCharacters());
        assertEquals(512, command.getTeamAnswerTokens());
        assertEquals(4, command.getTeamWorkers());
        assertEquals(2, command.getTeamSearches());
        assertEquals("IN: Hitzeschutz am Bau\nOUT: Freizeitsport", command.getScope());
        assertEquals(3, command.getSourceCap());
        ResearchServiceCommand plain = ResearchServiceCommandWire.parse(
                com.aresstack.askai.research.search.ResearchServiceCommandWire.manualSearch("req-6", "q x", "en",
                        "bm25", 0, 0));
        assertEquals(false, plain.isTeam());
        assertEquals(0, plain.getTeamAnswerTokens());
    }

    @Test
    public void openPagesAndFencedWorkerQueriesReachTheHost() {
        com.aresstack.askai.research.backend.ResearchBackendEvent open =
                com.aresstack.askai.research.agentwire.ResearchAgentEventMapper.mapMessage("p",
                        ResearchRunWire.manualSearchOpenPages("req-8", 3)).build();
        assertEquals("open_pages", open.getTitle());
        assertEquals("3", open.getText());
        com.aresstack.askai.research.backend.ResearchBackendEvent dropped =
                com.aresstack.askai.research.agentwire.ResearchAgentEventMapper.mapMessage("p",
                        ResearchRunWire.manualSearchTeamQuery("req-8", "Kaffeemaschine kaufen", "https://a.example",
                                "outside scope (nearest exclusion: Shopping)")).build();
        assertEquals("team_query", dropped.getTitle());
        assertEquals("Kaffeemaschine kaufen", dropped.getText());
        assertEquals("outside scope (nearest exclusion: Shopping)", dropped.getPublicMessage());
        assertEquals("", com.aresstack.askai.research.agentwire.ResearchAgentEventMapper.mapMessage("p",
                ResearchRunWire.manualSearchTeamQuery("req-8", "DGUV Hitze", "https://a.example", null)).build()
                .getPublicMessage());
    }

    @Test
    public void aPageWorkerReportReachesTheHostAsATeamNote() {
        String line = ResearchRunWire.manualSearchTeamNote("req-7", "worker 2: search for DGUV Regel 112-198");
        com.aresstack.askai.research.backend.ResearchBackendEvent event =
                com.aresstack.askai.research.agentwire.ResearchAgentEventMapper.mapMessage("p", line).build();
        assertEquals("team_note", event.getTitle());
        assertEquals("worker 2: search for DGUV Regel 112-198", event.getText());
        assertEquals("req-7", event.getTechnicalDetail());
    }
}
