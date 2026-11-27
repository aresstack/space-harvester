package com.aresstack.askai.research.runtime.acquire;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A navigation target is NOT a search hit. The frontier is fed from selected hits, from links found on
 * visited pages and from re-queued targets; only the first kind has a rank, a snippet and a result page.
 * Squeezing all of them into one "candidate" type would mean inventing ranks for links and losing the
 * provenance that makes a run traceable afterwards.
 */
public class FrontierEntryTest {

    @Test
    public void aSelectedSearchHitKeepsItsCandidateIdAndWhatTheSerpPromised() {
        FrontierEntry entry = FrontierEntry.fromSearchResult("https://example.org/a", "c18",
                "Truthahnragout Tradition — ein historischer Überblick");

        assertEquals(FrontierEntry.Origin.SEARCH_RESULT, entry.getOrigin());
        assertTrue(entry.hasSearchCandidate());
        assertEquals("c18", entry.getSearchCandidateId());
        assertTrue(entry.getExpectedContent().contains("historischer Überblick"));
        assertEquals("", entry.getParentUrl());
    }

    @Test
    public void aDiscoveredLinkHasAParentInsteadOfARankOrACandidate() {
        FrontierEntry entry = FrontierEntry.fromDiscoveredLink("https://example.org/deep",
                "https://example.org/a");

        assertEquals(FrontierEntry.Origin.DISCOVERED_LINK, entry.getOrigin());
        assertFalse("a link found while reading is not a search hit", entry.hasSearchCandidate());
        assertEquals("https://example.org/a", entry.getParentUrl());
        assertEquals("nothing was promised about this page", "", entry.getExpectedContent());
    }

    @Test
    public void requeueingAfterAChallengeKeepsProvenanceAndExpectation() {
        FrontierEntry requeued = FrontierEntry.fromSearchResult("https://example.org/a", "c3", "Titel Snippet")
                .requeued();

        assertEquals(FrontierEntry.Origin.REQUEUED, requeued.getOrigin());
        assertEquals("c3", requeued.getSearchCandidateId());
        assertEquals("Titel Snippet", requeued.getExpectedContent());
    }

    @Test
    public void aTargetWithoutAUrlIsRefused() {
        try {
            FrontierEntry.fromDiscoveredLink("  ", "https://example.org/a");
            org.junit.Assert.fail("a frontier entry without a url is meaningless");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("url"));
        }
    }
}
