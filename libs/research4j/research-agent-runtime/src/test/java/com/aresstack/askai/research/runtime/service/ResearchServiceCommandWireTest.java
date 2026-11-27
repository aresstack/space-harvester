package com.aresstack.askai.research.runtime.service;

import org.junit.Test;

import java.net.URLEncoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The runtime-side parser of the {@code #RSC1#} service-command envelope. */
public class ResearchServiceCommandWireTest {

    @Test
    public void parsesAManualSearchEnvelopeWithAnEscapedQuery() throws Exception {
        String query = "wearables audio: & video ünïcode";
        String envelope = "#RSC1# manual_search request_id=abc-123 query="
                + URLEncoder.encode(query, "UTF-8");

        assertTrue(ResearchServiceCommandWire.isServiceCommand(envelope));
        ResearchServiceCommand command = ResearchServiceCommandWire.parse(envelope);
        assertEquals(ResearchServiceCommand.TYPE_MANUAL_SEARCH, command.getType());
        assertEquals("abc-123", command.getRequestId());
        assertEquals("the query round-trips through URL encoding", query, command.getQuery());
        assertEquals("no language field → empty (provider default stays)", "", command.getLanguage());
    }

    @Test
    public void parsesTheAuthoritativeLanguageSnapshotOfAManualSearch() {
        ResearchServiceCommand command = ResearchServiceCommandWire.parse(
                "#RSC1# manual_search request_id=R1 query=wearables language=de");
        assertEquals(ResearchServiceCommand.TYPE_MANUAL_SEARCH, command.getType());
        assertEquals("R1", command.getRequestId());
        assertEquals("wearables", command.getQuery());
        assertEquals("de", command.getLanguage());
    }

    @Test
    public void parsesASetLanguageEnvelope() {
        ResearchServiceCommand command =
                ResearchServiceCommandWire.parse("#RSC1# set_language language=de");
        assertEquals(ResearchServiceCommand.TYPE_SET_LANGUAGE, command.getType());
        assertEquals("de", command.getLanguage());
        assertEquals("no request id, no query — a pure context mutation", "", command.getRequestId());
        assertEquals("", command.getQuery());
    }

    @Test
    public void parsesTheExplicitReviewSourcesEnvelope() {
        // Issue #29: the host's "Neue Quellen auswerten" button — pinned across the process boundary.
        ResearchServiceCommand command =
                ResearchServiceCommandWire.parse("#RSC1# review_sources request_id=review-42");
        assertEquals(ResearchServiceCommand.TYPE_REVIEW_SOURCES, command.getType());
        assertEquals("review-42", command.getRequestId());
        assertEquals("", command.getQuery());
    }

    @Test
    public void plainChatTextIsNotAServiceCommand() {
        assertFalse(ResearchServiceCommandWire.isServiceCommand("just a normal question"));
        assertNull(ResearchServiceCommandWire.parse("just a normal question"));
        assertNull(ResearchServiceCommandWire.parse(null));
    }
}
