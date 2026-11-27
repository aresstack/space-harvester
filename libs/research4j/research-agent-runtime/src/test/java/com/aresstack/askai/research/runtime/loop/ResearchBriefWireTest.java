package com.aresstack.askai.research.runtime.loop;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;

/** The research brief travels the research wire and the UI-side parser decodes it losslessly. */
public class ResearchBriefWireTest {

    @Test
    public void aResearchBriefRoundTripsThroughTheWire() {
        String brief = "# Research Brief\n\n## Fragestellung\n\nWelche aktuellen Wearables mit Audio & Video?";
        String line = ResearchRunWire.researchBrief("scoping", brief);

        // The UI-side parser (a deliberate duplicate on the plugin side of the process boundary) decodes it.
        Map<String, String> f = com.aresstack.askai.research.agentwire.ResearchRunWire.fields(line);
        assertEquals("brief", com.aresstack.askai.research.agentwire.ResearchRunWire.typeOf(line));
        assertEquals("scoping", com.aresstack.askai.research.agentwire.ResearchRunWire.decodedField(f, "phase"));
        assertEquals(brief, com.aresstack.askai.research.agentwire.ResearchRunWire.decodedField(f, "content"));
    }
}
