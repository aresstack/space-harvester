package com.aresstack.askai.research.runtime.team;

import com.aresstack.askai.agent.model.port.ChatRequest;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.research.runtime.role.ResearchRoles;
import org.junit.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * PR 08 (plan 5.1): the chat slot follows the LIVE phase id, not the profile. A turn in phase X runs on slot
 * {@code phase.X}, also when the registry falls back to its neutral profile (Outline until PR 25) and for the
 * opening greeting, which runs in Concept.
 */
public class PhaseSlotChatTest {

    private static PhaseChats over(final FakeModelPort port) {
        return new PhaseChats() {
            public MainModelChat chatFor(String phaseId) {
                ResearchRole role = ResearchRoles.CONVERSATION.withSlot(ResearchModelSlots.phase(phaseId));
                return PortMainModelChat.forRole(port, role, Duration.ofSeconds(30));
            }
        };
    }

    private static TeamAgentStateView in(String phase) {
        return new TeamAgentStateView(phase, "running", Arrays.asList("CANCEL"));
    }

    private static String lastSlot(FakeModelPort port) {
        List<ChatRequest> requests = port.requests();
        return requests.get(requests.size() - 1).getSlot().getName();
    }

    @Test
    public void eachTurnUsesTheSlotOfTheLivePhase() {
        FakeModelPort port = new FakeModelPort();
        for (int i = 0; i < 5; i++) {
            port.enqueueMessage("ok " + i);
        }
        ResearchTeamAgent agent = new ResearchTeamAgent(over(port), PhaseAssistantProfileRegistry.defaults(),
                new PhaseContextAssembler());

        agent.greet(new TeamAgentStateView("", "new", Arrays.asList("CANCEL")));
        assertEquals("the greeting runs in Concept", "phase.scoping", lastSlot(port));

        agent.respond("Hallo", in("research"));
        assertEquals("phase.research", lastSlot(port));

        agent.respond("Gliederung", in("outline"));
        assertEquals("Outline has no own profile yet, but its own slot", "phase.outline", lastSlot(port));

        agent.respond("Schreib", in("draft"));
        assertEquals("phase.draft", lastSlot(port));

        agent.respond("Fertig", in("finalization"));
        assertEquals("the legacy finalization phase works on the Document", "phase.draft", lastSlot(port));
    }

    @Test
    public void phaseSlotsNameThePhaseIds() {
        assertEquals(ResearchModelSlots.PHASE_SCOPING, ResearchModelSlots.phase("scoping"));
        assertEquals(ResearchModelSlots.PHASE_SCOPING, ResearchModelSlots.phase(""));
        assertEquals(ResearchModelSlots.PHASE_RESEARCH, ResearchModelSlots.phase("RESEARCH"));
        assertEquals(ResearchModelSlots.PHASE_OUTLINE, ResearchModelSlots.phase("outline"));
        assertEquals(ResearchModelSlots.PHASE_DRAFT, ResearchModelSlots.phase("draft"));
        assertEquals("phase.draft.summary", ResearchModelSlots.summary("draft").getName());
    }
}
